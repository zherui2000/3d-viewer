#!/usr/bin/env bash
# Builds the OpenCASCADE toolkits the app needs for STEP import, for Android.
#
# Mirrors scripts/build-occt.ps1 (used on Windows); CI runs this one. Keep the two in
# step: the pins below are also the CI cache key, so changing any of them rebuilds.
#
# Output, under $OUT_DIR:
#   <abi>/inc         headers, for the JNI bridge to compile against
#   <abi>/lib         .so files, for the bridge to link against
#   jniLibs/<abi>     the same .so files, laid out for Gradle to package
#
# Only TKDESTEP (STEP reader) and TKMesh (mesher) are requested; OpenCASCADE's CMake pulls
# in exactly their dependency closure. No OpenGL toolkit is built and FreeType is off.
set -euo pipefail

OCCT_TAG="V8_0_1"
OCCT_COMMIT="b8f597c677811d1f9f4d8a97f5ae2825c0353a42"
NDK_VERSION="28.2.13676358"
CMAKE_VERSION="3.31.6"
MIN_SDK=26

ABIS="${ABIS:-arm64-v8a x86_64}"
WORK_DIR="${WORK_DIR:-$HOME/occt-build}"
OUT_DIR="${OUT_DIR:-$WORK_DIR/install/8.0.1}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:?Set ANDROID_HOME}}"

NDK="$SDK/ndk/$NDK_VERSION"
CMAKE="$SDK/cmake/$CMAKE_VERSION/bin/cmake"
NINJA="$SDK/cmake/$CMAKE_VERSION/bin/ninja"
for required in "$NDK" "$CMAKE" "$NINJA"; do
  [[ -e "$required" ]] || { echo "Missing $required (install ndk;$NDK_VERSION and cmake;$CMAKE_VERSION)" >&2; exit 1; }
done

# ---- source: pinned by commit, since a tag can be moved and a hash cannot -----------
SRC="$WORK_DIR/src"
if [[ ! -d "$SRC" ]]; then
  mkdir -p "$WORK_DIR"
  git -c advice.detachedHead=false clone --depth 1 --branch "$OCCT_TAG" \
    https://github.com/Open-Cascade-SAS/OCCT.git "$SRC"
fi
HEAD="$(git -C "$SRC" rev-parse HEAD)"
if [[ "$HEAD" != "$OCCT_COMMIT" ]]; then
  echo "OpenCASCADE checkout is at $HEAD, expected $OCCT_COMMIT ($OCCT_TAG)." >&2
  exit 1
fi

# ---- build --------------------------------------------------------------------------
MODULES="FoundationClasses ModelingData ModelingAlgorithms Visualization ApplicationFramework DataExchange Draw Deprecated"

for ABI in $ABIS; do
  BUILD="$WORK_DIR/build-$ABI"
  INSTALL="$OUT_DIR/$ABI"
  echo "==> OpenCASCADE $OCCT_TAG for $ABI"

  module_flags=()
  for m in $MODULES; do module_flags+=("-DBUILD_MODULE_$m=OFF"); done

  "$CMAKE" -G Ninja -S "$SRC" -B "$BUILD" \
    -DCMAKE_MAKE_PROGRAM="$NINJA" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$MIN_SDK" \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_LIBRARY_TYPE=Shared \
    -DINSTALL_DIR="$INSTALL" \
    -DINSTALL_DIR_INCLUDE=inc \
    -DINSTALL_DIR_LIB=lib \
    -DINSTALL_DIR_CMAKE=lib/cmake/opencascade \
    -DINSTALL_DIR_RESOURCE=res \
    "-DBUILD_ADDITIONAL_TOOLKITS=TKDESTEP TKMesh" \
    -DUSE_FREETYPE=OFF \
    -DUSE_TK=OFF \
    -DUSE_FREEIMAGE=OFF \
    -DUSE_RAPIDJSON=OFF \
    -DUSE_DRACO=OFF \
    -DUSE_TBB=OFF \
    -DUSE_EIGEN=OFF \
    -DBUILD_DOC_Overview=OFF \
    -DBUILD_DOC_RefMan=OFF \
    -DBUILD_GTEST=OFF \
    "${module_flags[@]}"

  "$CMAKE" --build "$BUILD" --target install

  # Gradle packages prebuilt libraries from <dir>/<abi>/*.so; it does not package
  # libraries a CMake target merely imports.
  mkdir -p "$OUT_DIR/jniLibs/$ABI"
  cp "$INSTALL"/lib/*.so "$OUT_DIR/jniLibs/$ABI/"
done

echo "$OCCT_TAG $OCCT_COMMIT" > "$OUT_DIR/OCCT_VERSION"
echo "==> Done: $OUT_DIR"
