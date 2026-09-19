package de.mm20.launcher2.config

object ConfigValidator {
    const val MaxNameLength = 64
    const val MaxPackageNameLength = 256
    const val MaxFavorites = 64
    const val MaxWidgets = 16

    private val packageNameRegex =
        Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    /** Upload names: one path segment, no leading dot, no separators. */
    val imageNameRegex = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

    fun validate(config: LauncherConfig): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()

        config.icons?.pack?.let { pack ->
            validatePackageName(pack, "icons.pack", diagnostics)
        }

        config.appearance?.transparency?.let { transparency ->
            transparency.name?.let { name ->
                validateName(name, "appearance.transparency.name", diagnostics)
            }
            validateTransparency(transparency.background, "appearance.transparency.background", diagnostics)
            validateTransparency(transparency.surface, "appearance.transparency.surface", diagnostics)
            validateTransparency(transparency.elevatedSurface, "appearance.transparency.elevatedSurface", diagnostics)
        }

        config.appearance?.wallpaper?.image?.let { image ->
            if (!imageNameRegex.matches(image)) {
                diagnostics += Diagnostic(
                    Severity.Error,
                    "invalid-wallpaper-image",
                    "appearance.wallpaper.image",
                    "'$image' is not a valid upload name (letters, digits, '.', '_', '-'; no leading dot; max 64)",
                )
            }
        }

        config.home?.dock?.favorites?.let { favorites ->
            if (favorites.size > MaxFavorites) {
                diagnostics += Diagnostic(
                    Severity.Error,
                    "too-many-favorites",
                    "home.dock.favorites",
                    "Dock favorites list exceeds the maximum of $MaxFavorites entries",
                )
            }
            val seen = mutableSetOf<Favorite>()
            favorites.forEachIndexed { index, favorite ->
                val path = "home.dock.favorites[$index]"
                validatePackageName(favorite.packageName, "$path.packageName", diagnostics)
                if (!seen.add(favorite)) {
                    diagnostics += Diagnostic(
                        Severity.Error,
                        "duplicate-favorite",
                        path,
                        "Duplicate favorite '${favorite.packageName}' " +
                                "(${favorite.profile.name.lowercase()})",
                    )
                }
            }
        }

        config.home?.widgets?.widgets?.let { widgets ->
            if (widgets.size > MaxWidgets) {
                diagnostics += Diagnostic(
                    Severity.Error,
                    "too-many-widgets",
                    "home.widgets.widgets",
                    "Widget list exceeds the maximum of $MaxWidgets entries",
                )
            }
            val seen = mutableSetOf<BuiltinWidget>()
            widgets.forEachIndexed { index, widget ->
                if (!seen.add(widget)) {
                    diagnostics += Diagnostic(
                        Severity.Error,
                        "duplicate-widget",
                        "home.widgets.widgets[$index]",
                        "Duplicate widget type '${widget.name.lowercase()}'",
                    )
                }
            }
        }

        return diagnostics
    }

    private fun validateTransparency(
        value: Float?,
        path: String,
        out: MutableList<Diagnostic>,
    ) {
        if (value == null) return
        if (value.isNaN() || value < 0f || value > 1f) {
            out += Diagnostic(
                Severity.Error,
                "invalid-transparency",
                path,
                "Transparency value must be between 0.0 and 1.0, got $value",
            )
        }
    }

    private fun validateName(
        name: String,
        path: String,
        out: MutableList<Diagnostic>,
    ) {
        if (name.isBlank()) {
            out += Diagnostic(
                Severity.Error,
                "invalid-name",
                path,
                "Name must not be blank",
            )
        } else if (name.length > MaxNameLength) {
            out += Diagnostic(
                Severity.Error,
                "invalid-name",
                path,
                "Name exceeds the maximum length of $MaxNameLength characters",
            )
        }
    }

    private fun validatePackageName(
        packageName: String,
        path: String,
        out: MutableList<Diagnostic>,
    ) {
        if (packageName.length > MaxPackageNameLength ||
            !packageNameRegex.matches(packageName)
        ) {
            out += Diagnostic(
                Severity.Error,
                "invalid-package-name",
                path,
                "'$packageName' is not a syntactically valid package name",
            )
        }
    }
}
