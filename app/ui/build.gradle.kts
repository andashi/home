import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.kover)
}

android {
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.compileSdkMinor.get().toInt()
        }
    }

    packaging {
        resources.excludes.add("META-INF/DEPENDENCIES")
    }

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        signingConfig = signingConfigs.getByName("debug")
    }

    buildTypes {
        release {
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("nightly") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
            optIn.add("androidx.compose.ui.ExperimentalComposeUiApi")
            optIn.add("androidx.compose.foundation.ExperimentalFoundationApi")
            optIn.add("androidx.compose.ui.text.ExperimentalTextApi")
            optIn.add("androidx.compose.ui.unit.ExperimentalUnitApi")
            optIn.add("androidx.compose.foundation.layout.ExperimentalLayoutApi")
            optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
            optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
            optIn.add("androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi")
            optIn.add("androidx.compose.animation.ExperimentalAnimationApi")
            optIn.add("androidx.compose.animation.ExperimentalSharedTransitionApi")
        }
    }

    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
    }

    lint {
        abortOnError = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    namespace = "de.mm20.launcher2.ui"
}


dependencies {
    implementation(libs.bundles.kotlin)

    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.foundationlayout)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.uitooling)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.animationgraphics)

    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.compose.material3adaptive.navigation3)

    implementation(libs.markdown)

    implementation(libs.haze)

    implementation(libs.androidx.core)
    implementation(libs.androidx.activitycompose)
    implementation(libs.bundles.androidx.lifecycle)

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.emojipicker)

    implementation(libs.androidx.lifecycle.viewmodelcompose)
    implementation(libs.androidx.lifecycle.runtimecompose)

    implementation(libs.koin.android)
    implementation(libs.koin.androidxcompose)

    implementation(libs.coil.core)
    implementation(libs.coil.compose)

    implementation(project(":libs:material-color-utilities"))

    implementation(project(":core:base"))
    implementation(project(":core:i18n"))
    implementation(project(":core:ktx"))
    implementation(project(":core:profiles"))
    implementation(project(":services:icons"))
    implementation(project(":services:tags"))
    implementation(project(":services:search"))
    implementation(project(":core:preferences"))
    implementation(project(":data:applications"))
    implementation(project(":data:appshortcuts"))
    implementation(project(":data:homegrid"))
    implementation(project(":core:grid"))
    implementation(project(":core:glass"))
    implementation(project(":core:config"))
    implementation(project(":data:searchable"))
    implementation(project(":data:themes"))
    implementation(project(":services:badges"))
    implementation(project(":core:crashreporter"))
    implementation(project(":data:notifications"))
    implementation(project(":data:contacts"))
    implementation(project(":core:permissions"))
    implementation(project(":data:search-actions"))
    implementation(project(":services:global-actions"))
    implementation(project(":services:widgets"))
    implementation(project(":services:favorites"))
    implementation(project(":services:feed"))

    testImplementation(libs.bundles.tests)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// Coverage gate (ADR 0005, AGENTS.md "Test policy"), scoped to the code the
// fork owns in this module: the home grid and glass packages. The rest of app/ui is
// upstream and stays unmeasured on purpose. Bound = the value the tests
// reached when the package was created, rounded down; raised as tests land,
// never lowered.
kover {
    reports {
        filters {
            includes {
                classes("de.mm20.launcher2.ui.launcher.grid.*", "de.mm20.launcher2.ui.launcher.glass.*")
            }
        }
        verify {
            rule("line coverage of app/ui launcher.grid") {
                minBound(82)
            }
        }
    }
}

// Screenshot goldens live in the source tree so they are committed and CI can
// verify against them.
roborazzi {
    outputDir.set(file("src/test/roborazzi"))
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
}
