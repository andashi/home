import java.time.LocalDate
import java.time.format.DateTimeFormatter
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
}

android {
    androidResources {
        generateLocaleConfig = true
    }

    packaging {
        resources.excludes.add("META-INF/DEPENDENCIES")
        resources.excludes.add("META-INF/LICENSE")
        resources.excludes.add("META-INF/LICENSE.txt")
        resources.excludes.add("META-INF/license.txt")
        resources.excludes.add("META-INF/NOTICE")
        resources.excludes.add("META-INF/NOTICE.txt")
        resources.excludes.add("META-INF/notice.txt")
        resources.excludes.add("META-INF/ASL2.0")
        resources.excludes.add("META-INF/LICENSE.md")
        resources.excludes.add("META-INF/NOTICE.md")
    }

    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.compileSdkMinor.get().toInt()
        }
    }
    defaultConfig {
        // Andashi Home (ADR 0006). Kotlin packages keep the upstream namespace.
        applicationId = "org.andashi.home"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = System.getenv("VERSION_CODE_OVERRIDE")?.toIntOrNull() ?: 2026091500
        // Releases take their version from the git tag (.github/workflows/release.yml).
        versionName = System.getenv("VERSION_NAME_OVERRIDE") ?: "0.1.0-dev"
        signingConfig = signingConfigs.getByName("debug")
    }

    // Release signing (ADR 0006): the organization's key, provided by the
    // environment. In CI the keystore is decoded to $RUNNER_TEMP/keystore;
    // locally set KEYSTORE_PATH. Without these variables a release build comes
    // out unsigned (not installable) rather than silently signed with the
    // debug key.
    val releaseKeystore = System.getenv("KEYSTORE_PATH")
        ?: System.getenv("RUNNER_TEMP")?.let { "$it/keystore/keystore.jks" }
    val releaseSigningAvailable = releaseKeystore != null &&
            file(releaseKeystore).exists() &&
            System.getenv("KEYSTORE_PASSWORD") != null &&
            System.getenv("SIGNING_KEY_ALIAS") != null

    signingConfigs {
        create("gh-actions") {
            if (releaseSigningAvailable) {
                storeFile = file(releaseKeystore!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (releaseSigningAvailable) signingConfigs.getByName("gh-actions") else null
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        create("nightly") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            applicationIdSuffix = ".nightly"
            versionNameSuffix = "-${LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))}-nightly"
            signingConfig = signingConfigs.findByName("gh-actions")

            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        flavorDimensions += "variant"
        productFlavors {
            create("default") {
                dimension = "variant"
            }
            create("fdroid") {
                dimension = "variant"
                versionNameSuffix = "-fdroid"
            }
        }
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

    lint {
        abortOnError = false
    }
    namespace = "de.mm20.launcher2"
    buildFeatures {
        buildConfig = true
    }
}


dependencies {
    implementation(libs.bundles.kotlin)

    //Android Jetpack
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core)

    implementation(libs.coil.core)
    implementation(libs.coil.svg)

    implementation(libs.koin.android)
    implementation(project(":data:applications"))
    implementation(project(":data:appshortcuts"))
    implementation(project(":services:badges"))
    implementation(project(":core:base"))
    implementation(project(":services:config")) // Fork addition (Phase 2)
    implementation(project(":data:contacts"))
    implementation(project(":core:crashreporter"))
    implementation(project(":data:customattrs"))
    implementation(project(":data:searchable"))
    implementation(project(":data:themes"))
    implementation(project(":data:i18n"))
    implementation(project(":core:i18n"))
    implementation(project(":services:icons"))
    implementation(project(":core:ktx"))
    implementation(project(":data:notifications"))
    implementation(project(":core:permissions"))
    implementation(project(":core:profiles"))
    implementation(project(":core:preferences"))
    implementation(project(":services:search"))
    implementation(project(":services:tags"))
    implementation(project(":app:ui"))
    implementation(project(":data:widgets"))
    implementation(project(":data:database"))
    implementation(project(":data:search-actions"))
    implementation(project(":services:global-actions"))
    implementation(project(":services:widgets"))
    implementation(project(":services:favorites"))
    implementation(project(":services:feed"))

    // Uncomment this if you want annoying notifications in your debug builds
    //debugImplementation(libs.leakcanary)
}
