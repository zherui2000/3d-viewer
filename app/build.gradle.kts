import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // No kotlin-android plugin: AGP 9 compiles Kotlin itself ("built-in Kotlin") and
    // rejects the standalone plugin. The Compose compiler plugin is still required.
    alias(libs.plugins.kotlin.compose)
}

// OpenCASCADE, built by scripts/build-occt.ps1 (or .sh in CI), provides STEP import. Its
// location is machine-specific, so it comes from -Pocct.dir, then occt.dir in
// local.properties, then OCCT_DIR. Deliberately not an error here when absent: unit tests
// and lint do not need it, and the native build fails with a clear message if it is missing.
val occtDir: String? = providers.gradleProperty("occt.dir").orNull
    ?: rootProject.file("local.properties").takeIf { it.isFile }?.let { file ->
        Properties().apply { file.inputStream().use { load(it) } }.getProperty("occt.dir")
    }
    ?: System.getenv("OCCT_DIR")

android {
    namespace = "com.inspect3d.viewer"
    // Filament 1.77.3 requires consumers to compile against API 37 or later.
    compileSdk = 37
    // The NDK AGP 9.5 defaults to, pinned so OpenCASCADE (built with the same one by
    // scripts/build-occt) and the JNI bridge share one toolchain and one libc++_shared.so.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.inspect3d.viewer"
        // Filament performs best on API 26+, and adaptive launcher icons require it.
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "1.2.0-beta6"

        // On-device tests: the STEP bridge is native and can only be exercised there.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // 64-bit only. OpenCASCADE is built for these two, and shipping two more ABIs
            // would roughly double the APK for 32-bit phones that have all but gone.
            // x86_64 is for the emulator. Each ABI adds about 60 MB of native code, so a
            // build for one phone can drop the other: -Pinspect3d.abis=arm64-v8a
            abiFilters += providers.gradleProperty("inspect3d.abis").orNull
                ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DOCCT_DIR=${occtDir.orEmpty().replace('\\', '/')}",
                    // OpenCASCADE is built against the shared C++ runtime; the bridge must
                    // use the same one, or the two would carry separate heaps and type info.
                    "-DANDROID_STL=c++_shared",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    sourceSets {
        getByName("main") {
            // AGP packages the libraries a CMake target builds, but not ones it imports:
            // OpenCASCADE's .so files reach the APK through jniLibs instead.
            if (occtDir != null) jniLibs.directories.add("$occtDir/jniLibs")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // Stray calls into android.* return defaults instead of throwing. The parsers
            // and maths under test are plain Kotlin, so this only guards the edges.
            isReturnDefaultValues = true
        }
    }

    lint {
        // The warning list is empty as of this commit, so hold it there.
        warningsAsErrors = true
        disable += setOf(
            // These three fire when somebody else publishes a release. A dependency being
            // one version behind is a decision to make, not a defect, and letting it fail
            // the build means CI turns red on a day nobody touched this repository.
            "GradleDependency",
            "NewerVersionAvailable",
            "AndroidGradlePluginVersion",
            // targetSdk 35 under compileSdk 37 is deliberate. 37 is forced by Filament's
            // AAR metadata; 35 is the level this app's windowing, insets and permission
            // behaviour was actually tested against. Raising it is a change to verify on a
            // device, not a lint fix.
            "OldTargetApi",
        )
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // Filament's native libraries are already stripped; keep them uncompressed so
        // the runtime material compiler can be mapped quickly on first use.
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

// Kotlin's jvmTarget is not set here on purpose: with built-in Kotlin it defaults to
// android.compileOptions.targetCompatibility (17 above), so the two cannot drift apart.

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // 3D engine (rendering, scene graph, materials) ...
    implementation(libs.filament.android)
    // ... and the runtime material compiler, used to build the clipping-plane shaders
    // on device so the project needs no offline `matc` step.
    implementation(libs.filamat.android)

    testImplementation(libs.junit)
    // The real org.json, which shadows the stubbed android.jar version in unit tests.
    testImplementation(libs.json)
    // An XmlPullParser implementation for the JVM, handed to ThreeMfParser by the tests.
    testImplementation(libs.kxml2)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
