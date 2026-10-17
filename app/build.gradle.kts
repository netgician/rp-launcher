plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.netgician.rplauncher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.netgician.rplauncher"
        // Android 10: brings the system role picker for home, phone and SMS apps.
        minSdk = 29
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"

        // ONNX Runtime ships native code; phones only (32- and 64-bit ARM) keeps the app small.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        // Test builds only: a fixed debug key (standard "android" password, not a secret)
        // so each new test build installs over the last one. Releases use a separate key.
        getByName("debug") {
            storeFile = file("debug.keystore")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
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
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

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
