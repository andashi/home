import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The home grid's layout engine: pure Kotlin, no Android (ADR 0001, ADR 0005).
// Placement, collision and clamping live here so they can be tested on the JVM
// in milliseconds and reasoned about without a device.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)

    testImplementation(libs.junit)
}
