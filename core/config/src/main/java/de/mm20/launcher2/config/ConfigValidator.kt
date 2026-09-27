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

    const val MaxTransliteratorIdLength = 64
    /**
     * One ICU transliterator id, or the words auto and off, which it also
     * matches: Source-Target/Variant, every part present - `/` and `Latin/`
     * are refused here rather than reported later as missing from the device
     * (#190 review). The lookahead keeps the length in the one pattern, which
     * the schema publishes.
     */
    val transliteratorIdRegex =
        Regex("^(?=.{1,$MaxTransliteratorIdLength}$)[A-Za-z0-9_]+(?:-[A-Za-z0-9_]+)*(?:/[A-Za-z0-9_]+)?$")
    const val MaxGlassTint = 1f
    const val MaxGlassRadius = 64f
    const val MaxPackageNameLength = 256
    const val MaxFavorites = 64
    /** `apps` (#3 slice 4): past any phone's app count, well short of a denial of service. */
    const val MaxApps = 512
    const val MaxLabelLength = 100
    /**
     * What a label may contain, as the schema publishes it: something that
     * is not white space, and no control characters or line breaks. The
     * validator checks the same in code (its length in characters, not
     * UTF-16 units); this is the schema's form of it.
     */
    const val labelCharsPattern = "^(?=.*\\S)[^\\u0000-\\u001F\\u007F-\\u009F\\u2028\\u2029]*$"
    /**
     * A launcher activity's class name, fully qualified as the system names a
     * launcher entry: dot-separated Java identifiers. Unlike [classNameRegex]
     * there is no relative `.Main` form, which no launcher entry could match.
     */
    internal val activityNameRegex = Regex("^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*$")
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
        config.search?.frequentlyUsedRows?.let { rows ->
            if (rows !in SearchDefaults.MinFrequentlyUsedRows..SearchDefaults.MaxFrequentlyUsedRows) {
                diagnostics += Diagnostic(
                    DiagnosticCode.InvalidSearch,
                    "search.frequentlyUsedRows",
                    "Frequently used rows must be between ${SearchDefaults.MinFrequentlyUsedRows} and " +
                        "${SearchDefaults.MaxFrequentlyUsedRows}, got $rows",
                )
            }
        }
        // One ICU id: the launcher appends its own base transliterator, so a
        // compound id (with ';') is refused. Whether this device's ICU has it
        // is reported by the device, not decided here (#3 slice 1).
        config.search?.transliterator?.let { id ->
            if (!transliteratorIdRegex.matches(id)) {
                diagnostics += Diagnostic(
                    DiagnosticCode.InvalidSearch,
                    "search.transliterator",
                    "Transliterator must be \"${SearchDefaults.TransliteratorAuto}\", " +
                        "\"${SearchDefaults.TransliteratorOff}\" or one ICU transliterator id " +
                        "(Source-Target/Variant of letters, digits and '_'; up to $MaxTransliteratorIdLength), got \"$id\"",
                )
            }
        }
        // Only the steps the settings screen offers: a write-back produces
        // nothing else, and the file is untrusted input (#3 slice 1).
        config.icons?.size?.let { size ->
            if (size !in IconDefaults.Sizes) {
                diagnostics += Diagnostic(
                    DiagnosticCode.InvalidIcons,
                    "icons.size",
                    "Icon size must be one of ${IconDefaults.Sizes.joinToString()} dp, got $size",
                )
            }
        }

        // #107: reversed results put the best match at the bottom, the
        // farthest from a bar at the top. Applied anyway; the config decides.
        config.search?.let { search ->
            if (search.barPosition == InSearchBarPosition.Top && search.reversed == true) {
                diagnostics += Diagnostic(
                    DiagnosticCode.SearchReversedWithTopBar,
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
                    DiagnosticCode.InvalidWallpaperImage,
                    "appearance.wallpaper.image",
                    "'$image' is not a valid upload name (letters, digits, '.', '_', '-'; no leading dot; max 64)",
                )
            }
        }

        config.home?.favorites?.let { favorites ->
            if (favorites.size > MaxFavorites) {
                diagnostics += Diagnostic(
                    DiagnosticCode.TooManyFavorites,
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
                        DiagnosticCode.DuplicateFavorite,
                        path,
                        "Duplicate favorite '${favorite.packageName}' " +
                                "(${favorite.profile.name.lowercase()})",
                    )
                }
            }
        }

        config.home?.grid?.let { grid -> validateGrid(grid, diagnostics) }

        config.apps?.let { apps -> validateApps(apps, diagnostics) }

        // #3 slice 2: an app a gesture launches is named like a favorite.
        config.gestures?.byGesture()?.forEach { (gesture, value) ->
            if (value is GestureConfig.App) {
                validatePackageName(value.app.packageName, "${gesture.path}.packageName", diagnostics)
            }
        }

        return diagnostics
    }

    /**
     * `apps` (#3 slice 4). The file is untrusted input and a label ends up on
     * the screen, so a label is a name: one to [MaxLabelLength] characters,
     * not blank, and no control characters or line breaks.
     */
    private fun validateApps(apps: List<AppConfig>, out: MutableList<Diagnostic>) {
        if (apps.size > MaxApps) {
            out += Diagnostic(DiagnosticCode.InvalidApps, "apps", "The apps list exceeds the maximum of $MaxApps entries")
        }
        val seen = mutableSetOf<Triple<String, Profile, String?>>()
        apps.forEachIndexed { index, app ->
            val path = "apps[$index]"
            validatePackageName(app.packageName, "$path.packageName", out)
            app.activity?.let { activity ->
                if (activity.length > MaxPackageNameLength || !activityNameRegex.matches(activity)) {
                    out += Diagnostic(
                        DiagnosticCode.InvalidApps, "$path.activity",
                        "'$activity' is not a valid activity class name",
                    )
                }
            }
            app.label?.let { label ->
                val length = label.codePointCount(0, label.length)
                val badChar = label.codePoints().anyMatch {
                    Character.isISOControl(it) ||
                        Character.getType(it) == Character.LINE_SEPARATOR.toInt() ||
                        Character.getType(it) == Character.PARAGRAPH_SEPARATOR.toInt()
                }
                if (label.isBlank() || length > MaxLabelLength || badChar) {
                    out += Diagnostic(
                        DiagnosticCode.InvalidApps, "$path.label",
                        "A label is 1 to $MaxLabelLength characters, not blank, without control characters or line breaks",
                    )
                }
            }
            if (!seen.add(Triple(app.packageName, app.profile, app.activity))) {
                out += Diagnostic(
                    DiagnosticCode.DuplicateApp, path,
                    "'${app.packageName}' (${app.profile.name.lowercase()}) is listed twice",
                )
            }
        }
    }

    /** `home.grid` (D5): every violation is an Error at the item's path. */
    /**
     * `search.actions` (#106): a known type, the fields it needs, valid
     * package names, no duplicates, and at most [MaxSearchActions].
     */
    private fun validateSearchActions(actions: List<SearchActionConfig>, out: MutableList<Diagnostic>) {
        if (actions.size > MaxSearchActions) {
            out += Diagnostic(
                DiagnosticCode.TooManySearchActions,
                "search.actions",
                "at most $MaxSearchActions search actions, got ${actions.size}",
            )
        }
        val seen = mutableSetOf<String>()
        actions.forEachIndexed { index, action ->
            val path = "search.actions[$index]"
            fun invalid(message: String) {
                out += Diagnostic(DiagnosticCode.InvalidSearchAction, path, message)
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
                            DiagnosticCode.SearchActionFieldIgnored,
                            path,
                            "an app action takes a label and a package; its url and encoding are ignored",
                        )
                    }
                }
                SearchActionTypes.Intent -> out += Diagnostic(
                    DiagnosticCode.SearchActionReadOnly,
                    path,
                    "an intent action is made on the device; the file keeps it where it is but cannot create or change it",
                )
                in SearchActionTypes.BuiltIn -> {
                    if (action.label != null || action.url != null || action.packageName != null || action.encoding != null) {
                        out += Diagnostic(
                            DiagnosticCode.SearchActionFieldIgnored,
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
                out += Diagnostic(DiagnosticCode.DuplicateSearchAction, path, "the same action is listed twice")
            }
        }
    }

    private fun validateGrid(grid: GridConfig, out: MutableList<Diagnostic>) {
        grid.columns?.let { columns ->
            if (columns !in MinGridColumns..MaxGridColumns) {
                out += Diagnostic(
                    DiagnosticCode.InvalidGridColumns,
                    "home.grid.columns",
                    "Grid columns must be between $MinGridColumns and $MaxGridColumns, got $columns",
                )
            }
        }
        grid.layouts?.forEach { (layoutKey, layout) ->
            val basePath = "home.grid.layouts.$layoutKey.items"
            if (layout.items.size > MaxGridItems) {
                out += Diagnostic(
                    DiagnosticCode.TooManyGridItems,
                    basePath,
                    "Layout '$layoutKey' exceeds the maximum of $MaxGridItems items",
                )
            }
            val seenIds = mutableSetOf<String>()
            layout.items.forEachIndexed { index, item ->
                val path = "$basePath[$index]"
                if (!gridItemIdRegex.matches(item.id)) {
                    out += Diagnostic(
                        DiagnosticCode.InvalidGridItemId,
                        path,
                        "'${item.id}' is not a valid item id (lowercase letters, digits, '-'; " +
                                "no leading '-'; max 32)",
                    )
                } else if (!seenIds.add(item.id)) {
                    out += Diagnostic(
                        DiagnosticCode.DuplicateGridItemId,
                        path,
                        "Duplicate item id '${item.id}' in layout '$layoutKey'",
                    )
                }
                if (!item.isFavorites && !isComponentName(item.widget)) {
                    out += Diagnostic(
                        DiagnosticCode.InvalidGridWidget,
                        path,
                        "'${item.widget}' is neither '${GridItemConfig.Favorites}' nor a " +
                                "provider component name (package/class)",
                    )
                }
                if ((item.x == null) != (item.y == null)) {
                    out += Diagnostic(
                        DiagnosticCode.PartialGridPosition,
                        path,
                        "a position is x and y together; the lone coordinate is ignored and " +
                                "the item is placed at the first free cells",
                    )
                }
                val badPosition = listOf(item.x, item.y).any { it != null && it !in MinGridPosition..MaxGridCoordinate }
                val badSize = listOf(item.w, item.h).any { it != null && it !in MinGridSpan..MaxGridCoordinate }
                if (badPosition || badSize) {
                    out += Diagnostic(
                        DiagnosticCode.InvalidGridGeometry,
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
                DiagnosticCode.InvalidGlass,
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
                DiagnosticCode.InvalidPackageName,
                path,
                "'$packageName' is not a syntactically valid package name",
            )
        }
    }
}
