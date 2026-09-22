plugins {
    alias(libs.plugins.kotlin.plugin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.dokka) apply true
    alias(libs.plugins.kover)
}

dependencies {
    dokka(project(":core:shared"))

    // Modules the fork owns and measures (ADR 0005, AGENTS.md "Test policy").
    // Listing a module here merges it into the root report
    // (`./gradlew koverHtmlReport`); the threshold that fails CI lives in the
    // module itself, next to the tests that have to reach it.
    kover(project(":core:config"))
    kover(project(":services:config"))
}