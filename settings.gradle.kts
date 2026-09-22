pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":app:app")
include(":app:ui")

include(":core:base")
include(":core:crashreporter")
include(":core:config")
include(":core:grid")
include(":core:preferences")
include(":core:ktx")
include(":core:i18n")
include(":data:database")
include(":core:permissions")
include(":core:shared")

include(":data:appshortcuts")
include(":data:customattrs")
include(":data:applications")
include(":data:themes")
include(":data:contacts")
include(":data:widgets")
include(":data:notifications")
include(":data:search-actions")
include(":data:searchable")

include(":services:config")
include(":services:tags")
include(":services:search")
include(":services:badges")
include(":services:icons")

include(":libs:material-color-utilities")
include(":libs:address-formatter")
include(":services:global-actions")
include(":services:widgets")
include(":services:favorites")

include(":core:profiles")
include(":data:i18n")
include(":services:feed")
