package com.inspect3d.viewer.parse

import com.inspect3d.viewer.model.ModelParseException
import com.inspect3d.viewer.model.RawMesh

/**
 * STEP (ISO 10303-21: `.step`, `.stp`) reader.
 *
 * STEP carries exact CAD geometry — planes, cylinders, NURBS patches trimmed by curves —
 * rather than triangles, so unlike every other format here it has to be *tessellated*
 * before it can be drawn. That is done natively by OpenCASCADE (see `step_bridge.cpp`),
 * which reads AP203, AP214 and AP242 and meshes the result. What comes back is ordinary
 * triangles with surface normals, in millimetres, and from there a STEP model is handled
 * exactly like an STL.
 */
object StepParser {

    fun parse(bytes: ByteArray): RawMesh {
        val result = try {
            StepNative.read(bytes)
        } catch (e: LinkageError) {
            // Only possible on an ABI the native libraries were not built for. Caught as
            // LinkageError, not UnsatisfiedLinkError: the failed load happens inside the
            // object's initialiser, so the first call sees ExceptionInInitializerError and
            // every later one NoClassDefFoundError — both LinkageErrors, neither the
            // UnsatisfiedLinkError underneath. Missing any of them would crash the app.
            throw ModelParseException("STEP files are not supported on this device's processor.", e)
        }

        if (result.size == 1) {
            throw ModelParseException(result[0] as? String ?: "This STEP file could not be read.")
        }

        val positions = result[0] as FloatArray
        val normals = result[1] as FloatArray
        val indices = result[2] as IntArray
        val counts = result[3] as IntArray

        return RawMesh(
            positions = positions,
            normals = normals,
            indices = indices,
            // The bridge asks OpenCASCADE for millimetres whatever the file was authored in.
            unit = "millimeter",
            // A STEP assembly reports its solids; a surface model has none, but is still one
            // object to the viewer.
            objectCount = counts[0].coerceAtLeast(1),
        )
    }
}

/**
 * The JNI entry point, isolated so that nothing else in the app touches native code and so
 * the library is loaded on first use rather than at start-up: OpenCASCADE is a few dozen
 * libraries, and someone opening an STL should not pay to map them.
 */
internal object StepNative {
    init {
        System.loadLibrary("inspect3d_step")
    }

    /**
     * `{ FloatArray positions, FloatArray normals, IntArray indices, IntArray { solids, faces } }`
     * on success, `{ String message }` on failure.
     */
    @JvmStatic
    external fun read(bytes: ByteArray): Array<Any?>
}
