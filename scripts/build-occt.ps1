<#
.SYNOPSIS
  Builds the OpenCASCADE toolkits the app needs for STEP import, for Android.

.DESCRIPTION
  Fetches OpenCASCADE at a pinned release commit, cross-compiles it with the Android NDK
  for each requested ABI, and installs headers plus shared libraries under -OutDir:

    <OutDir>\<abi>\inc            headers, for the JNI bridge to compile against
    <OutDir>\<abi>\lib            the .so files, for the bridge to link against
    <OutDir>\jniLibs\<abi>        the same .so files, laid out for Gradle to package

  Only the STEP reader (TKDESTEP) and the mesher (TKMesh) are requested; OpenCASCADE's
  own CMake pulls in exactly their dependency closure. No module is built whole, no
  OpenGL toolkit is built, and FreeType is disabled.

  Point the app at the result with occt.dir=<OutDir> in local.properties.

  Mirrors scripts/build-occt.sh, which CI runs. Keep the two in step.
#>
param(
    [string[]] $Abis = @("arm64-v8a", "x86_64"),
    [string] $WorkDir = (Join-Path $HOME "occt-build"),
    [string] $OutDir = (Join-Path (Join-Path $HOME "occt-build") "install\8.0.1"),
    [string] $Sdk = (Join-Path $env:LOCALAPPDATA "Android\Sdk")
)

# Deliberately not "Stop": Windows PowerShell 5.1 turns anything a native tool writes to
# stderr into a terminating error once output is redirected, and CMake prints ordinary
# warnings there. Every native step below checks $LASTEXITCODE instead.
$ErrorActionPreference = "Continue"

# Pinned: a tag can be moved, a commit hash cannot.
$OcctTag = "V8_0_1"
$OcctCommit = "b8f597c677811d1f9f4d8a97f5ae2825c0353a42"

# The same NDK AGP 9.5 defaults to, so the bridge and OpenCASCADE share one toolchain and
# one libc++_shared.so. minSdk matches the app's.
$NdkVersion = "28.2.13676358"
$CmakeVersion = "3.31.6"
$MinSdk = 26

$git = (Get-Command git -ErrorAction SilentlyContinue).Source
if (-not $git) { $git = "C:\Program Files\Git\cmd\git.exe" }
$ndk = Join-Path $Sdk "ndk\$NdkVersion"
$cmakeBin = Join-Path $Sdk "cmake\$CmakeVersion\bin"
$cmake = Join-Path $cmakeBin "cmake.exe"
$ninja = Join-Path $cmakeBin "ninja.exe"

foreach ($required in @($ndk, $cmake, $ninja)) {
    if (-not (Test-Path $required)) {
        throw "Missing $required. Install it with: android sdk install ndk/$NdkVersion cmake/$CmakeVersion"
    }
}

# ---- source -------------------------------------------------------------------------
$src = Join-Path $WorkDir "src"
if (-not (Test-Path $src)) {
    New-Item -ItemType Directory -Force $WorkDir | Out-Null
    & $git -c advice.detachedHead=false clone --depth 1 --branch $OcctTag `
        https://github.com/Open-Cascade-SAS/OCCT.git $src
    if ($LASTEXITCODE -ne 0) { throw "git clone failed" }
}
$head = (& $git -C $src rev-parse HEAD).Trim()
if ($head -ne $OcctCommit) {
    throw "OpenCASCADE checkout is at $head, expected $OcctCommit ($OcctTag). Delete $src and rerun."
}

# ---- build --------------------------------------------------------------------------
$modules = @(
    "FoundationClasses", "ModelingData", "ModelingAlgorithms", "Visualization",
    "ApplicationFramework", "DataExchange", "Draw", "Deprecated"
)

foreach ($abi in $Abis) {
    $build = Join-Path $WorkDir "build-$abi"
    $install = Join-Path $OutDir $abi
    Write-Host "==> OpenCASCADE $OcctTag for $abi"

    $configure = @(
        "-G", "Ninja",
        "-S", $src,
        "-B", $build,
        "-DCMAKE_MAKE_PROGRAM=$ninja",
        "-DCMAKE_TOOLCHAIN_FILE=$ndk/build/cmake/android.toolchain.cmake",
        "-DANDROID_ABI=$abi",
        "-DANDROID_PLATFORM=android-$MinSdk",
        "-DANDROID_STL=c++_shared",
        "-DCMAKE_BUILD_TYPE=Release",
        "-DBUILD_LIBRARY_TYPE=Shared",
        "-DINSTALL_DIR=$install",
        "-DINSTALL_DIR_INCLUDE=inc",
        "-DINSTALL_DIR_LIB=lib",
        "-DINSTALL_DIR_CMAKE=lib/cmake/opencascade",
        "-DINSTALL_DIR_RESOURCE=res",
        "-DBUILD_ADDITIONAL_TOOLKITS=TKDESTEP TKMesh",
        "-DUSE_FREETYPE=OFF",
        "-DUSE_TK=OFF",
        "-DUSE_FREEIMAGE=OFF",
        "-DUSE_RAPIDJSON=OFF",
        "-DUSE_DRACO=OFF",
        "-DUSE_TBB=OFF",
        "-DUSE_EIGEN=OFF",
        "-DBUILD_DOC_Overview=OFF",
        "-DBUILD_DOC_RefMan=OFF",
        "-DBUILD_GTEST=OFF"
    )
    foreach ($m in $modules) { $configure += "-DBUILD_MODULE_$m=OFF" }

    & $cmake @configure
    if ($LASTEXITCODE -ne 0) { throw "CMake configure failed for $abi" }

    & $cmake --build $build --target install
    if ($LASTEXITCODE -ne 0) { throw "Build failed for $abi" }

    # Gradle packages prebuilt libraries from <dir>/<abi>/*.so; it does not package
    # libraries a CMake target merely imports.
    $jni = Join-Path $OutDir "jniLibs\$abi"
    New-Item -ItemType Directory -Force $jni | Out-Null
    Copy-Item (Join-Path $install "lib\*.so") $jni -Force
}

Set-Content -Path (Join-Path $OutDir "OCCT_VERSION") -Value "$OcctTag $OcctCommit" -Encoding ascii
Write-Host "==> Done. Add to local.properties:  occt.dir=$($OutDir -replace '\\','/')"
