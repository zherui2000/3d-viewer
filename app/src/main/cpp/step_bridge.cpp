// JNI bridge: STEP file bytes in, triangle mesh out.
//
// OpenCASCADE does the hard part. STEP stores exact B-rep geometry (planes, cylinders,
// NURBS patches trimmed by curves), not triangles, so the file is read into a B-rep shape,
// tessellated by BRepMesh, and the per-face triangulations are flattened into the same
// positions / normals / indices layout the Kotlin parsers produce. From there the model
// goes through LoadedModel exactly like an STL: normalisation, BVH, measurement, section.
//
// Returned to Kotlin as an Object[]:
//   success: { float[] positions, float[] normals, int[] indices, int[] { solids, faces } }
//   failure: { String message }
// An array rather than a constructed Kotlin object keeps the native side free of class and
// constructor lookups that R8 would otherwise have to be told to keep.

#include <jni.h>
#include <android/log.h>

#include <cmath>
#include <sstream>
#include <string>
#include <vector>

#include <BRepBndLib.hxx>
#include <BRepLib_ToolTriangulatedShape.hxx>
#include <BRepMesh_IncrementalMesh.hxx>
#include <BRep_Tool.hxx>
#include <Bnd_Box.hxx>
#include <IFSelect_ReturnStatus.hxx>
#include <Poly_Connect.hxx>
#include <Poly_Triangulation.hxx>
#include <STEPControl_Reader.hxx>
#include <Standard_Failure.hxx>
#include <TopAbs_Orientation.hxx>
#include <TopExp_Explorer.hxx>
#include <TopLoc_Location.hxx>
#include <TopoDS.hxx>
#include <TopoDS_Face.hxx>
#include <TopoDS_Shape.hxx>
#include <gp_Dir.hxx>
#include <gp_Pnt.hxx>
#include <gp_Trsf.hxx>

namespace {

constexpr const char* kTag = "Inspect3D-STEP";

// Chordal deviation as a fraction of the model's bounding-box diagonal, and the largest
// angle between neighbouring facets. Fine enough that a 50 mm bore in a 500 mm part reads
// as round, coarse enough that a dense assembly does not run a phone out of memory.
constexpr double kRelativeDeflection = 0.0008;
constexpr double kAngularDeflection = 0.30;  // radians, about 17 degrees

jobjectArray failure(JNIEnv* env, const std::string& message) {
    __android_log_print(ANDROID_LOG_WARN, kTag, "%s", message.c_str());
    jobjectArray result = env->NewObjectArray(1, env->FindClass("java/lang/Object"), nullptr);
    env->SetObjectArrayElement(result, 0, env->NewStringUTF(message.c_str()));
    return result;
}

struct Mesh {
    std::vector<float> positions;
    std::vector<float> normals;
    std::vector<int> indices;
    int faces = 0;
};

// Appends one face's triangulation, in world coordinates and with outward normals.
void appendFace(const TopoDS_Face& face, Mesh& mesh, Poly_Connect& connect) {
    TopLoc_Location location;
    occ::handle<Poly_Triangulation> tris = BRep_Tool::Triangulation(face, location);
    if (tris.IsNull() || tris->NbTriangles() == 0) return;

    // Normals come from the underlying surface's own parametrisation, which knows nothing
    // of the face's orientation in the solid. A REVERSED face's material is on the other
    // side, so its normals are negated and its winding flipped; otherwise it would render
    // inside out and the section view would show the wrong side as the cut face.
    BRepLib_ToolTriangulatedShape::ComputeNormals(face, tris, connect);

    const gp_Trsf transform = location.Transformation();
    const bool reversed = face.Orientation() == TopAbs_REVERSED;
    // A mirroring placement (common in assemblies of left/right-handed parts) also flips
    // handedness, so it inverts the winding a second time.
    const bool flipWinding = reversed != transform.IsNegative();

    const int base = static_cast<int>(mesh.positions.size() / 3);
    const int nodeCount = tris->NbNodes();
    for (int i = 1; i <= nodeCount; ++i) {
        const gp_Pnt p = tris->Node(i).Transformed(transform);
        mesh.positions.push_back(static_cast<float>(p.X()));
        mesh.positions.push_back(static_cast<float>(p.Y()));
        mesh.positions.push_back(static_cast<float>(p.Z()));

        gp_Dir n = tris->Normal(i).Transformed(transform);
        if (reversed) n.Reverse();
        mesh.normals.push_back(static_cast<float>(n.X()));
        mesh.normals.push_back(static_cast<float>(n.Y()));
        mesh.normals.push_back(static_cast<float>(n.Z()));
    }

    const int triangleCount = tris->NbTriangles();
    for (int t = 1; t <= triangleCount; ++t) {
        int a = 0, b = 0, c = 0;
        tris->Triangle(t).Get(a, b, c);
        if (flipWinding) std::swap(b, c);
        mesh.indices.push_back(base + a - 1);
        mesh.indices.push_back(base + b - 1);
        mesh.indices.push_back(base + c - 1);
    }
    mesh.faces++;
}

jfloatArray toJava(JNIEnv* env, const std::vector<float>& values) {
    jfloatArray array = env->NewFloatArray(static_cast<jsize>(values.size()));
    if (array != nullptr) {
        env->SetFloatArrayRegion(array, 0, static_cast<jsize>(values.size()), values.data());
    }
    return array;
}

jintArray toJava(JNIEnv* env, const std::vector<int>& values) {
    jintArray array = env->NewIntArray(static_cast<jsize>(values.size()));
    if (array != nullptr) {
        env->SetIntArrayRegion(array, 0, static_cast<jsize>(values.size()),
                               reinterpret_cast<const jint*>(values.data()));
    }
    return array;
}

jobjectArray readStep(JNIEnv* env, jbyteArray bytes) {
    // One copy of the file, into a string the stream reads from. ReadStream avoids a
    // temporary file and with it any question of where, and whether, to clean it up.
    const jsize length = env->GetArrayLength(bytes);
    std::string content(static_cast<size_t>(length), '\0');
    env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte*>(content.data()));
    std::istringstream stream(std::move(content));

    STEPControl_Reader reader;
    if (reader.ReadStream("model.step", stream) != IFSelect_RetDone) {
        return failure(env, "This STEP file could not be read. It may be damaged or not a STEP file.");
    }
    // Report geometry in millimetres whatever the file was authored in; the Kotlin side
    // labels the model with that unit and converts measurements back from it.
    reader.SetSystemLengthUnit(1.0);

    if (reader.TransferRoots() <= 0) {
        return failure(env, "This STEP file contains no shapes that can be displayed.");
    }
    const TopoDS_Shape shape = reader.OneShape();
    if (shape.IsNull()) {
        return failure(env, "This STEP file contains no shapes that can be displayed.");
    }

    Bnd_Box bounds;
    BRepBndLib::Add(shape, bounds);
    if (bounds.IsVoid()) {
        return failure(env, "This STEP file contains no geometry.");
    }
    double xMin, yMin, zMin, xMax, yMax, zMax;
    bounds.Get(xMin, yMin, zMin, xMax, yMax, zMax);
    const double diagonal = std::sqrt((xMax - xMin) * (xMax - xMin) +
                                      (yMax - yMin) * (yMax - yMin) +
                                      (zMax - zMin) * (zMax - zMin));
    const double deflection = std::max(diagonal * kRelativeDeflection, 1e-6);

    // In parallel: BRepMesh splits the work per face across cores.
    BRepMesh_IncrementalMesh mesher(shape, deflection, false, kAngularDeflection, true);
    if (!mesher.IsDone()) {
        return failure(env, "The shapes in this STEP file could not be tessellated.");
    }

    Mesh mesh;
    Poly_Connect connect;
    for (TopExp_Explorer it(shape, TopAbs_FACE); it.More(); it.Next()) {
        appendFace(TopoDS::Face(it.Current()), mesh, connect);
    }
    if (mesh.indices.empty()) {
        return failure(env, "This STEP file produced no surfaces to display.");
    }

    int solids = 0;
    for (TopExp_Explorer it(shape, TopAbs_SOLID); it.More(); it.Next()) solids++;

    __android_log_print(ANDROID_LOG_INFO, kTag, "STEP: %d solids, %d faces, %zu triangles",
                        solids, mesh.faces, mesh.indices.size() / 3);

    jobjectArray result = env->NewObjectArray(4, env->FindClass("java/lang/Object"), nullptr);
    env->SetObjectArrayElement(result, 0, toJava(env, mesh.positions));
    env->SetObjectArrayElement(result, 1, toJava(env, mesh.normals));
    env->SetObjectArrayElement(result, 2, toJava(env, mesh.indices));
    env->SetObjectArrayElement(result, 3, toJava(env, std::vector<int>{solids, mesh.faces}));
    return result;
}

}  // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_inspect3d_viewer_parse_StepNative_read(JNIEnv* env, jclass, jbyteArray bytes) {
    // OpenCASCADE reports failure by throwing. Nothing may escape into the JVM: a C++
    // exception crossing JNI is undefined behaviour and in practice aborts the process.
    try {
        return readStep(env, bytes);
    } catch (const Standard_Failure& e) {
        const char* message = e.what();
        return failure(env, std::string("This STEP file could not be processed: ") +
                                ((message != nullptr && *message != '\0') ? message : "geometry error"));
    } catch (const std::bad_alloc&) {
        return failure(env, "This STEP model is too large to open on this device.");
    } catch (const std::exception& e) {
        return failure(env, std::string("This STEP file could not be processed: ") + e.what());
    } catch (...) {
        return failure(env, "This STEP file could not be processed.");
    }
}
