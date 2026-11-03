# Inspect3D — local 3D model viewer with measurement and cross-sections

A native Android viewer for engineering geometry: open a `.step`/`.stp`, `.stl`, `.obj`,
`.3mf`, `.gltf` or `.glb` file from device storage or a cloud drive, orbit it, measure
point-to-point distances on the surface, and slice it with an arbitrary clipping plane to
inspect the interior. The scene is **Z-up**, with the XY plane as the ground, and an XYZ
triad in the corner shows how the view is oriented.

Kotlin · Jetpack Compose (Material 3) · MVVM + Coroutines/Flow · Google Filament 1.77.3 ·
OpenCASCADE 8.0.1 (STEP)

---

## Why Filament rather than SceneView

SceneView is a pleasant wrapper, but this app needs three things that are easier to reach
for directly:

1. **Custom clipping shaders.** The cross-section is a shader-level cut, not a
   boolean-mesh operation, so it stays interactive on million-triangle parts. That needs
   control over the material source.
2. **Raw mesh ownership.** Point-to-point measurement needs the triangles on the CPU for
   ray casting, and the same arrays feed both the GPU buffers and the BVH.
3. **Predictable camera maths.** Picking must reproduce the exact projection the renderer
   used; owning the camera parameters makes that exact rather than approximate.

Materials are compiled **on the device** with `filamat-android`, so the project needs no
offline `matc` step and no checked-in shader blobs.

## Build

STEP import is native: it needs OpenCASCADE built for Android once per machine. It takes
roughly twenty to forty minutes per ABI and is then reused:

```bash
android sdk install ndk/28.2.13676358 cmake/3.31.6
```

```bash
pwsh scripts/build-occt.ps1
```

(`bash scripts/build-occt.sh` on Linux or macOS.) Then point Gradle at the result in
`local.properties`, which the script prints for you:

```properties
occt.dir=C:/Users/you/occt-build/install/8.0.1
```

After that it is the usual:

```bash
./gradlew assembleDebug
```

Without `occt.dir`, unit tests and lint still run. Only the native build stops, with a
message saying what is missing. `-Pocct.dir=…` or an `OCCT_DIR` environment variable work
too, and the latter is how CI passes it.

Toolchain, as actually built: **JDK 17** (Temurin 17.0.20.1), **Gradle 9.8.2**, **AGP
9.5.0**, **Kotlin 2.4.21**, **compileSdk 37**, `minSdk 26`, Compose BOM 2026.09.00. A
device or emulator with OpenGL ES 3.0 is enough to run it.

Two toolchain constraints are worth knowing, because both will bite anyone who tries to
move this project onto an older stack:

* **compileSdk 37 is a floor, not a preference.** Filament 1.77.3's AAR metadata requires
  consumers to compile against API 37, which in turn forces AGP 9.x, which forces Gradle
  9.x.
* **There is no `kotlin-android` plugin.** AGP 9 compiles Kotlin itself ("built-in
  Kotlin") and fails the build if the standalone plugin is applied. The Compose compiler
  plugin is still needed. Kotlin's `jvmTarget` is deliberately not set: with built-in
  Kotlin it defaults to `android.compileOptions.targetCompatibility`, so the Java and
  Kotlin bytecode targets cannot drift apart.

> `filamat-android` carries a native shader compiler, which adds roughly 8–10 MB per ABI
> to a debug build. If that matters for release, compile the three materials in
> `ViewerMaterials.kt` offline with `matc` and load the `.filamat` files from assets
> instead; the rest of the renderer does not change.

## What the pieces are

| Layer | Files | Role |
| --- | --- | --- |
| Parsing | `parse/StlParser`, `ObjParser`, `ThreeMfParser`, `GltfParser`, `ModelRepository` | SAF document → triangles in the file's own units |
| Model | `model/LoadedModel`, `Bounds`, `Bvh` | normalisation, bounds, picking hierarchy |
| Rendering | `render/ViewerRenderer`, `ViewerMaterials`, `GeometryFactory`, `CameraState` | Filament engine, shaders, GPU buffers, camera |
| State | `viewmodel/ViewerViewModel`, `ViewerUiState` | single source of truth |
| UI | `ui/ViewerScreen`, `ControlsPanel`, `MeasurementCard`, `ViewportGestures` | Compose overlay and gestures |

### Data flow

```
SAF Uri ──(Dispatchers.IO)──▶ parser ──▶ RawMesh ──▶ LoadedModel (+BVH)
                                                          │
                                   ViewerViewModel  ◀──────┘
                                     │  StateFlow<ViewerUiState>
             ┌───────────────────────┴───────────────────────┐
             ▼                                               ▼
     Compose overlay (recomposes)                ViewerRenderer (reads once per frame)
             │                                               │
          gestures ────────────────────────────────────────▶ camera / materials / scene
```

The renderer *pulls* state once per display frame instead of being pushed updates. A
finger drag therefore updates the camera at display rate without pushing a recomposition
through the whole overlay, and any state change — slider, colour, new model — simply shows
up on the next frame.

## How the cross-section works

The plane is applied in the **vertex** shader, which writes the signed distance from each
vertex to the plane into a custom interpolant; the fragment shader turns that distance into
`baseColor.a` and `masked` blending discards what is on the cut-away side
(`ViewerMaterials.kt`).

Two details make it reliable:

* The distance is computed from `getPosition()` — **object space**. Filament's shader-side
  "world space" is translated for floating-point precision and is not guaranteed to match
  API-level world space, so plane maths must not use it. The fit transform is baked into
  the vertex data and every renderable keeps an identity transform, so object space *is*
  world space here.
* A signed distance interpolates exactly across a triangle, so the cut edge is
  pixel-accurate rather than per-vertex.

The model material is double sided, so the newly exposed inside of the shell is lit and
shaded properly. The same plane equation is handed to the picker, so taps never land on
geometry that has been cut away.

The plane's *indicator* is sized per axis from the model's support function along that
axis — its bounding box's reach in that direction — rather than from one uniform radius.
A plane cutting a flat plate across its thin axis then spans the plate, instead of a
square the size of the plate's diagonal. The fill fades out towards the border and a thin
rim marks the extent, so the indicator reads as a plane rather than a wall across the
viewport.

The default cut is the **XY plane** through the model's centre: a horizontal slice
removing the upper half, as a slicer or a floor plan would take. Azimuth turns the normal
about Z, starting at +X and moving towards +Y; elevation tilts it towards +Z.

## Orientation: Z-up

The scene is Z-up. The ground grid is the XY plane, the camera's yaw turns about +Z, and
yaw 0 is the front view, looking along +Y with +X to the right. That is the convention of
the CAD tools that write STL, 3MF and STEP, so those files are shown exactly as authored.
Under the earlier Y-up scene they appeared lying on their backs.

**glTF is the exception.** Its specification mandates +Y up, so `GltfParser` turns every
scene +90° about X as it is read, `(x, y, z) → (x, −z, y)`, which is what Blender does on
import. It is a proper rotation, so triangle winding survives (a unit test checks the
determinant). The consequence worth knowing is that a glTF model's reported dimensions and
measured deltas are in the viewer's axes, so its height is Z.

The grid rests under each model's base rather than at a fixed depth, so a flat part sits
on it the way it would on a build plate.

The **XYZ triad** in the bottom-left corner (X red, Y green, Z blue) projects each world
axis through the camera's own right, up and forward vectors. Those are the same ones the
renderer and the picker use, so the triad cannot disagree with the view. Axes pointing away
from the viewer are drawn first and dimmed.

## Display modes

| Mode | What it shows |
| --- | --- |
| Solid | the lit, physically based material, with metallic and roughness sliders |
| **Shaded** | eDrawings-style: evenly lit, no face in shadow |
| **Shaded with edges** | the same, with the part's edges outlined in black (eDrawings' default) |
| Wireframe | every triangle edge, no surface |
| Solid + mesh | the lit surface with every triangle edge, to show how the file was tessellated |

"Shaded" is a dedicated unlit material: a headlight over strong ambient,
`colour × (0.62 + 0.38 × |n·v|)`. Light always comes from the viewer's side, so no face can
turn away from it into shadow, yet the brightness still varies with the angle to the eye,
enough for curvature and steps to read. It is a material of its own rather than a
re-rigged light setup so the look cannot drift with light intensities, exposure or the
metallic slider.

The **edges** in "Shaded with edges" are *feature* edges (`FeatureEdges.kt`):

* creases where adjacent faces meet at more than 30°;
* open boundaries.

Vertices are welded by position first, since STL stores every triangle separately and
nothing would share an edge otherwise. The result is the drawing-like outline a CAD viewer
shows. A tessellated cylinder's facets meet at a few degrees and stay unmarked; its rim
against a flat cap is a 90° crease and is outlined. Extraction runs on the loading thread
in two flat hash tables and stops above 750 k triangles. The model's faces carry a small
depth bias, so lines lying exactly on them draw cleanly instead of flickering.

## How STEP import works

STEP is unlike every other format here: it stores exact CAD geometry, meaning planes,
cylinders and NURBS patches trimmed by curves, not triangles. It has to be *tessellated*
before it can be drawn. That is the job of **OpenCASCADE** (8.0.1, pinned by commit),
called through a small JNI bridge (`app/src/main/cpp/step_bridge.cpp`):

1. `STEPControl_Reader::ReadStream` parses the bytes Kotlin already holds, with no temp
   file. Geometry is requested in millimetres, whatever unit the file was authored in.
2. `BRepMesh_IncrementalMesh` tessellates the shape in parallel. The chordal deflection is
   0.08 % of the bounding-box diagonal and the angular deflection about 17°: fine enough
   that a bore reads as round, coarse enough not to exhaust a phone on an assembly.
3. Each face's triangulation is flattened into the same positions, normals and indices
   arrays the Kotlin parsers produce. From there a STEP part is handled exactly like an
   STL: normalisation, BVH, measurement, section, feature edges.

Normals come from the exact surface, not from averaged facets, so curved faces shade
smoothly. One trap is handled explicitly. OpenCASCADE computes those normals from each
surface's own parametrisation, which knows nothing of the face's orientation in the solid.
A face marked `REVERSED` therefore has its normals negated and its winding flipped, and a
mirroring placement flips the winding once more. Without that, half the faces of a typical
part would render inside out.

The bridge catches every C++ exception before it can reach the JVM, where it would abort
the process. Failures come back as a message the app shows like any other parse error.

**What it costs.** STEP support needs 23 OpenCASCADE libraries, about 46 MB per ABI. The
app therefore ships **arm64-v8a and x86_64 only**: 64-bit phones, plus the emulator. 32-bit
ARM devices are no longer supported.

**Licensing.** OpenCASCADE is LGPL 2.1 with an additional exception. It is linked
*dynamically*, as separate `.so` files, which is the arrangement the LGPL is designed
around. Users can replace those libraries, so no further obligations arise beyond shipping
the licence and offering OpenCASCADE's source. If you distribute the app, include
`LICENSE_LGPL_21.txt` and `OCCT_LGPL_EXCEPTION.txt` from the OpenCASCADE source tree.

## How measurement works

1. A tap gives a pixel. `CameraState.rayThrough` turns it into a world-space ray using the
   same projection the renderer was given (perspective or orthographic).
2. `Bvh.intersect` walks the hierarchy and returns the closest triangle hit
   (Möller–Trumbore, two-sided so interior faces exposed by the section can be measured).
3. The hit point is reconstructed from barycentrics, so it lies exactly on the surface.
4. Distances are computed in render units and converted back to the file's units with
   `LoadedModel.worldPerSourceUnit` — a 3MF authored in millimetres reads out in
   millimetres, and glTF in metres.

The BVH is built on the loading thread, so the first tap is already fast.

## Gestures

| Gesture | Action |
| --- | --- |
| One finger drag | orbit |
| Two finger pinch | zoom |
| Two finger drag | pan |
| Tap (measurement mode) | place point A, then B; a third tap starts over |

All four live in one `awaitEachGesture` loop (`ViewportGestures.kt`) rather than stacked
detectors, which is what avoids tap and transform detectors eating each other's events.

## Format support and known limits

| Format | Covered | Not covered |
| --- | --- | --- |
| STL | binary and ASCII, facet normals, winding fallback | — |
| OBJ | `v`/`vn`/`f`, all corner forms, negative indices, n-gons | `.mtl` materials, textures |
| 3MF | mesh objects, components, build items, transforms, document unit | materials, beam lattice, slices |
| glTF 2.0 | `.glb` and `.gltf` with embedded buffers, scene graph transforms, `POSITION`/`NORMAL`, quantized accessors; turned from Y-up to Z-up | external `.bin`, Draco, sparse accessors, animation, skinning, PBR textures |
| STEP | AP203, AP214, AP242 B-rep (solids, shells, surfaces, assemblies with placements), tessellated by OpenCASCADE in millimetres | colours, PMI, product structure names, wireframe-only geometry |

Other deliberate limits:

* Files are read fully into memory and capped at 300 MB (`ModelRepository`).
* Wireframe is skipped above 1.5 M triangles (`GeometryFactory.MAX_WIREFRAME_TRIANGLES`);
  solid rendering and sectioning still work.
* One material for the whole model — this is an inspection tool, not a render preview, so
  the surface colour, metallic and roughness are user controls.
* Files without vertex normals are flat shaded, which is the correct look for machined
  parts (and avoids smoothing across hard CAD edges).

## If you change the Filament version

`filament-android` and `filamat-android` must always match. Three APIs in
`ViewerMaterials.kt` are the ones that move between releases: `MaterialBuilder`'s
`uniformParameter(type, name)`, `variable(Variable.CUSTOM0, name)`, and the
`variable_<name>` naming the fragment shader reads. `TargetApi.OPENGL` assumes Filament's
default GL backend; add `VULKAN` if you switch the engine's backend.

## Tests

```bash
./gradlew test
```

80 JVM unit tests, which need no device or emulator and take seconds to run. They cover
the parts where a sign error produces plausible-looking output rather than a crash: the
parsers, the picking maths and the axis conventions.

| Suite | Tests | What it pins down |
| --- | --- | --- |
| `CameraStateTest` | 12 | screen↔world round trip, orthographic rays, clamps, aspect-aware fit, **Z-up**: front view, orbit about Z |
| `GltfParserTest` | 11 | GLB chunks, embedded base64, node transforms, Draco and external-buffer rejection, **Y-up → Z-up** turn and its determinant |
| `BvhTest` | 10 | ray/mesh intersection, including a **differential test** against a brute-force scan |
| `ObjParserTest` | 10 | quad fans, negative indices, partial normal sets, bad references |
| `StlParserTest` | 9 | binary/ASCII equivalence, the `solid`-prefixed binary trap, zero facet normals |
| `LoadedModelTest` | 7 | normalisation, unit round-trip, flat-shading expansion |
| `ThreeMfParserTest` | 7 | the row-major transform convention, units, malformed packages |
| `FeatureEdgesTest` | 5 | a box outlined by 12 edges, never its face diagonals; curved facets unmarked; the 30° crease threshold; welding a triangle soup |
| `TangentFrameTest` | 5 | the quaternion encoding against its own inverse |
| `SectionStateTest` | 4 | the XY-plane default, azimuth about Z, which half the plane equation removes |

STEP import is native, so it is tested **on a device** instead:

```bash
./gradlew connectedDebugAndroidTest -Pinspect3d.abis=x86_64
```

`StepParserInstrumentedTest` (4 tests) runs the real OpenCASCADE bridge on a hand-written
AP214 cylinder. It checks the true size in millimetres, and that every triangle faces
outward and agrees with its normals, including the bottom cap, which the file declares as a
*reversed* face. It also checks that only the rims are outlined, and that a damaged file
comes back as a message rather than a crash. All four pass on the API 36 emulator. CI does
not run them, since that would need an emulator on the runner.

Two of these earn their keep more than the rest:

* **`BvhTest`'s differential test** runs 400 random rays against 800 random triangles and
  compares every answer with a linear scan. One test exercises partitioning, sibling
  adjacency, the traversal stack and the degenerate-split fallback at once; a seeded
  `Random` keeps failures reproducible.
* **`CameraStateTest`'s round trip** projects a world point to a pixel by hand, casts a ray
  back through that pixel, and asserts it passes within 1e-3 of the point. That is what
  catches a flipped Y or a transposed aspect ratio — bugs that show up in the app as
  measurements drifting near the screen edges.

Writing them found one real defect: an STL file ending part-way through a facet left
orphan vertices that no triangle referenced but that still counted towards the model's
bounds, distorting the reported size and the camera framing.

Three small changes made this code reachable off-device, none of which altered behaviour:
`packTangentFrame` moved out of the renderer into a Filament-free `TangentFrame`,
`GltfParser` swapped `android.util.Base64` for `java.util.Base64` (API 26+), and
`ThreeMfParser` takes an injectable `XmlPullParser` factory that defaults to the
platform's.

## Continuous integration

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) — one job on `ubuntu-24.04`, run on
every push to `main`/`master`, on every pull request, and on demand. Four checks, in a single
Gradle invocation with `--continue`:

| Check | Why it is there |
| --- | --- |
| `test` | the 66 tests above — seconds, no device |
| `lint` | fails on lint **errors**; warnings are reported, not fatal |
| `assembleDebug` | what you install to try it |
| `assembleRelease` | the only variant that runs R8 |

To run exactly what CI runs, before pushing:

```bash
./gradlew test lint assembleDebug assembleRelease
```

The release build is in CI for one reason: **Filament is driven entirely through JNI**, so a
missing keep rule in `proguard-rules.pro` does not fail compilation. It produces an APK that
crashes the first time native code looks up a class by name. Only the release variant shrinks,
so only the release variant can catch that, and the APK is unsigned — no keystore is needed
and none belongs in CI. It does work: shrinking takes the APK from **63.22 MB** debug to
**46.25 MB** release, with `proguard-rules.pro`'s `-keep class com.google.android.filament.**`
holding the JNI surface intact.

### What the first run taught us

Run #1 failed, and the way it failed is worth keeping on record.

**Every action version was stale.** The workflow was first written pinning `checkout@v4`,
`setup-java@v4`, `upload-artifact@v4`, `gradle/actions@v4` and `setup-android@v3` — all from
memory rather than checked. The current majors are **v7, v6, v7, v6 and v4**. `ubuntu-latest`
had just begun migrating to Ubuntu 26 (19 October 2026), where the runner forces Node 20
actions onto Node 24, and `setup-android@v3` died there. Hence the pinned `ubuntu-24.04`:
an image change should be a deliberate commit, not something that happens mid-build.

**The `!cancelled()` guards were actively harmful.** Each check originally had its own step
guarded with `if: ${{ !cancelled() }}`, the intent being that one red run should report every
problem rather than stopping at the first. What actually happened: SDK setup failed, the steps
that depend on it were skipped — and the three build steps ran anyway and reported
**success**, in under four minutes, without an SDK and without having built anything. A green
step that proves nothing is worse than a red one. The checks now run as a single
`./gradlew test lint assembleDebug assembleRelease --continue`, which reports every failing
task the same way but cannot run at all unless setup succeeded. `!cancelled()` is still used
on the artifact uploads, where it is correct — those collect reports precisely when the build
has failed.

One accident of the first run is worth noting: `gradlew` executed even though the
`chmod +x` step was skipped, because the `100755` mode was committed to the index. The FAT32
workaround below earned its keep on the first attempt.

With both fixed, **run #3 passed every step in 5 minutes** — faster than the same four tasks
take locally, where FAT32 and a Windows filesystem make the many-small-files phase slow. It
produced three artifacts: `apks` (54.49 MB), `test-report` and `lint-report`. That APK
artifact is the load-bearing evidence rather than the green tick: `if-no-files-found: ignore`
means an empty glob uploads nothing at all, so a 54 MB artifact can only exist if both
variants really were assembled.

### Details that are not obvious

All of these were established by checking rather than guessing:

* **AGP does not download missing SDK components**, so the workflow installs them by hand. The
  versions are the ones AGP 9.5.0 resolves by default for this project — `build-tools;36.0.0`
  against `compileSdk 37` — which keeps CI compiling against the same components as a desk
  build. If that step ever reports a package it cannot find, the runner's `cmdline-tools` is
  too old to see it; pin a newer one via `setup-android`'s `cmdline-tools-version`.
* **The platform package is `platforms;android-37.0`, not `platforms;android-37`.** API 37
  introduced minor platform revisions, and the older spelling does not resolve.
* **`chmod +x ./gradlew`** is a safety net, not the mechanism. The mode is committed as
  `100755`, set with `git update-index --chmod=+x gradlew`, because this working tree lives
  on **FAT32** — which cannot record an executable bit at all, so `core.filemode` is `false`
  here and git would never pick the mode up from the file itself.

[`.gitattributes`](.gitattributes) exists for the same class of reason. Git for Windows
installs with `core.autocrlf=true`, and a CR at the end of `#!/bin/sh` makes Linux search for
an interpreter named `/bin/sh\r` and fail with a message that never mentions line endings, so
`gradlew` is pinned to `eol=lf`. `*.jar` is marked binary because the wrapper-validation step
checksums `gradle-wrapper.jar` and any normalisation would fail it.

`gradle/actions/setup-gradle` caches `~/.gradle`, so dependencies, the Gradle distribution and
the build cache survive between runs. `gradle/actions/wrapper-validation` runs before any
Gradle code does: `gradle-wrapper.jar` is the only binary committed here, which makes it the
one file a supply-chain attack would aim at.

### What lint currently says

Wiring this up surfaced one genuine lint **error** —
`UnusedMaterial3ScaffoldPaddingParameter`, because `ViewerScreen`'s `Scaffold` discards the
content padding it is handed. That is deliberate here: the viewport is meant to run edge to
edge behind the system bars, and the overlays that must clear them say so individually
(`statusBarsPadding()` on the header, `navigationBarsPadding()` on the controls). It is now
suppressed at the declaration with that reasoning written next to it, rather than hidden in a
lint baseline where the reason would be lost.

As of `1.1.0-beta1` there are **no warnings either**, and `warningsAsErrors = true` keeps it
that way. The nine that were outstanding went like this:

| Warning | Resolution |
| --- | --- |
| `UnusedResources` ×2 | the two leftover strings deleted |
| `ObsoleteSdkInt` | `mipmap-anydpi-v26` → `mipmap-anydpi`; the qualifier is redundant at `minSdk 26` |
| `MonochromeLauncherIcon` | `<monochrome>` layer added, so Android 13+ themed icons look right |
| `DataExtractionRules` | `data_extraction_rules.xml` plus its pre-12 twin `backup_rules.xml` — lint insists on both once one exists |
| `GradleDependency` / `NewerVersionAvailable` ×3 | documentfile 1.1.0, kxml2 2.3.0, coroutines 1.11.0 — then **disabled** |
| `OldTargetApi` | **disabled**, deliberately |

The disabled checks are a judgement, not an oversight. The version checks fire whenever
somebody *else* publishes a release, so with `warningsAsErrors` on they would turn CI red on a
day nobody touched the repository. `OldTargetApi` stays off because `targetSdk 35` is the level
this app's windowing, insets and permissions were actually tested at; raising it is a change to
verify on a device, not a lint fix. All four are named, with reasons, in `app/build.gradle.kts`.

> Only `ci.yml` assumes GitHub. The four commands are plain Gradle, so moving to GitLab CI,
> Jenkins or anything else means re-expressing the JDK, SDK and `chmod` setup — nothing in the
> project is tied to a provider.

## Status

**Compiles clean and produces an APK**, at `app/build/outputs/apk/debug/app-debug.apk`.
Since STEP support, native code dominates its size: about 60 MB per ABI, of which
OpenCASCADE is 46. A universal debug build (arm64 + x86_64) is about 139 MB, and a
single-ABI build for one phone (`-Pinspect3d.abis=arm64-v8a`) is roughly half that.

### 1.2.0-beta1, verified on the emulator

| Checked | Result |
| --- | --- |
| Z-up | STL box lies flat: 100 along X, 60 along Y, 40 tall, resting on the grid |
| glTF turn | the glTF box reads `2.000 × 0.500 × 1.000 m`, upright, its 1 m height now along Z |
| Axis triad | Z points up, and X and Y match the box's own edges as the view orbits |
| Shaded with edges | even lighting with no dark side; the box's 12 edges are outlined and none of its face diagonals are |
| Section default | cuts horizontally, the XY plane through the centre |
| STEP | `cylinder.step` reads `STEP · 216 triangles · 19.98 × 19.99 × 30.00 mm`, upright and smooth-shaded; in Shaded with edges only the rims are outlined |
| STEP, on device | `StepParserInstrumentedTest`: 4 of 4 pass |

### 1.0, first build

Exactly two code problems surfaced on the first real compile, both API-naming mistakes
rather than logic: Filament's method is `Scene.remove(entity)`, not `removeEntity`, and
`GltfParser.kt` had been truncated mid-statement on disk, so it was rewritten in full.

### Verified on an emulator (API 36, x86_64, GLES emulation)

Driven end to end with `adb`, loading a generated 100 × 60 × 40 binary STL cube:

| Checked | Result |
| --- | --- |
| Binary STL parsing | header reads `STL · 12 triangles · 100.00 × 60.00 × 40.00` — exact source dimensions |
| Runtime shader compilation | all three materials compiled on device; no error banner |
| Tangent-frame quaternions | the three visible faces shade distinctly, so the hand-written packing is right |
| Camera framing | model fits the portrait viewport after the aspect-ratio fix |
| Measurement | two taps gave `48.42`, `ΔX +48.29`, `ΔY +3.462`, **`ΔZ +0.0000`** |
| Section plane | clips the model at the plane, interior shaded by the double-sided material |
| ACTION_VIEW | opening a model from another app works |

The `ΔZ` of exactly zero is the useful one: the face tapped is a constant-Z plane, so two
independent ray casts landing on it must agree to zero in Z. Ray generation, BVH traversal
and barycentric reconstruction are all confirmed by that single number.

### All four parsers, checked against generated files

Each test file targets a path the others do not. Reported dimensions are the check, since a
parser that misreads indices, a transform or a unit cannot produce the right numbers.

| File | Exercises | Reported |
| --- | --- | --- |
| `cube.stl` | binary STL, facet normals | STL, 12 tri, 100.0 × 60.0 × 40.0 |
| `box.obj` | quad faces (fan triangulation), no `vn` (flat fallback), **negative indices** | OBJ, 12 tri, 80.0 × 40.0 × 20.0 |
| `box.3mf` | zip container, **build-item transform** `diag(3, 3, 6)` on a 10³ mesh, document unit | 3MF, 12 tri, 30.0 × 30.0 × 60.0 **mm** |
| `box.glb` | GLB chunks, `UNSIGNED_SHORT` indices, no `NORMAL` | glTF, 12 tri, 2.0 × 1.0 × 0.5 **m** |

The 3MF case is the sharpest: 10³ in, 30 × 30 × 60 out, which only happens if the
row-major 4x3 transform is converted to column-major correctly. The OBJ case needs the
render as well as the numbers — a mis-resolved negative index leaves a hole rather than
changing the bounds, and the box draws closed.

`scratchpad/make_models.ps1` in the session's working files generates all three.

### Open: a crash reported on one physical device

The app was reported closing immediately on launch on a real phone. It has **not been
reproduced** — it runs correctly on the emulator — and no crash log was ever captured, so
the cause remains unknown. Ruled out: a missing native library (all four ABIs ship).

Several changes since that report may have addressed it, but none is confirmed as the fix:

* androidx `activity`/`lifecycle`/`core` were upgraded to match the 2026 Compose BOM; the
  original pairing with 2024-era artifacts was an untested combination.
* `Filament.init()` no longer takes the process down if the native library fails to load.
* Engine creation is wrapped, so a driver rejection renders an error screen naming the
  exception rather than vanishing.
* The compiled material payload is copied into a *direct* `ByteBuffer` before being handed
  to Filament, which reads payloads through the JNI direct-buffer address.

**From `1.1.0-beta1` the app captures its own crashes**, which is the most useful thing it
can do about this. `CrashLog` installs an uncaught-exception handler before Filament is
touched, writes the trace to the app's storage on the way down — with the build, device,
Android level and ABIs in the header — and shows it on the next launch in a dialog with a
**Copy and dismiss** button. The handler chains to the system's afterwards, so the crash is
still recorded normally rather than swallowed.

Verified on the emulator end to end: `adb shell am crash com.inspect3d.viewer` produced a
919-byte report, the process died properly, the next launch showed the dialog, dismissing it
deleted the file, and a further relaunch was clean.

**One limit, which matters for this particular bug:** that handler sees *Java/Kotlin*
exceptions only. A native abort (`SIGABRT`/`SIGSEGV`) inside Filament kills the process
without passing through it, so if the beta still closes on that phone **and no dialog appears
afterwards**, that absence is itself the diagnosis: it points at the native layer. Then only
`adb logcat -b crash -d` will show the cause.

### Rough edges

* The files above are small and synthetic. Nothing has been run against a real
  multi-megabyte CAD export, so the BVH build time, the 300 MB read cap and the
  wireframe triangle limit are all untested at scale.
* glTF files with external `.bin` buffers, Draco compression or sparse accessors are
  rejected with a message rather than supported.
