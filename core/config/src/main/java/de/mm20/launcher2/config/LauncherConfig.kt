package de.mm20.launcher2.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
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
)

@Serializable
data class IconsConfig(
    val themed: Boolean? = null,
    val enforceThemed: Boolean? = null,
    val pack: String? = null,
)

@Serializable
data class AppearanceConfig(
    val transparency: TransparencyConfig? = null,
    val wallpaper: WallpaperConfig? = null,
)

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
data class TransparencyConfig(
    val name: String? = null,
    val background: Float? = null,
    val surface: Float? = null,
    val elevatedSurface: Float? = null,
)

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
 * is twice as wide and the cover display renders its columns `0 until
 * columns` (D7). Rows are derived from the screen, not configured (D1).
 * [locked] forbids edit mode, so nothing is ever written back for a locked
 * profile (D3). [layouts] is keyed by [GridLayouts.Phone] or
 * [GridLayouts.Fold]; a device uses exactly one of them.
 */
@Serializable
data class GridConfig(
    val columns: Int? = null,
    val locked: Boolean? = null,
    val layouts: Map<String, GridLayoutConfig>? = null,
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
    }
}
