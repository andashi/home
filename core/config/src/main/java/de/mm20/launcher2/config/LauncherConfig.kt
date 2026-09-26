package de.mm20.launcher2.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class LauncherConfig(
    val schemaVersion: Int,
    val icons: IconsConfig? = null,
    val appearance: AppearanceConfig? = null,
    val home: HomeConfig? = null,
    val search: SearchConfig? = null,
)

@Serializable
data class IconsConfig(
    val themed: Boolean? = null,
    val enforceThemed: Boolean? = null,
    /** A pack's package name, or [NoPack]; absent, the launcher's default (Lawnicons if installed). */
    val pack: String? = null,
    /**
     * Icon size in dp (#3 slice 1): search results, the dock and the pickers.
     * One of [IconDefaults.Sizes], the steps the settings screen offers.
     */
    val size: Int? = null,
    /** Legacy (non-adaptive) icons are fitted into an adaptive shape. */
    val adaptify: Boolean? = null,
    val badges: IconBadgesConfig? = null,
) {
    companion object {
        /** The apps' own icons, chosen: no pack and no fallback (#3 D6). */
        const val NoPack = "none"
    }
}

/** `icons.badges` (#3 slice 1): the dots and marks drawn on icons. */
@Serializable
data class IconBadgesConfig(
    /** A dot for an app with notifications. */
    val notifications: Boolean? = null,
    /** The app's badge on a shortcut's icon. */
    val shortcuts: Boolean? = null,
    /** A mark on an app that is paused (suspended). */
    val suspendedApps: Boolean? = null,
)

/** The one place the icon defaults live: today's behavior, so a file without them changes nothing. */
object IconDefaults {
    const val Size = 48
    const val MinSize = 32
    const val MaxSize = 64
    const val SizeStep = 8
    /** The sizes the settings screen offers, and so all a write-back produces. */
    val Sizes: List<Int> = (MinSize..MaxSize step SizeStep).toList()
    const val Adaptify = false
    const val Badges = true
}

@Serializable
data class AppearanceConfig(
    val glass: GlassConfig? = null,
    val wallpaper: WallpaperConfig? = null,
)

/**
 * The glass surfaces of the home screen (ADR 0004, #24): cards, dock and
 * search pill over the blurred wallpaper. Every field is optional; an absent
 * one is unmanaged, and the state starts from [GlassDefaults].
 *
 * [blur] and [radius] are dp, [tint] is the alpha of the zone's Monet surface
 * color over the backdrop. [contrast] scales blur and tint rather than
 * switching to another look. Numbers decode as floats so a generator that
 * writes `24.0` does not fail the zone's whole document.
 */
@Serializable
data class GlassConfig(
    val blur: Float? = null,
    val tint: Float? = null,
    val radius: Float? = null,
    val contrast: GlassContrast? = null,
    val wallpaperBlur: Boolean? = null,
    val searchWallpaperBlur: Boolean? = null,
)

@Serializable(with = GlassContrastSerializer::class)
enum class GlassContrast {
    @SerialName("low")
    Low,

    @SerialName("medium")
    Medium,

    @SerialName("high")
    High,
}

/**
 * Decodes an enum by its lowercase name with an error that names the field.
 * The generated enum serializer reports only the enum's class name, and
 * decoding runs over a JSON tree that carries no path, so a host would read
 * "GlassContrast does not contain element with name 'extreme'" and have to
 * guess where it was. Each such enum appears in exactly one place in the
 * contract, so the path is known here.
 */
internal abstract class FieldEnumSerializer<E : Enum<E>>(
    serialName: String,
    private val path: String,
    entries: List<E>,
) : KSerializer<E> {
    private val byName = entries.associateBy { it.name.lowercase() }

    /** The values the contract accepts, as written; the JSON Schema lists these. */
    val names: List<String> = byName.keys.toList()

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: E) {
        encoder.encodeString(value.name.lowercase())
    }

    override fun deserialize(decoder: Decoder): E {
        val name = decoder.decodeString()
        return byName[name] ?: throw SerializationException(
            "'$name' is not a valid value for $path (${names.joinToString(", ")})"
        )
    }
}

internal object GlassContrastSerializer : FieldEnumSerializer<GlassContrast>(
    "de.mm20.launcher2.config.GlassContrast", "appearance.glass.contrast", GlassContrast.entries,
)

/** The one place the glass defaults live; state, settings and read-back use it. */
object GlassDefaults {
    const val Blur = 24f
    /** Lowered from 0.35 when the frosted look became liquid (#82). */
    const val Tint = 0.12f
    const val Radius = 28f
    val Contrast = GlassContrast.Medium
    const val WallpaperBlur = true

    /**
     * `appearance.glass.searchWallpaperBlur` (#91): search is an overlay, so
     * the wallpaper behind it is blurred whatever [WallpaperBlur] says for
     * the home screen.
     */
    const val SearchWallpaperBlur = true

    /** `home.grid.labels`: labels under grid items, never on the dock. */
    const val Labels = true
}

/**
 * The wallpaper of this profile. [image] names a file previously uploaded
 * through the ingest provider (`content://<applicationId>.config-ingest/wallpapers/<image>`);
 * [target] defaults to [WallpaperTarget.Both].
 */
@Serializable
data class WallpaperConfig(
    val image: String? = null,
    val target: WallpaperTarget? = null,
)

@Serializable
enum class WallpaperTarget {
    @SerialName("home")
    Home,

    @SerialName("lock")
    Lock,

    @SerialName("both")
    Both,
}

@Serializable
data class HomeConfig(
    val searchBar: SearchBarConfig? = null,
    /**
     * The one pin list, shared by search and the favorites widget on the grid
     * (D2). Where the widget sits is a `"widget": "favorites"` item in
     * [grid]; whether it is on the grid at all is whether such an item exists.
     */
    val favorites: List<Favorite>? = null,
    val widgets: WidgetsConfig? = null,
    val grid: GridConfig? = null,
)

@Serializable
data class SearchBarConfig(
    val position: SearchBarPosition? = null,
)

@Serializable
enum class SearchBarPosition {
    @SerialName("top")
    Top,

    @SerialName("bottom")
    Bottom,
}

@Serializable(with = FavoriteSerializer::class)
data class Favorite(
    val packageName: String,
    val profile: Profile = Profile.Personal,
)

/**
 * Accepts a favorite written either as an object or as a bare package name.
 *
 *     "favorites": [{ "packageName": "com.example", "profile": "work" }]
 *     "favorites": ["com.example"]
 *
 * The short form means the personal profile, which is what a single string can
 * mean. It exists because it is what config generators naturally emit, and
 * because the alternative is worse than verbose: this parser does not set
 * `coerceInputValues`, so one bare string among the favorites would fail the
 * decode and take the zone's entire configuration with it - wallpaper, dock,
 * icons and all. A contract that loses everything over a shorthand is a trap,
 * not a specification.
 *
 * Writing always uses the object form, so a round trip normalises.
 */
internal object FavoriteSerializer : KSerializer<Favorite> {
    private val objectSerializer = FavoriteObject.serializer()

    @Serializable
    @SerialName("Favorite")
    private data class FavoriteObject(
        val packageName: String,
        val profile: Profile = Profile.Personal,
    )

    override val descriptor: SerialDescriptor = objectSerializer.descriptor

    override fun serialize(encoder: Encoder, value: Favorite) {
        objectSerializer.serialize(encoder, FavoriteObject(value.packageName, value.profile))
    }

    override fun deserialize(decoder: Decoder): Favorite {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return objectSerializer.deserialize(decoder).let { Favorite(it.packageName, it.profile) }
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> {
                if (!element.isString) {
                    throw SerializationException("A favorite must be a package name or an object")
                }
                Favorite(element.content)
            }

            else -> jsonDecoder.json.decodeFromJsonElement(objectSerializer, element)
                .let { Favorite(it.packageName, it.profile) }
        }
    }
}

@Serializable
enum class Profile {
    @SerialName("personal")
    Personal,

    @SerialName("work")
    Work,

    /** Private Space; treated as just another profile for config purposes (ADR 0006). */
    @SerialName("private")
    Private,
}

/** `enabled` is the master switch for the home grid (ADR 0001). */
@Serializable
data class WidgetsConfig(
    val enabled: Boolean? = null,
)

/**
 * The single-page home grid (ADR 0001, revised 2026-09-22).
 *
 * [columns] is the column count of one cover-width page; the `fold` layout
 * is twice as wide and the cover display renders its columns `columns until
 * 2 * columns`, the right half (D7, #93). Rows are derived from the screen, not configured (D1).
 * [locked] forbids edit mode, so nothing is ever written back for a locked
 * profile (D3). [layouts] is keyed by [GridLayouts.Phone] or
 * [GridLayouts.Fold]; a device uses exactly one of them.
 */
@Serializable
data class GridConfig(
    val columns: Int? = null,
    val locked: Boolean? = null,
    val layouts: Map<String, GridLayoutConfig>? = null,
    val labels: Boolean? = null,
)

object GridLayouts {
    const val Phone = "phone"
    const val Fold = "fold"
    val All: Set<String> = setOf(Phone, Fold)
}

@Serializable
data class GridLayoutConfig(
    val items: List<GridItemConfig>,
)

/**
 * One item of a layout. [id] is stable across devices and reloads and is
 * what write-back and the database match on, never the array position.
 * [widget] is [GridItemConfig.Favorites] or a flattened provider
 * `ComponentName` (`pkg/cls`). Geometry ([x], [y], [w], [h], in cells) may
 * be omitted once: the launcher places the item and writes the geometry back.
 */
@Serializable
data class GridItemConfig(
    val id: String,
    val widget: String,
    val x: Int? = null,
    val y: Int? = null,
    val w: Int? = null,
    val h: Int? = null,
    val profile: Profile? = null,
    val borderless: Boolean? = null,
    val background: Boolean? = null,
    val themeColors: Boolean? = null,
) {
    val isFavorites: Boolean get() = widget == Favorites
    val hasGeometry: Boolean get() = x != null && y != null && w != null && h != null

    /**
     * A position is `x` and `y` together. With one, the item anchors there
     * and a missing size comes from the provider; a lone coordinate is not
     * a position (the validator warns, the differ ignores it).
     */
    val hasPosition: Boolean get() = x != null && y != null

    companion object {
        const val Favorites = "favorites"

        /**
         * What an absent option means: the one place in the contract where
         * absent is a default, not unmanaged (ADR 0002). Apply stores these,
         * read-back serves them, and a write-back leaves them out again.
         */
        val OptionDefaults: Map<String, Boolean> = mapOf(
            "borderless" to false,
            "background" to true,
            "themeColors" to true,
        )
    }
}

/**
 * `search` (#91): how search behaves. Every key is optional; a key that is
 * left out stays unmanaged. The look of search is `appearance.glass`, its
 * columns are `home.grid.columns` - this section is behavior only.
 */
@Serializable
data class SearchConfig(
    /** The favorites row at the top of search. */
    val favorites: Boolean? = null,
    /** All apps while the query is empty. */
    val allApps: Boolean? = null,
    val layout: SearchResultLayout? = null,
    /** Labels under app icons in search. */
    val labels: Boolean? = null,
    /** Contacts in the results; without the permission a banner asks for it. */
    val contacts: Boolean? = null,
    /** App shortcuts in the results. */
    val shortcuts: Boolean? = null,
    /** The filter bar above the keyboard. */
    val filterBar: Boolean? = null,
    /** The keyboard opens with search. */
    val openKeyboard: Boolean? = null,
    /** Enter launches the best match. */
    val launchOnEnter: Boolean? = null,
    /** Results from the bottom up, the best match nearest a bottom search bar. */
    val reversed: Boolean? = null,
    /** A button in the search bar that shows hidden items. */
    val hiddenItemsButton: Boolean? = null,
    /**
     * Where the search bar sits while search is open (#107); `follow` (the
     * default) puts it where `home.searchBar.position` does (#3 D6).
     */
    @Serializable(with = SearchBarPositionInSearchSerializer::class)
    val barPosition: InSearchBarPosition? = null,
    /**
     * The search actions, in order (#106): the chips under the search bar
     * and the recognisers for numbers, addresses and the like. Present, it
     * replaces the device's list; `[]` means none; absent, the device keeps
     * its own.
     */
    val actions: List<SearchActionConfig>? = null,
    /** Icons in front of apps while results are a list (#3 slice 1). */
    val listIcons: Boolean? = null,
    /** An expanded app shows its version and package details. */
    val appDetails: Boolean? = null,
)

/**
 * `search.barPosition`: a position of its own, or [Follow] the home bar's.
 * Separate from [SearchBarPosition] so that `home.searchBar.position` cannot
 * take `follow`, which would have nothing to follow.
 */
enum class InSearchBarPosition { Top, Bottom, Follow }

internal object SearchBarPositionInSearchSerializer : FieldEnumSerializer<InSearchBarPosition>(
    "de.mm20.launcher2.config.SearchBarPositionInSearch", "search.barPosition", InSearchBarPosition.entries,
)

@Serializable(with = SearchResultLayoutSerializer::class)
enum class SearchResultLayout {
    @SerialName("grid")
    Grid,

    @SerialName("list")
    List,
}

/**
 * One search action (#106). [type] is one of [SearchActionTypes.Configurable]:
 * `url` (a web search by URL template, [label] and [url] with `${'$'}{1}` for the
 * query, optionally pinned to the app [packageName] and with its [encoding]),
 * `app` (search inside the app [packageName], shown as [label]) or a built-in
 * by name, which takes no other field.
 */
@Serializable
data class SearchActionConfig(
    val type: String,
    val label: String? = null,
    val url: String? = null,
    @SerialName("package")
    val packageName: String? = null,
    val encoding: String? = null,
)

object SearchActionTypes {
    const val Url = "url"
    /** Where a `url` action's url takes the query. */
    const val QueryPlaceholder = "\${1}"
    const val App = "app"
    const val WebSearch = "websearch"

    /**
     * A custom intent action a user made on the device: read back as its type
     * and label, kept where it is when a pulled file names it, never created
     * by a file (review on #116).
     */
    const val Intent = "intent"

    /** The launcher's own actions, by the name the database stores them under. */
    val BuiltIn = setOf(
        "call", "message", "email", "contact", "alarm", "timer", "calendar", "website",
        WebSearch, "share", "private_space",
    )
    val Configurable = BuiltIn + setOf(Url, App)

    /** How a `url` action encodes the query; `url` is the default. */
    val Encodings = setOf("url", "form", "none")
    const val DefaultEncoding = "url"
}

internal object SearchResultLayoutSerializer : FieldEnumSerializer<SearchResultLayout>(
    "de.mm20.launcher2.config.SearchResultLayout", "search.layout", SearchResultLayout.entries,
)

/** The one place the search defaults live: today's behavior, so a file without `search` changes nothing. */
object SearchDefaults {
    const val Favorites = true
    const val AllApps = true
    val Layout = SearchResultLayout.Grid
    const val Labels = true
    const val Contacts = true
    const val Shortcuts = true
    const val FilterBar = true
    const val OpenKeyboard = true
    const val LaunchOnEnter = true
    const val Reversed = false
    const val HiddenItemsButton = false
    const val ListIcons = true
    const val AppDetails = true
}
