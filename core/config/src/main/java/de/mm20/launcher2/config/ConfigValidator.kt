package de.mm20.launcher2.config

/**
 * The contract's limits live here as named constants and patterns, never as
 * literals in a check, so that everything that states them - this validator,
 * the JSON Schema (#3 slice 3) - reads the same value.
 *
 * Which path gets which limit is declared twice on purpose: here, as checks
 * with their diagnostic codes, and in ConfigSchema.constraints, as schema
 * keywords. ConfigSchemaTest breaks every schema limit and fails when this
 * validator does not object, so the two cannot disagree unnoticed. Driving
 * both from one table would rewrite the checks and put the diagnostic codes
 * at risk (invalid-grid-geometry merges four fields); decided against for #3
 * slice 3. Revisit if write-back (slice 4) or a later section adds many limits.
 */
object ConfigValidator {
    const val MinGlass = 0f
    const val MaxGlassBlur = 64f
    const val MaxGlassTint = 1f
    const val MaxGlassRadius = 64f
    const val MaxPackageNameLength = 256
    const val MaxFavorites = 64
    const val MaxGridItems = 32
    const val MaxSearchActions = 32
    const val MinGridColumns = 2
    const val MaxGridColumns = 8
    /** Upper bound for x, y, w and h: past any real grid, and far from Int overflow. */
    const val MaxGridCoordinate = 64
    /** Lower bound for x and y: the first cell. */
    const val MinGridPosition = 0
    /** Lower bound for w and h: one cell. */
    const val MinGridSpan = 1

    internal val packageNameRegex =
        Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    /** Item ids (D5): what a hand-written file and a write-back both produce. */
    internal val gridItemIdRegex = Regex("^[a-z0-9][a-z0-9-]{0,31}$")

    internal val classNameRegex = Regex("^\\.?[A-Za-z_][A-Za-z0-9_$]*(\\.[A-Za-z_][A-Za-z0-9_$]*)*$")

    /**
     * `pkg/cls`: a package name of at most [MaxPackageNameLength] and a class
     * part (a leading '.' is the relative form). Neither part can hold a '/'.
     */
    internal val componentNameRegex = Regex(
        "^(?=[^/]{1,$MaxPackageNameLength}/)" +
            packageNameRegex.pattern.removePrefix("^").removeSuffix("$") + "/" +
            classNameRegex.pattern.removePrefix("^").removeSuffix("$") + "$"
    )

    /** Upload names: one path segment, no leading dot, no separators. */
    val imageNameRegex = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

    fun validate(config: LauncherConfig): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()

        // "none" is the apps' own icons, chosen (#3 D6); anything else names a pack.
        config.icons?.pack?.takeIf { it != IconsConfig.NoPack }?.let { pack ->
            validatePackageName(pack, "icons.pack", diagnostics)
        }

        // #107: reversed results put the best match at the bottom, the
        // farthest from a bar at the top. Applied anyway; the config decides.
        config.search?.let { search ->
            if (search.barPosition == InSearchBarPosition.Top && search.reversed == true) {
                diagnostics += Diagnostic(
                    Severity.Warning,
                    "search-reversed-with-top-bar",
                    "search.reversed",
                    "reversed results with search.barPosition top put the best match the farthest from the " +
                            "search bar; applied as written",
                )
            }
        }

        config.search?.actions?.let { validateSearchActions(it, diagnostics) }

        config.appearance?.glass?.let { glass ->
            validateGlass(glass.blur, MinGlass, MaxGlassBlur, "appearance.glass.blur", "dp", diagnostics)
            validateGlass(glass.tint, MinGlass, MaxGlassTint, "appearance.glass.tint", "", diagnostics)
            validateGlass(glass.radius, MinGlass, MaxGlassRadius, "appearance.glass.radius", "dp", diagnostics)
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
    /**
     * `search.actions` (#106): a known type, the fields it needs, valid
     * package names, no duplicates, and at most [MaxSearchActions].
     */
    private fun validateSearchActions(actions: List<SearchActionConfig>, out: MutableList<Diagnostic>) {
        if (actions.size > MaxSearchActions) {
            out += Diagnostic(
                Severity.Error,
                "too-many-search-actions",
                "search.actions",
                "at most $MaxSearchActions search actions, got ${actions.size}",
            )
        }
        val seen = mutableSetOf<String>()
        actions.forEachIndexed { index, action ->
            val path = "search.actions[$index]"
            fun invalid(message: String) {
                out += Diagnostic(Severity.Error, "invalid-search-action", path, message)
            }
            action.packageName?.let { validatePackageName(it, "$path.package", out) }
            when (action.type) {
                SearchActionTypes.Url -> {
                    if (action.label.isNullOrBlank()) invalid("a url action needs a label")
                    val url = action.url
                    if (url.isNullOrBlank()) invalid("a url action needs a url")
                    else if (SearchActionTypes.QueryPlaceholder !in url) {
                        invalid("the url needs ${SearchActionTypes.QueryPlaceholder} where the query goes")
                    }
                    val encoding = action.encoding
                    if (encoding != null && encoding !in SearchActionTypes.Encodings) {
                        invalid("'$encoding' is not an encoding (${SearchActionTypes.Encodings.joinToString(", ")})")
                    }
                }
                SearchActionTypes.App -> {
                    if (action.label.isNullOrBlank()) invalid("an app action needs a label")
                    if (action.packageName.isNullOrBlank()) invalid("an app action needs the package to search in")
                    if (action.url != null || action.encoding != null) {
                        out += Diagnostic(
                            Severity.Warning,
                            "search-action-field-ignored",
                            path,
                            "an app action takes a label and a package; its url and encoding are ignored",
                        )
                    }
                }
                SearchActionTypes.Intent -> out += Diagnostic(
                    Severity.Warning,
                    "search-action-read-only",
                    path,
                    "an intent action is made on the device; the file keeps it where it is but cannot create or change it",
                )
                in SearchActionTypes.BuiltIn -> {
                    if (action.label != null || action.url != null || action.packageName != null || action.encoding != null) {
                        out += Diagnostic(
                            Severity.Warning,
                            "search-action-field-ignored",
                            path,
                            "'${action.type}' is a built-in action; its label, url, package and encoding are ignored",
                        )
                    }
                }
                else -> invalid(
                    "'${action.type}' is not a search action (${SearchActionTypes.Configurable.sorted().joinToString(", ")})"
                )
            }
            // Intent actions are the device's own, told apart only by label and
            // possibly alike: never a duplicate (review on #116).
            val key = listOf(action.type, action.url.orEmpty(), action.packageName.orEmpty()).joinToString("|")
            if (action.type != SearchActionTypes.Intent && !seen.add(key)) {
                out += Diagnostic(Severity.Error, "duplicate-search-action", path, "the same action is listed twice")
            }
        }
    }

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
                val badPosition = listOf(item.x, item.y).any { it != null && it !in MinGridPosition..MaxGridCoordinate }
                val badSize = listOf(item.w, item.h).any { it != null && it !in MinGridSpan..MaxGridCoordinate }
                if (badPosition || badSize) {
                    out += Diagnostic(
                        Severity.Error,
                        "invalid-grid-geometry",
                        path,
                        "x and y must be 0 to $MaxGridCoordinate, w and h 1 to $MaxGridCoordinate, got " +
                                "x=${item.x} y=${item.y} w=${item.w} h=${item.h}",
                    )
                }
            }
        }
    }

    private fun isComponentName(widget: String): Boolean = componentNameRegex.matches(widget)

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
