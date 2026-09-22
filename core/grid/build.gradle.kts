import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The home grid's layout engine: pure Kotlin, no Android (ADR 0001, ADR 0005).
// Placement, collision and clamping live here so they can be tested on the JVM
// in milliseconds and reasoned about without a device.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
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

// Coverage gate (ADR 0005, AGENTS.md "Test policy"): the engine is the fork's
// most-tested component, so its bound is the highest (99.45 % on 2026-09-22). Raised as tests land,
// never lowered.
kover {
    reports {
        verify {
            rule("line coverage of :core:grid") {
                minBound(99)
            }
        }
    }
}
