import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ---------------------------------------------------------------------------------------------
// Release signing is read from keystore.properties (never committed) or from environment
// variables, so CI can inject the keystore without touching the repo.
// ---------------------------------------------------------------------------------------------
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}

fun signingValue(key: String, env: String): String? =
    (keystoreProps.getProperty(key) ?: System.getenv(env))?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("storeFile", "IBB_KEYSTORE_FILE")
val hasReleaseSigning = releaseStoreFile != null && rootProject.file(releaseStoreFile).exists()

android {
    namespace = "dev.ibb.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.ibb.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        ndk {
            // Native bridge ABIs shipped in jniLibs (PojavLauncher-derived execution layer).
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        // Native code is opt-in: by default the execution layer is consumed as prebuilt
        // `jniLibs` (PojavLauncher's GPLv3 `pojavexec`/`exithook`), so `assembleDebug` needs no
        // NDK. Pass -PwithNative=true to also build the optional C++ shim in src/main/cpp.
        if (project.hasProperty("withNative")) {
            ndkVersion = "27.0.12077973"
            externalNativeBuild {
                cmake {
                    cppFlags += listOf("-std=c++17", "-fno-exceptions")
                    arguments += listOf("-DANDROID_STL=c++_static")
                }
            }
        }
    }

    /*
     * Per-ABI APKs for release: `./gradlew :app:assembleRelease -PabiSplit=true` emits one ~15 MB
     * APK per architecture instead of a ~30 MB universal build. Play Store AABs do this
     * automatically; this flag is for sideloaded builds.
     */
    if (project.hasProperty("abiSplit")) {
        splits {
            abi {
                isEnable = true
                reset()
                include("arm64-v8a", "armeabi-v7a", "x86_64")
                isUniversalApk = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
            // Keeps the Compose preview tooling & readable crash logs while iterating.
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.create("release") {
                    storeFile = rootProject.file(releaseStoreFile!!)
                    storePassword = signingValue("storePassword", "IBB_KEYSTORE_PASSWORD")
                    keyAlias = signingValue("keyAlias", "IBB_KEY_ALIAS")
                    keyPassword = signingValue("keyPassword", "IBB_KEY_PASSWORD")
                }
            } else {
                // No release keystore configured: still produce an installable APK signed with
                // the debug key (that is what `assembleRelease` does for sideloading) instead of
                // an unsigned artifact. Configure keystore.properties for a store-ready build.
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-Xjvm-default=all",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/*.kotlin_module",
        )
        jniLibs.useLegacyPackaging = false
    }

    if (project.hasProperty("withNative")) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    androidResources {
        // Brand fonts + master assets are pre-shrunk; keep them uncompressed for mmap loading.
        noCompress += listOf("xz", "ttf", "jar", "zip")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)


    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.tukaani.xz)

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
