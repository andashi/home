import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.kover)
}

android {
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.compileSdkMinor.get().toInt()
        }
    }

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    namespace = "de.mm20.launcher2.config"
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}

// `ConfigParserTest` parses the example document out of ADR 0002, so the ADR is
// an input of this test task. Gradle cannot infer that: without the line below
// a change to the ADR alone leaves the task UP-TO-DATE, the test does not run,
// and a wrong example passes unnoticed - which is exactly the kind of silent
// drift the test exists to catch.
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("docs/architecture/adr/0002-config-format-json.md"))
        .withPropertyName("adr0002")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// Coverage gate (ADR 0005, AGENTS.md "Test policy"). The bound is the value the
// tests reached when the gate was introduced, rounded down to the whole
// percent; it is raised as tests land and never lowered. `koverVerifyDebug`
// runs the unit tests itself, so it is one task in CI.
kover {
    reports {
        verify {
            rule("line coverage of :core:config") {
                minBound(91)
            }
        }
    }
}
