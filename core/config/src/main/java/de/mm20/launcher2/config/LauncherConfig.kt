package de.mm20.launcher2.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    val clock: ClockConfig? = null,
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

@Serializable
data class Favorite(
    val packageName: String,
    val profile: Profile = Profile.Personal,
)

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
    @SerialName("weather")
    Weather,

    @SerialName("music")
    Music,

    @SerialName("calendar")
    Calendar,

    @SerialName("apps")
    Apps,

    @SerialName("notes")
    Notes,
}

@Serializable
data class ClockConfig(
    val style: ClockStyle? = null,
    val fillHeight: Boolean? = null,
)

@Serializable
enum class ClockStyle {
    @SerialName("digital1")
    Digital1,

    @SerialName("digital2")
    Digital2,

    @SerialName("orbit")
    Orbit,

    @SerialName("analog")
    Analog,

    @SerialName("binary")
    Binary,

    @SerialName("segment")
    Segment,

    @SerialName("empty")
    Empty,
}
