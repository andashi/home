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

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    namespace = "de.mm20.launcher2.config.service"
}

dependencies {
    implementation(libs.bundles.kotlin)
    implementation(libs.androidx.core)

    implementation(libs.koin.android)

    implementation(project(":core:base"))
    implementation(project(":core:config"))
    implementation(project(":core:grid"))
    implementation(project(":core:glass"))
    implementation(project(":core:preferences"))
    implementation(project(":core:profiles"))
    implementation(project(":data:applications"))
    implementation(project(":data:searchable"))
    implementation(project(":data:homegrid"))
    implementation(project(":data:search-actions"))

    testImplementation(libs.bundles.tests)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Robolectric on JDK 17+ (https://robolectric.org/getting-started/)
tasks.withType<Test>().configureEach {
    jvmArgs(
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.net=ALL-UNNAMED",
        "--add-opens=java.base/java.security=ALL-UNNAMED",
        "--add-opens=java.base/java.text=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
    )
    // ConfigRoundTripTest reads the complete example from the docs; without
    // this, a change to the example alone would leave the task UP-TO-DATE.
    inputs.file(rootProject.file("docs/configuration/complete-example.json"))
        .withPropertyName("completeExample")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// Coverage gate (ADR 0005, AGENTS.md "Test policy"). The bound is the value the
// tests reached when the gate was introduced, rounded down to the whole
// percent; it is raised as tests land and never lowered. `koverVerifyDebug`
// runs the unit tests itself, so it is one task in CI.
kover {
    reports {
        verify {
            rule("line coverage of :services:config") {
                minBound(81)
            }
        }
    }
}
