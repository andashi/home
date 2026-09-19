package de.mm20.launcher2.config.service

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import de.mm20.launcher2.config.ConfigValidator
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Fork addition (Phase 2, ADR 0003): write-only ingest for the launcher
 * config file, the provisioning transport that works for **every** Android
 * user:
 *
 * ```
 * adb shell content write --user N \
 *     --uri content://<applicationId>.config-ingest/launcher.json < launcher.json
 * ```
 *
 * Why a provider and not `adb push`: the shell only sees the owner's
 * (user 0) external storage; `/storage/emulated/<N>/...` of a secondary
 * profile is "Permission denied" even as root (measured on the GrapheneOS
 * emulator, 2026-09-19, see ADR 0003). A content provider is resolved in the
 * target user by the system, so `--user N` reaches that user's launcher.
 *
 * This is transport only. The bytes land atomically (temp file, then rename)
 * in [ConfigLocation.configFile]; reloading stays with the two existing
 * triggers, the [ConfigWatcher] (sees the rename) and the explicit
 * [ReloadConfigReceiver] broadcast. One loader, no third code path.
 *
 * Wallpaper images (issue #22) take the same road:
 *
 * ```
 * adb shell content write --user N \
 *     --uri content://<applicationId>.config-ingest/wallpapers/<name> < image.jpg
 * ```
 *
 * and land in `<config-dir>/wallpapers/<name>`, where `launcher.json`'s
 * `appearance.wallpaper.image` refers to them. Uploads over
 * [MaxWallpaperBytes] are discarded on close.
 *
 * Gate: the manifest requires `WRITE_SECURE_SETTINGS` (held by shell and
 * system only) and, belt and braces, [openFile] rejects every calling uid
 * except shell and root. The provider never reads, lists or deletes; it
 * accepts exactly two path shapes and only write modes.
 */
class ConfigIngestProvider : ContentProvider() {

    /** Overridable for tests; production reads the binder identity. */
    internal var callingUid: () -> Int = { Binder.getCallingUid() }

    /** How long [openFile] waits for the user's external storage to appear. */
    internal var awaitStorageMs: Long = ConfigLocation.DefaultAwaitMs

    private val closeHandler: Handler by lazy {
        Handler(HandlerThread("config-ingest").apply { start() }.looper)
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val uid = callingUid()
        if (uid != Process.SHELL_UID && uid != Process.ROOT_UID) {
            throw SecurityException("Config ingest is restricted to the shell user (caller uid $uid)")
        }
        if (!mode.startsWith("w")) {
            throw SecurityException("Config ingest is write-only (mode '$mode')")
        }
        val context = context ?: throw IllegalStateException("Provider has no context")
        val segments = uri.pathSegments
        val relative = when {
            segments == listOf(ConfigLocation.ConfigFileName) -> ConfigLocation.ConfigFileName

            segments.size == 2 && segments[0] == ConfigLocation.WallpapersDirName &&
                    ConfigValidator.imageNameRegex.matches(segments[1]) ->
                "${ConfigLocation.WallpapersDirName}/${segments[1]}"

            else -> throw FileNotFoundException("Unknown ingest path: $uri")
        }
        val limit = if (relative == ConfigLocation.ConfigFileName) Long.MAX_VALUE else MaxWallpaperBytes
        // A freshly started user's external storage can lag behind its
        // package resolution; wait for it instead of failing the first write.
        val configDir = ConfigLocation.awaitConfigDir(context, awaitStorageMs)
            ?: throw FileNotFoundException("External files directory unavailable")
        val target = File(configDir, relative)
        val dir = target.parentFile ?: throw FileNotFoundException("No config directory")
        // Two uploads may race to create the directory; mkdirs() returns
        // false for the loser although the directory now exists.
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw FileNotFoundException("Could not create ${dir.absolutePath}")
        }
        sweepStaleUploads(dir)
        val tmp = newTempFile(dir)
        return ParcelFileDescriptor.open(
            tmp,
            ParcelFileDescriptor.MODE_WRITE_ONLY or
                    ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE,
            closeHandler,
        ) { error -> commit(tmp, target, error, limit) }
    }

    /**
     * Completes an ingest once the caller closed the descriptor: a clean
     * close renames the temp file onto the config atomically, a failed
     * transfer discards it and leaves the previous config untouched.
     * Returns true when the config was replaced.
     */
    internal fun commit(
        tmp: File,
        target: File,
        error: IOException?,
        maxBytes: Long = Long.MAX_VALUE,
    ): Boolean {
        if (error != null) {
            Log.w(TAG, "Config ingest aborted, discarding partial upload", error)
            tmp.delete()
            return false
        }
        if (tmp.length() > maxBytes) {
            Log.w(TAG, "Upload of ${tmp.length()} bytes exceeds the limit of $maxBytes, discarded")
            tmp.delete()
            return false
        }
        if (!tmp.renameTo(target)) {
            Log.e(TAG, "Config ingest could not rename ${tmp.name} to ${target.name}")
            tmp.delete()
            return false
        }
        return true
    }

    /**
     * One temp file per upload, so concurrent writers never truncate each
     * other and the close of one never renames the other's bytes.
     */
    internal fun newTempFile(dir: File): File =
        File.createTempFile("upload.", ".$IngestTmpSuffix", dir)

    /**
     * Removes uploads whose writer died without closing (no commit ever
     * happens for them). Anything older than [StaleUploadMs] cannot be an
     * upload in flight.
     */
    internal fun sweepStaleUploads(dir: File, now: Long = System.currentTimeMillis()) {
        dir.listFiles { f -> f.isFile && f.name.endsWith(".$IngestTmpSuffix") }
            ?.filter { now - it.lastModified() > StaleUploadMs }
            ?.forEach { it.delete() }
    }

    override fun getType(uri: Uri): String? = null

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        throw UnsupportedOperationException("Config ingest is write-only: $uri")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw UnsupportedOperationException("Config ingest accepts streams only (content write): $uri")
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        throw UnsupportedOperationException("Config ingest accepts streams only (content write): $uri")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        throw UnsupportedOperationException("Config ingest is write-only: $uri")
    }

    companion object {
        const val AuthoritySuffix = ".config-ingest"
        internal const val IngestTmpSuffix = "ingest"
        internal const val StaleUploadMs = 10 * 60 * 1000L
        internal const val MaxWallpaperBytes = 32L * 1024 * 1024
        private const val TAG = "ConfigIngestProvider"
    }
}
