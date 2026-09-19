package de.mm20.launcher2.config.service

import android.app.WallpaperManager
import android.content.Context
import de.mm20.launcher2.config.ConfigParser
import de.mm20.launcher2.config.Diagnostic
import de.mm20.launcher2.config.Severity
import de.mm20.launcher2.config.WallpaperTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Fork addition (issue #22): the config-managed wallpaper of this profile.
 *
 * Android exposes no "which image is set", only an id that changes with every
 * set. To make convergence idempotent and drift visible, the store remembers
 * what it applied (image name, file hash, target, resulting ids) and reports
 * the image as current only while all of that still holds. Anything else
 * (user changed the wallpaper by hand, a new file under the same name, no
 * record at all) reads as "no managed wallpaper", so the differ reapplies.
 */
interface WallpaperStore {
    suspend fun current(): WallpaperState?
    suspend fun apply(image: String, target: WallpaperTarget): List<Diagnostic>

    /**
     * Re-applies the recorded wallpaper if the system holds it but never
     * rendered it. WallpaperManagerService crops and draws a static wallpaper
     * only for the *current* user: a set from a background profile stores the
     * original and assigns ids, and the screen stays black until the profile
     * is in the foreground and the wallpaper is set again (measured on the
     * GrapheneOS emulator, 2026-09-19). Called from the launcher's foreground
     * hook. Returns true when a re-apply happened.
     */
    suspend fun ensureRendered(): Boolean
}

data class WallpaperState(val image: String, val target: WallpaperTarget)

/** What [WallpaperApplier] reports after a set: the per-target wallpaper ids. */
data class WallpaperIds(val system: Int, val lock: Int)

/** Port over [WallpaperManager], fakeable in unit tests. */
interface WallpaperApplier {
    fun currentIds(): WallpaperIds
    fun apply(file: File, target: WallpaperTarget): WallpaperIds

    /** Whether the system has a drawable (cropped) wallpaper file for [target]. */
    fun isRendered(target: WallpaperTarget): Boolean
}

class AndroidWallpaperApplier(context: Context) : WallpaperApplier {
    private val manager = WallpaperManager.getInstance(context.applicationContext)

    override fun currentIds(): WallpaperIds = WallpaperIds(
        system = manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM),
        lock = manager.getWallpaperId(WallpaperManager.FLAG_LOCK),
    )

    override fun apply(file: File, target: WallpaperTarget): WallpaperIds {
        val flags = when (target) {
            WallpaperTarget.Home -> WallpaperManager.FLAG_SYSTEM
            WallpaperTarget.Lock -> WallpaperManager.FLAG_LOCK
            WallpaperTarget.Both -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
        }
        FileInputStream(file).use { stream ->
            manager.setStream(stream, null, true, flags)
        }
        return currentIds()
    }

    override fun isRendered(target: WallpaperTarget): Boolean {
        // For "both" the lock wallpaper legitimately shares the system one and
        // has no file of its own, so the system file decides.
        val flag = if (target == WallpaperTarget.Lock) WallpaperManager.FLAG_LOCK else WallpaperManager.FLAG_SYSTEM
        return try {
            manager.getWallpaperFile(flag)?.use { true } ?: false
        } catch (e: Exception) {
            false
        }
    }
}

@Serializable
internal data class AppliedWallpaper(
    val image: String,
    val target: WallpaperTarget,
    val sha256: String,
    val systemId: Int,
    val lockId: Int,
)

class DefaultWallpaperStore(
    context: Context,
    private val applier: WallpaperApplier,
) : WallpaperStore {
    private val appContext = context.applicationContext
    private val stateFile = File(appContext.filesDir, "config/wallpaper-state.json")

    /** One writer at a time: reloads and the foreground fixer share this store. */
    private val mutex = Mutex()

    override suspend fun current(): WallpaperState? = withContext(Dispatchers.IO) {
        val applied = readApplied() ?: return@withContext null
        val file = ConfigLocation.wallpaperFile(appContext, applied.image) ?: return@withContext null
        if (!file.exists()) return@withContext null
        if (file.readBytes().sha256Hex() != applied.sha256) return@withContext null
        val ids = try {
            applier.currentIds()
        } catch (e: Exception) {
            return@withContext null
        }
        if (applied.holds(ids)) WallpaperState(applied.image, applied.target) else null
    }

    /** The system still shows what we set: the ids of the targeted slots are unchanged. */
    private fun AppliedWallpaper.holds(ids: WallpaperIds): Boolean {
        val systemHolds = target == WallpaperTarget.Lock || ids.system == systemId
        val lockHolds = target == WallpaperTarget.Home || ids.lock == lockId
        return systemHolds && lockHolds
    }

    override suspend fun apply(image: String, target: WallpaperTarget): List<Diagnostic> =
        withContext(Dispatchers.IO) { mutex.withLock { applyLocked(image, target) } }

    private fun applyLocked(image: String, target: WallpaperTarget): List<Diagnostic> {
        run {
            val file = ConfigLocation.wallpaperFile(appContext, image)
            if (file == null || !file.isFile) {
                return listOf(
                    Diagnostic(
                        Severity.Error,
                        "wallpaper-missing",
                        "appearance.wallpaper.image",
                        "No uploaded wallpaper named '$image'; upload it to " +
                                "content://<applicationId>.config-ingest/wallpapers/$image first",
                    )
                )
            }
            // The file may be replaced atomically by a same-name upload while
            // Android reads it. Record the state only when the bytes before
            // and after the set agree; otherwise the next reload reapplies.
            val before = file.readBytes().sha256Hex()
            val ids = applier.apply(file, target)
            val after = file.readBytes().sha256Hex()
            if (before != after) {
                return listOf(
                    Diagnostic(
                        Severity.Error,
                        "wallpaper-replaced-during-apply",
                        "appearance.wallpaper.image",
                        "'$image' was replaced while it was being applied; reload again",
                    )
                )
            }
            writeApplied(
                AppliedWallpaper(
                    image = image,
                    target = target,
                    sha256 = after,
                    systemId = ids.system,
                    lockId = ids.lock,
                )
            )
            return if (applier.isRendered(target)) {
                emptyList()
            } else {
                listOf(
                    Diagnostic(
                        Severity.Warning,
                        "wallpaper-pending-foreground",
                        "appearance.wallpaper.image",
                        "'$image' is stored but not yet rendered: the system crops a static " +
                                "wallpaper only for the current user. The launcher re-applies it " +
                                "the next time this profile is in the foreground.",
                    )
                )
            }
        }
    }

    override suspend fun ensureRendered(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val applied = readApplied() ?: return@withLock false
            if (applier.isRendered(applied.target)) return@withLock false
            // A wallpaper the user set by hand in the meantime is drift, not a
            // pending render; the next explicit reload decides, not this hook.
            if (!applied.holds(applier.currentIds())) return@withLock false
            val file = ConfigLocation.wallpaperFile(appContext, applied.image) ?: return@withLock false
            if (!file.isFile || file.readBytes().sha256Hex() != applied.sha256) return@withLock false
            val ids = applier.apply(file, applied.target)
            // Same guard as apply(): a same-name upload landing mid-set must not
            // be recorded under the old hash.
            if (file.readBytes().sha256Hex() != applied.sha256) return@withLock false
            writeApplied(applied.copy(systemId = ids.system, lockId = ids.lock))
            true
        }
    }

    private fun readApplied(): AppliedWallpaper? {
        if (!stateFile.exists()) return null
        return try {
            ConfigParser.json.decodeFromString(AppliedWallpaper.serializer(), stateFile.readText())
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: IOException) {
            null
        }
    }

    private fun writeApplied(applied: AppliedWallpaper) {
        stateFile.parentFile?.mkdirs()
        val tmp = File(stateFile.parentFile, "${stateFile.name}.tmp")
        tmp.writeText(ConfigParser.json.encodeToString(AppliedWallpaper.serializer(), applied))
        if (!tmp.renameTo(stateFile)) {
            tmp.delete()
            throw IOException("Could not replace ${stateFile.name}")
        }
    }
}
