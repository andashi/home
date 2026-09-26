package de.mm20.launcher2.preferences

import android.content.Context
import de.mm20.launcher2.config.GlassContrast
import de.mm20.launcher2.config.GlassDefaults
import de.mm20.launcher2.search.SearchFilters
import de.mm20.launcher2.serialization.UUIDSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
@ConsistentCopyVisibility
data class LauncherSettingsData internal constructor(
    // No migration keys on it any more: Migration4 and Migration5, the ones
    // that did, were deleted (#3), and Migration6 is keyed on content.
    val schemaVersion: Int = 6,

    val uiColorScheme: ColorScheme = ColorScheme.System,
    @Serializable(with = UUIDSerializer::class)
    val uiColorsId: UUID = BuiltInColorSchemes.System,
    @Serializable(with = UUIDSerializer::class)
    val uiShapesId: UUID = UUID(0L, 0L),
    /** `appearance.glass` (ADR 0004, #24); written only by a config reload. */
    val glassBlur: Float = GlassDefaults.Blur,
    val glassTint: Float = GlassDefaults.Tint,
    val glassRadius: Float = GlassDefaults.Radius,
    val glassContrast: GlassContrast = GlassDefaults.Contrast,
    val glassWallpaperBlur: Boolean = GlassDefaults.WallpaperBlur,
    val glassSearchWallpaperBlur: Boolean = GlassDefaults.SearchWallpaperBlur,
    @Serializable(with = UUIDSerializer::class)
    val uiTypographyId: UUID = UUID(0L, 0L),

    val uiCompatModeColors: Boolean = false,
    val uiOrientation: ScreenOrientation = ScreenOrientation.Auto,

    val wallpaperDim: Boolean = false,

    val homeScreenWidgets: Boolean = false,
    /** `home.grid.columns`: columns of one cover-width page (ADR 0001, D1). */
    val homeGridColumns: Int = 4,
    /** `home.grid.locked`: no edit mode, nothing written back (D3). */
    val homeGridLocked: Boolean = false,
    /** `home.grid.labels`: labels under grid items (ADR 0004, #24). */
    val homeGridLabels: Boolean = GlassDefaults.Labels,
    /** The one-time conversion of the widget column into grid items ran (ADR 0001 migration). */
    val homeGridInitialized: Boolean = false,

    val favoritesEnabled: Boolean = true,
    val favoritesFrequentlyUsed: Boolean = true,
    val favoritesFrequentlyUsedRows: Int = 1,
    val favoritesEditButton: Boolean = true,
    val favoritesCompactTags: Boolean = false,

    val searchAllApps: Boolean = true,
    val appsShowDetails: Boolean = true,


    val contactSearchProviders: Set<String> = setOf("local"),
    val contactSearchCallOnTap: Boolean = false,


    val shortcutSearchEnabled: Boolean = true,
    val shortcutSearchBlocklist: Set<String> = setOf(),





    val badgesNotifications: Boolean = true,
    val badgesSuspendedApps: Boolean = true,
    val badgesShortcuts: Boolean = true,

    val gridColumnCount: Int = 5,
    val gridIconSize: Int = 48,
    val gridLabels: Boolean = true,
    val gridList: Boolean = false,
    val gridListIcons: Boolean = true,

    val searchBarStyle: SearchBarStyle = SearchBarStyle.Transparent,
    val searchBarColors: SearchBarColors = SearchBarColors.Auto,
    val searchBarKeyboard: Boolean = true,
    val searchLaunchOnEnter: Boolean = true,
    val searchBarBottom: Boolean = false,
    /** The search bar while search is open (#107): null follows [searchBarBottom]. */
    val searchBarBottomInSearch: Boolean? = null,
    val searchBarFixed: Boolean = false,

    val searchResultsReversed: Boolean = false,

    val rankingWeightFactor: WeightFactor = WeightFactor.Default,

    val hiddenItemsShowButton: Boolean = false,

    val iconsShape: IconShape = IconShape.PlatformDefault,
    val iconsAdaptify: Boolean = false,
    /** On in the fork: the Clear look (#76) is built from themed layers (#86). */
    val iconsThemed: Boolean = true,
    val iconsForceThemed: Boolean = false,
    val iconsPack: String? = null,
    val systemBarsHideStatus: Boolean = false,
    val systemBarsHideNav: Boolean = false,
    val systemBarsStatusColors: SystemBarColors = SystemBarColors.Auto,
    val systemBarsNavColors: SystemBarColors = SystemBarColors.Auto,

    val gesturesSwipeDown: GestureAction = GestureAction.Search,
    val gesturesSwipeLeft: GestureAction = GestureAction.NoAction,
    val gesturesSwipeRight: GestureAction = GestureAction.NoAction,
    val gesturesSwipeUp: GestureAction = GestureAction.Search,
    val gesturesDoubleTap: GestureAction = GestureAction.ScreenLock,
    val gesturesLongPress: GestureAction = GestureAction.NoAction,
    val gesturesHomeButton: GestureAction = GestureAction.NoAction,

    val stateTagsMultiline: Boolean = false,



    val searchFilter: SearchFilters = SearchFilters(),
    val searchFilterBar: Boolean = true,
    @Serializable(with = KeyboardFilterBarItemListSerializer::class)
    val searchFilterBarItems: List<KeyboardFilterBarItem> = listOf(
        KeyboardFilterBarItem.Apps,
        KeyboardFilterBarItem.Shortcuts,
        KeyboardFilterBarItem.Contacts,
        KeyboardFilterBarItem.HiddenResults,
    ),


    /**
     * The ID of the transliterator to use. The empty string means to pick a transliterator
     * automatically. null disables the transliterator.
     */
    val localeTransliterator: String? = "",


    val feedProviderPackage: String? = null


    ) {
    constructor(
        context: Context,
    ) : this(
        gridColumnCount = context.resources.getInteger(R.integer.config_columnCount),
    )
}

@Serializable
enum class ColorScheme {
    Light,
    Dark,
    System,
}

/**
 * The ids of the built-in colour schemes, the one place they are defined:
 * data/themes names its built-in colour schemes by these, and
 * `appearance.theme.colors` maps its slugs onto them (#3 slice 3). Any other
 * id is a scheme a person made. [System] has the value of data/themes'
 * DefaultThemeId, which is also the default of every other theme part
 * (shapes, typography) and so stays defined there.
 */
object BuiltInColorSchemes {
    /** Follows the system (Monet) palette; also the default of every theme part. */
    val System: UUID = UUID(0L, 0L)
    val BlackAndWhite: UUID = UUID(0L, 1L)
    val HighContrast: UUID = UUID(0L, 2L)
}

@Serializable
enum class SearchBarStyle {
    Transparent,
    Solid,
    Hidden,
}

@Serializable
enum class SearchBarColors {
    Auto,
    Light,
    Dark,
}

@Serializable
enum class IconShape {
    PlatformDefault,
    Circle,
    Square,
    RoundedSquare,
    Triangle,
    Squircle,
    Hexagon,
    Pentagon,
    Teardrop,
    Pebble,
    EasterEgg,
}

@Serializable
enum class SystemBarColors {
    Auto,
    Light,
    Dark,
}

@Serializable
enum class SurfaceShape {
    Rounded,
    Cut,
}

@Serializable
enum class ScreenOrientation {
    Auto,
    Portrait,
    Landscape,
}

@Serializable
sealed interface GestureAction {
    @Serializable
    @SerialName("no_action")
    data object NoAction : GestureAction

    @Serializable
    @SerialName("notifications")
    data object Notifications : GestureAction

    @Serializable
    @SerialName("quick_settings")
    data object QuickSettings : GestureAction

    @Serializable
    @SerialName("screen_lock")
    data object ScreenLock : GestureAction

    @Serializable
    @SerialName("search")
    data object Search : GestureAction

    /**
     * The widget pages reached by gestures are gone (PR 5b). The value stays
     * decodable so a settings file written before the removal does not trip
     * the corruption handler and reset every setting; Migration6 rewrites it
     * to [Search] on the next start, and nothing offers or acts on it.
     */
    @Serializable
    @SerialName("widgets")
    @Deprecated("The widget pages are gone; Migration6 maps this to Search")
    data object Widgets : GestureAction

    @Serializable
    @SerialName("power_menu")
    data object PowerMenu : GestureAction

    @Serializable
    @SerialName("recents")
    data object Recents : GestureAction

    @Serializable
    @SerialName("launch_searchable")
    data class Launch(val key: String?) : GestureAction

    @Serializable
    @SerialName("feed")
    data object Feed : GestureAction

    @Serializable
    @SerialName("launcher_settings")
    data object LauncherSettings : GestureAction
}


@Serializable
enum class WeightFactor {
    Default,
    Low,
    High,
}



@Serializable
enum class KeyboardFilterBarItem {
    @SerialName("apps") Apps,
    @SerialName("shortcuts") Shortcuts,
    @SerialName("contacts") Contacts,
    @SerialName("hidden") HiddenResults,
}




