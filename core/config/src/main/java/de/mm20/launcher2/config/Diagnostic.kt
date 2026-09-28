package de.mm20.launcher2.config

import kotlinx.serialization.Serializable

@Serializable
enum class Severity {
    @kotlinx.serialization.SerialName("warning")
    Warning,

    @kotlinx.serialization.SerialName("error")
    Error,
}

/**
 * Every diagnostic code, each with its one severity. A code is a contract:
 * the provisioning host reads reports, and one condition reported as an
 * error in one section and a warning in another (profile-unavailable was
 * both) turns a push red or green by where a line was written. So a
 * severity is declared here once and nowhere else; [Diagnostic] can only be
 * built from an entry. The code strings are byte-stable for the same reason:
 * renaming one is a change of the contract, made here, in plain sight.
 */
enum class DiagnosticCode(val code: String, val severity: Severity) {
    AppUnavailable("app-unavailable", Severity.Warning),
    ApplyFailed("apply-failed", Severity.Error),
    DecodeFailed("decode-failed", Severity.Error),
    DuplicateApp("duplicate-app", Severity.Error),
    DuplicateAppOnDevice("duplicate-app-on-device", Severity.Warning),
    DuplicateFavorite("duplicate-favorite", Severity.Error),
    DuplicateGridItemId("duplicate-grid-item-id", Severity.Error),
    DuplicateSearchAction("duplicate-search-action", Severity.Error),
    DuplicateTag("duplicate-tag", Severity.Error),
    FavoriteUnavailable("favorite-unavailable", Severity.Warning),
    GestureAppUnavailable("gesture-app-unavailable", Severity.Warning),
    GridCrossesFold("grid-crosses-fold", Severity.Warning),
    IconPackUnavailable("icon-pack-unavailable", Severity.Warning),
    GridItemMoved("grid-item-moved", Severity.Warning),
    GridOptionIgnored("grid-option-ignored", Severity.Warning),
    GridOutOfBounds("grid-out-of-bounds", Severity.Warning),
    GridOverflow("grid-overflow", Severity.Warning),
    InertKey("inert-key", Severity.Warning),
    InputTooLarge("input-too-large", Severity.Error),
    InvalidApps("invalid-apps", Severity.Error),
    InvalidGlass("invalid-glass", Severity.Error),
    InvalidGridColumns("invalid-grid-columns", Severity.Error),
    InvalidGridGeometry("invalid-grid-geometry", Severity.Error),
    InvalidGridItemId("invalid-grid-item-id", Severity.Error),
    InvalidGridWidget("invalid-grid-widget", Severity.Error),
    InvalidIcons("invalid-icons", Severity.Error),
    InvalidPackageName("invalid-package-name", Severity.Error),
    InvalidSchemaVersion("invalid-schema-version", Severity.Error),
    InvalidSearch("invalid-search", Severity.Error),
    InvalidSearchAction("invalid-search-action", Severity.Error),
    InvalidTags("invalid-tags", Severity.Error),
    InvalidWallpaperImage("invalid-wallpaper-image", Severity.Error),
    MalformedJson("malformed-json", Severity.Error),
    MissingSchemaVersion("missing-schema-version", Severity.Error),
    PartialGridPosition("partial-grid-position", Severity.Error),
    PermissionMissing("permission-missing", Severity.Warning),
    ProfileUnavailable("profile-unavailable", Severity.Warning),
    ReadFailed("read-failed", Severity.Error),
    ReadStateFailed("read-state-failed", Severity.Error),
    SearchActionAppNotSearchable("search-action-app-not-searchable", Severity.Warning),
    SearchActionFieldIgnored("search-action-field-ignored", Severity.Warning),
    SearchActionIntentMissing("search-action-intent-missing", Severity.Warning),
    SearchActionReadOnly("search-action-read-only", Severity.Warning),
    SearchActionUnavailable("search-action-unavailable", Severity.Warning),
    SearchReversedWithTopBar("search-reversed-with-top-bar", Severity.Warning),
    TooManyFavorites("too-many-favorites", Severity.Error),
    TooManyGridItems("too-many-grid-items", Severity.Error),
    TooManySearchActions("too-many-search-actions", Severity.Error),
    TransliteratorUnavailable("transliterator-unavailable", Severity.Warning),
    UnknownKey("unknown-key", Severity.Warning),
    UnknownLayout("unknown-layout", Severity.Warning),
    UnknownWidgetProvider("unknown-widget-provider", Severity.Warning),
    UnsupportedSchemaVersion("unsupported-schema-version", Severity.Error),
    WallpaperMissing("wallpaper-missing", Severity.Error),
    WallpaperPendingForeground("wallpaper-pending-foreground", Severity.Warning),
    /** The search bar is hidden and nothing reaches search or settings (#229). */
    SearchUnreachable("search-unreachable", Severity.Warning),
    WallpaperReplacedDuringApply("wallpaper-replaced-during-apply", Severity.Error),
    WidgetTooLarge("widget-too-large", Severity.Warning),
    WidgetTooSmall("widget-too-small", Severity.Warning),

    /**
     * A write-back that did not write, and why: the code is this prefix and
     * the reason (ConfigWriteBack), one family with one severity.
     */
    WriteBackSkipped("write-back-skipped:", Severity.Warning),
}

/**
 * One finding of a reload. No data class, on purpose: a data class's `copy`
 * takes every field, and `copy(severity = ...)` would restate a severity next
 * to a code - the thing the closed constructor below prevents (review on
 * #215). Value equality stays, written out, because reports are compared.
 */
@Serializable
class Diagnostic
/**
 * Decoding only. The serializer builds a Diagnostic through its own synthetic
 * constructor, not this one, which is why this can be closed to callers: a
 * call that states a severity next to a code is how one code came to carry
 * two. Build from [DiagnosticCode] instead. If a Kotlin or serialization
 * upgrade ever needs this constructor, it fails at compile time.
 */
@Deprecated("Build a Diagnostic from its DiagnosticCode, which carries the severity", level = DeprecationLevel.ERROR)
constructor(
    val severity: Severity,
    val code: String,
    val path: String,
    val message: String,
) {
    /** The one way to build a diagnostic: its severity comes with its code. */
    @Suppress("DEPRECATION_ERROR")
    constructor(code: DiagnosticCode, path: String, message: String) : this(code.severity, code.code, path, message) {
        require(code != DiagnosticCode.WriteBackSkipped) { "a skipped write-back names its reason: Diagnostic(code, reason, path, message)" }
    }

    /** A code of a family: [code]'s prefix and [reason], with [code]'s severity. */
    @Suppress("DEPRECATION_ERROR")
    constructor(code: DiagnosticCode, reason: String, path: String, message: String) :
        this(code.severity, code.code + reason, path, message) {
        require(code == DiagnosticCode.WriteBackSkipped) { "only a skipped write-back is a family with a reason" }
    }

    override fun equals(other: Any?): Boolean =
        other is Diagnostic && severity == other.severity && code == other.code && path == other.path && message == other.message

    override fun hashCode(): Int = listOf(severity, code, path, message).hashCode()

    override fun toString(): String = "Diagnostic(severity=$severity, code=$code, path=$path, message=$message)"
}

/**
 * The table's entry for [code]: the entry itself, or the family whose prefix
 * it carries; null for a code this build does not know.
 */
fun diagnosticCodeOf(code: String): DiagnosticCode? =
    DiagnosticCode.entries.firstOrNull { it.code == code }
        ?: DiagnosticCode.WriteBackSkipped.takeIf { code.startsWith(it.code) }

/**
 * Whether this diagnostic says what this build's table says about its code.
 * One decoded from what an older build wrote can disagree (review on #215);
 * a code this build does not know is not judged.
 */
val Diagnostic.agreesWithTable: Boolean
    get() = diagnosticCodeOf(code)?.let { it.severity == severity } ?: true

data class ConfigParseResult(
    val config: LauncherConfig?,
    val diagnostics: List<Diagnostic>,
) {
    val isSuccess: Boolean
        get() = config != null && diagnostics.none { it.severity == Severity.Error }
}
