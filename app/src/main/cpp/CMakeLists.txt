cmake_minimum_required(VERSION 3.22)
project(inspect3d_step LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# OCCT_DIR is passed in by Gradle (occt.dir in local.properties, or -Pocct.dir) and is
# the output of scripts/build-occt.ps1 / .sh: <OCCT_DIR>/<abi>/{inc,lib}.
if(NOT OCCT_DIR)
    message(FATAL_ERROR "OCCT_DIR is not set. Build OpenCASCADE with scripts/build-occt and set occt.dir.")
endif()
set(OCCT_ABI_DIR "${OCCT_DIR}/${ANDROID_ABI}")
if(NOT EXISTS "${OCCT_ABI_DIR}/inc/STEPControl_Reader.hxx")
    message(FATAL_ERROR "No OpenCASCADE build for ${ANDROID_ABI} at ${OCCT_ABI_DIR}.")
endif()

add_library(inspect3d_step SHARED step_bridge.cpp)
target_include_directories(inspect3d_step PRIVATE "${OCCT_ABI_DIR}/inc")

# Only the toolkits the bridge calls into directly. Their own dependencies are recorded in
# each library's DT_NEEDED and are resolved by the loader from the APK's library folder,
# where Gradle packages the whole OpenCASCADE build (see jniLibs in build.gradle.kts).
set(OCCT_LINKED
    TKDESTEP TKXSBase TKDE TKMesh TKTopAlgo TKShHealing
    TKGeomAlgo TKBRep TKGeomBase TKG3d TKG2d TKMath TKernel)
foreach(toolkit IN LISTS OCCT_LINKED)
    add_library(${toolkit} SHARED IMPORTED)
    set_target_properties(${toolkit} PROPERTIES IMPORTED_LOCATION "${OCCT_ABI_DIR}/lib/lib${toolkit}.so")
endforeach()

find_library(android-log log)
target_link_libraries(inspect3d_step PRIVATE ${OCCT_LINKED} ${android-log})
