package de.mm20.launcher2.config

object ConfigValidator {
    const val MaxGlassBlur = 64f
    const val MaxGlassRadius = 64f
    const val MaxPackageNameLength = 256
    const val MaxFavorites = 64
    const val MaxGridItems = 32
    const val MinGridColumns = 2
    const val MaxGridColumns = 8

    private val packageNameRegex =
        Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    /** Item ids (D5): what a hand-written file and a write-back both produce. */
    val gridItemIdRegex = Regex("^[a-z0-9][a-z0-9-]{0,31}$")

    private val classNameRegex = Regex("^\\.?[A-Za-z_][A-Za-z0-9_$]*(\\.[A-Za-z_][A-Za-z0-9_$]*)*$")

    /** Upload names: one path segment, no leading dot, no separators. */
    val imageNameRegex = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

    fun validate(config: LauncherConfig): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()

        config.icons?.pack?.let { pack ->
            validatePackageName(pack, "icons.pack", diagnostics)
        }

        config.appearance?.glass?.let { glass ->
            validateGlass(glass.blur, 0f, MaxGlassBlur, "appearance.glass.blur", "dp", diagnostics)
            validateGlass(glass.tint, 0f, 1f, "appearance.glass.tint", "", diagnostics)
            validateGlass(glass.radius, 0f, MaxGlassRadius, "appearance.glass.radius", "dp", diagnostics)
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

        config.home?.favorites?.let { favorites ->
            if (favorites.size > MaxFavorites) {
                diagnostics += Diagnostic(
                    Severity.Error,
                    "too-many-favorites",
                    "home.favorites",
                    "Favorites list exceeds the maximum of $MaxFavorites entries",
                )
            }
            val seen = mutableSetOf<Favorite>()
            favorites.forEachIndexed { index, favorite ->
                val path = "home.favorites[$index]"
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

        config.home?.grid?.let { grid -> validateGrid(grid, diagnostics) }

        return diagnostics
    }

    /** `home.grid` (D5): every violation is an Error at the item's path. */
    private fun validateGrid(grid: GridConfig, out: MutableList<Diagnostic>) {
        grid.columns?.let { columns ->
            if (columns !in MinGridColumns..MaxGridColumns) {
                out += Diagnostic(
                    Severity.Error,
                    "invalid-grid-columns",
                    "home.grid.columns",
                    "Grid columns must be between $MinGridColumns and $MaxGridColumns, got $columns",
                )
            }
        }
        grid.layouts?.forEach { (layoutKey, layout) ->
            val basePath = "home.grid.layouts.$layoutKey.items"
            if (layout.items.size > MaxGridItems) {
                out += Diagnostic(
                    Severity.Error,
                    "too-many-grid-items",
                    basePath,
                    "Layout '$layoutKey' exceeds the maximum of $MaxGridItems items",
                )
            }
            val seenIds = mutableSetOf<String>()
            layout.items.forEachIndexed { index, item ->
                val path = "$basePath[$index]"
                if (!gridItemIdRegex.matches(item.id)) {
                    out += Diagnostic(
                        Severity.Error,
                        "invalid-grid-item-id",
                        path,
                        "'${item.id}' is not a valid item id (lowercase letters, digits, '-'; " +
                                "no leading '-'; max 32)",
                    )
                } else if (!seenIds.add(item.id)) {
                    out += Diagnostic(
                        Severity.Error,
                        "duplicate-grid-item-id",
                        path,
                        "Duplicate item id '${item.id}' in layout '$layoutKey'",
                    )
                }
                if (!item.isFavorites && !isComponentName(item.widget)) {
                    out += Diagnostic(
                        Severity.Error,
                        "invalid-grid-widget",
                        path,
                        "'${item.widget}' is neither '${GridItemConfig.Favorites}' nor a " +
                                "provider component name (package/class)",
                    )
                }
                if ((item.x == null) != (item.y == null)) {
                    out += Diagnostic(
                        Severity.Warning,
                        "partial-grid-position",
                        path,
                        "a position is x and y together; the lone coordinate is ignored and " +
                                "the item is placed at the first free cells",
                    )
                }
                val badPosition = (item.x != null && item.x < 0) || (item.y != null && item.y < 0)
                val badSize = (item.w != null && item.w < 1) || (item.h != null && item.h < 1)
                if (badPosition || badSize) {
                    out += Diagnostic(
                        Severity.Error,
                        "invalid-grid-geometry",
                        path,
                        "x and y must be 0 or more, w and h at least 1, got " +
                                "x=${item.x} y=${item.y} w=${item.w} h=${item.h}",
                    )
                }
            }
        }
    }

    /** `pkg/cls`: a package name and a non-empty class part (a leading '.' is the relative form). */
    private fun isComponentName(widget: String): Boolean {
        val slash = widget.indexOf('/')
        if (slash <= 0 || slash == widget.lastIndex) return false
        val packageName = widget.substring(0, slash)
        val className = widget.substring(slash + 1)
        return packageNameRegex.matches(packageName) &&
                packageName.length <= MaxPackageNameLength &&
                classNameRegex.matches(className)
    }

    /**
     * A glass number in [min]..[max]. Bounded above as well: the file is
     * untrusted input, and blur costs GPU time on every surface.
     */
    private fun validateGlass(
        value: Float?,
        min: Float,
        max: Float,
        path: String,
        unit: String,
        out: MutableList<Diagnostic>,
    ) {
        if (value == null) return
        if (value.isNaN() || value < min || value > max) {
            val field = path.substringAfterLast('.')
            out += Diagnostic(
                Severity.Error,
                "invalid-glass",
                path,
                "Glass $field must be between ${min.plain()} and ${max.plain()}$unit, got $value",
            )
        }
    }

    private fun Float.plain(): String = if (this % 1f == 0f) toInt().toString() else toString()

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
