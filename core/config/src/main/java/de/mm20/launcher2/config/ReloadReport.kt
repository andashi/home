package de.mm20.launcher2.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What triggered a config reload. Persisted in the [ReloadReport] so the
 * read-back provider can tell explicit broadcasts, watcher events and the
 * startup drift check apart.
 */
@Serializable
enum class ReloadTrigger {
    @SerialName("broadcast")
    Broadcast,

    @SerialName("file-watcher")
    FileWatcher,

    @SerialName("startup-check")
    StartupCheck,

    /**
     * The launcher wrote `launcher.json` itself (edit mode writing `home.grid`
     * back, ADR 0003 revised 2026-09-22). The report records the hash of the
     * written bytes so the watcher can tell its own rename from a push.
     */
    @SerialName("self-write")
    SelfWrite,

    /**
     * The grid of this device was measured for the first time, or its rows
     * changed. A layout kept as written because its rows were not known yet
     * is fitted now; the reload applies the grid even where the file and the
     * store agree, because a layout kept as written is exactly that.
     */
    @SerialName("grid-measured")
    GridMeasured,
}

@Serializable
data class ReloadReport(
    val success: Boolean,
    val schemaVersion: Int? = null,
    val diagnostics: List<Diagnostic> = emptyList(),
    val appliedMutations: List<String> = emptyList(),
    val errorMessage: String? = null,
    /**
     * SHA-256 of the reloaded config text (UTF-8). Null when the config could
     * not be read at all. The watcher compares this against the current file
     * hash to decide whether a startup reload is needed.
     */
    val configSha256: String? = null,
    val trigger: ReloadTrigger? = null,
)
