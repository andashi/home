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
    val dock: DockConfig? = null,
    val widgets: WidgetsConfig? = null,
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

@Serializable
data class DockConfig(
    val enabled: Boolean? = null,
    val favorites: List<Favorite>? = null,
)

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

@Serializable
data class WidgetsConfig(
    val enabled: Boolean? = null,
    val widgets: List<BuiltinWidget>? = null,
)

@Serializable
enum class BuiltinWidget {
    @SerialName("apps")
    Apps,
}

