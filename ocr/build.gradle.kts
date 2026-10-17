import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Text reading for the See tile: PP-OCRv6 tiny detection + recognition on ONNX Runtime.
// Plain Kotlin, so the whole pipeline is tested on the build machine with real models.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val onnxruntime = "1.20.0"

dependencies {
    // The app supplies the Android build of ONNX Runtime; same Java API, same version.
    compileOnly("com.microsoft.onnxruntime:onnxruntime:$onnxruntime")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:$onnxruntime")
    testImplementation("junit:junit:4.13.2")
}
