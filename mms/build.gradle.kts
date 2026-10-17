import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Picture and group texts (MMS): reading and writing the message format, plus the
// contact cards and calendar invites that arrive inside them. Plain Java and Kotlin with
// no Android parts, so every format rule is tested on the build machine.
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

dependencies {
    testImplementation("junit:junit:4.13.2")
}
