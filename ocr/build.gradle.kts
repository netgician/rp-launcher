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
val onnxFromSource = (findProperty("onnxFromSource") as String?) == "true"

dependencies {
    // The app supplies the Android build of ONNX Runtime; same Java API, same version.
    if (!onnxFromSource) {
        compileOnly("com.microsoft.onnxruntime:onnxruntime:$onnxruntime")
        testImplementation("com.microsoft.onnxruntime:onnxruntime:$onnxruntime")
    }
    testImplementation("junit:junit:4.13.2")
}

// From source (F-Droid): ONNX Runtime's own Java API is compiled here, from the checked-out source,
// and reaches the app through this module. The model tests need the desktop runtime, so they're
// left to the regular build.
if (onnxFromSource) {
    val ort = rootProject.file("build/onnxruntime-src/java/src/main")
    sourceSets.named("main") {
        java.srcDir(ort.resolve("java"))
        java.srcDir(ort.resolve("android"))
        java.exclude("**/module-info.java")
    }
    tasks.withType<Test>().configureEach { enabled = false }
}
