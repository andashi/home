---
sidebar_position: 3
---

# Modules

The project consists of multiple Gradle modules. This is the current structure:

- `:app`:
    - `:app`: The app module. Contains almost nothing except the `Application` class (
      `de.mm20.launcher2.LauncherApplication`)
    - `:ui`: Contains almost the entire user interface. The only module that uses Jetpack Compose.
- `:services`: Higher level APIs for the app's business logic. Each module represents a specific
  functionality of the launcher
    - `:badges`: Provide different types of badges that are displayed on app icons
    - `:config`: Applies the declarative launcher configuration (see the config contract)
    - `:favorites`: Handles pinned items and item visibility
    - `:feed`: The home screen feed
    - `:global-actions`: Handles global system actions like turning the screen off, and opening the
      notification drawer
    - `:icons`: Used to retrieve icons for items. Handles icon packs, themed icons and also custom
      icons (on a higher level)
    - `:music`: Manage media sessions and extract metadata
    - `:search`: Search service
    - `:tags`: Edit, copy and delete tags
    - `:widgets`: High-level APIs to manage widgets
- `:data`: Lower level APIs. Usually, these modules implement interfaces from the `:core:base`
  module, so that `:services` don't need to depend on `:data` modules directly.
    - `:applications`: Implements APIs for app grid and app search
    - `:appshortcuts`: Implements app shortcuts (search and shortcuts)
    - `:contacts`: Implements contact search
    - `:customattrs`: common (low-level) APIs to store per-app customizations (custom labels, custom
      icons, tags)
    - `:database`: The launcher database, uses AndroidX Room
    - `:i18n`: Localization helpers for data types
    - `:notifications`: APIs to read notifications. Contains the app's `NotificationListenerService`
    - `:search-actions`: Implements search actions (call, message, web search, ...)
    - `:searchable`: Persistence for searchable items
    - `:themes`: Colors, shapes, transparencies and typography
    - `:widgets`: CRUD operations to store and retrieve widgets in/from the database
- `:core`
    - `:base`: Interface definitions for the most commonly used data types. Commonly used data
      classes, helper functions and utilities.
    - `:compat`: Compatibility helpers for old Android versions
    - `:config`: Parses and diffs the declarative launcher configuration
    - `:crashreporter`: Logs caught exceptions to logcat
    - `:i18n`: All resources that require localization. Mainly strings but can also be used for icon
      resources if they need localization.
    - `:ktx`: Commonly used Kotlin extension functions
    - `:permissions`: Request and observe permission status for this app
    - `:preferences`: Store user preferences; uses AndroidX Datastore
    - `:profiles`: Manage user profiles on the device
    - `:shared`: Serializers and data types shared across modules
- `:libs`: Somewhat standalone modules and 3rd party libraries that do not depend on `:core:base`
    - `:address-formatter`: Fork of https://github.com/woheller69/AndroidAddressFormatter (because
      the upstream library is only available as a `-SNAPSHOT` version)
    - `:material-color-utilities`: This
      library: https://github.com/material-foundation/material-color-utilities (not available as
      Gradle package yet)

Most of the modules have a `Module.kt` file in their root which contains Koin definitions to make
the APIs accessible to other modules.
