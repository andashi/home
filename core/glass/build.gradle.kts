import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Glass surfaces' pure logic (ADR 0004, #24): the contrast factors, the
// backdrop's crop, downscale and blur, and the cache that makes the blur run
// once per wallpaper change. No Android, so it is tested on the JVM and the
// blur needs no GPU (it runs on the GrapheneOS emulator as it does on a phone).
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
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Coverage gate (ADR 0005, AGENTS.md "Test policy"): set at the value the
// tests reach when the module is introduced, rounded down; raised as tests
// land, never lowered.
kover {
    reports {
        verify {
            rule("line coverage of :core:glass") {
                minBound(100)
            }
        }
    }
}
