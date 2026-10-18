plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val abis = listOf("arm64-v8a", "armeabi-v7a")
val splitApks = (findProperty("split") as String?) == "true"
val releaseKeystore: String? = System.getenv("RP_KEYSTORE_FILE")?.takeIf { it.isNotBlank() && file(it).exists() }
// -PonnxFromSource=true uses an ONNX Runtime built from source (scripts/build_onnxruntime_android.sh), as F-Droid requires.
val onnxFromSource = (findProperty("onnxFromSource") as String?) == "true"

android {
    namespace = "com.netgician.rplauncher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.netgician.rplauncher"
        // Android 10: brings the system role picker for home, phone and SMS apps.
        minSdk = 29
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.0"

        // ONNX Runtime ships native code; phones only (32- and 64-bit ARM) keeps the app small.
        // With -Psplit=true the release is split into one smaller APK per phone type instead.
        if (!splitApks) {
            ndk {
                abiFilters += abis
            }
        }
    }

    splits {
        abi {
            isEnable = splitApks
            reset()
            include(*abis.toTypedArray())
            isUniversalApk = false
        }
    }

    signingConfigs {
        // Test builds only: a fixed debug key (standard "android" password, not a secret)
        // so each new test build installs over the last one. Releases use a separate key.
        getByName("debug") {
            storeFile = file("debug.keystore")
        }
        // The release key stays with its owner. CI reads it from repository secrets; without
        // them the release is left unsigned (F-Droid signs its own builds).
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("RP_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RP_KEY_ALIAS")
                keyPassword = System.getenv("RP_KEY_PASSWORD") ?: System.getenv("RP_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseKeystore != null) signingConfigs.getByName("release") else null
        }
    }

    lint {
        // Lint runs as its own CI step and reports there; it doesn't block a release build.
        checkReleaseBuilds = false
        abortOnError = false
    }

    // F-Droid wants a build it can reproduce; no build metadata that changes per machine.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    if (onnxFromSource) {
        sourceSets.getByName("main").jniLibs.srcDir(rootProject.file("build/onnxruntime-jni"))
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Text reading: our :ocr module on the Android build of ONNX Runtime (MIT).
    implementation(project(":ocr"))
    implementation(project(":mms"))
    if (!onnxFromSource) {
        // From source, the Java API comes in through :ocr and the native libraries through jniLibs below.
        implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    }

    // CameraX for the See tile (open source, Apache-2.0).
    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    testImplementation("junit:junit:4.13.2")
    // Real org.json on the test classpath; Android's copy is a stub in unit tests.
    testImplementation("org.json:json:20240303")
}
