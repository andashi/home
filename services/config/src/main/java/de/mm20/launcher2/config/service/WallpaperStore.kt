package de.mm20.launcher2.config.service

import android.app.WallpaperManager
import android.content.Context
import android.os.SystemClock
import de.mm20.launcher2.config.ConfigParser
import de.mm20.launcher2.config.Diagnostic
import de.mm20.launcher2.config.Severity
import de.mm20.launcher2.config.WallpaperTarget
import de.mm20.launcher2.glass.BackdropImage
import de.mm20.launcher2.glass.GlassBackdropSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /**
     * The wallpaper this profile is managed to have and has not been given
     * yet, or null when nothing is outstanding.
     *
     * Reported on every reload rather than only on the one that deferred it
     * (#37). Once the intent is recorded, [current] answers with it, the
     * differ sees no difference and produces no mutation, and [apply] is never
     * reached again - so a second run to check that everything sits would come
     * back silently green for something that is still waiting. That is exactly
     * the moment the information is wanted.
     */
    suspend fun pending(): WallpaperState?
}

data class WallpaperState(val image: String, val target: WallpaperTarget)

/**
 * How long [WallpaperStore.ensureRendered] refuses to re-apply a record it has
 * already re-applied (issue #29). Long enough to cover a crop that outlives
 * several activity resumes - the observed duplicate was 15 s apart - and short
 * enough that a re-apply which genuinely failed is retried while the profile
 * is still in front of someone.
 */
internal const val ReapplyCooldownMs = 60_000L

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
    /**
     * Recorded but never handed to the system, because nothing was on screen
     * when the config asked for it (#37). [systemId] and [lockId] are
     * meaningless while this is true - no set happened, so there is nothing to
     * compare them against.
     */
    val pending: Boolean = false,
    val systemId: Int,
    val lockId: Int,
)

class DefaultWallpaperStore(
    context: Context,
    private val applier: WallpaperApplier,
    /** Whether anyone can see the result right now, see [ForegroundState] (#37). */
    private val foreground: ForegroundState = ForegroundState(),
    /** Monotonic, injectable so the re-apply cooldown is testable. */
    private val elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() },
) : WallpaperStore, GlassBackdropSource {
    private val appContext = context.applicationContext

    private val backdrop = MutableStateFlow<BackdropImage?>(null)

    /**
     * The managed wallpaper as the glass backdrop's source (#74): only while
     * it is on the home screen as set - target `home` or `both`, handed to the
     * system (not deferred), the system still holding it and the file
     * unchanged. Anything else is null and the surfaces draw without a
     * backdrop; a backdrop that no longer matches what is on screen would be
     * worse than none.
     */
    override val image: StateFlow<BackdropImage?> = backdrop.asStateFlow()

    override suspend fun refresh() {
        withContext(Dispatchers.IO) { mutex.withLock { publishBackdrop() } }
    }

    /** Called with [mutex] held, after every write of the record and on [refresh]. */
    private fun publishBackdrop() {
        backdrop.value = readBackdrop()
    }

    private fun readBackdrop(): BackdropImage? {
        val applied = readApplied() ?: return null
        if (applied.pending || applied.target == WallpaperTarget.Lock) return null
        val file = ConfigLocation.wallpaperFile(appContext, applied.image) ?: return null
        if (!file.isFile || file.readBytes().sha256Hex() != applied.sha256) return null
        val ids = try {
            applier.currentIds()
        } catch (e: Exception) {
            return null
        }
        return if (applied.holds(ids)) BackdropImage(file.absolutePath, applied.sha256) else null
    }
    private val stateFile = File(appContext.filesDir, "config/wallpaper-state.json")

    /** One writer at a time: reloads and the foreground fixer share this store. */
    private val mutex = Mutex()

    /**
     * What the last re-apply wrote, and when (issue #29).
     *
     * In memory on purpose: it guards against a second re-apply inside one
     * process, which is where the duplicate was observed, and a restart should
     * be free to try again.
     */
    private var lastReapply: Pair<AppliedWallpaper, Long>? = null

    override suspend fun current(): WallpaperState? = withContext(Dispatchers.IO) {
        val applied = readApplied() ?: return@withContext null
        val file = ConfigLocation.wallpaperFile(appContext, applied.image) ?: return@withContext null
        if (!file.exists()) return@withContext null
        if (file.readBytes().sha256Hex() != applied.sha256) return@withContext null
        // A deferred wallpaper is still the one this profile is managed to
        // have: the config named it, the file is here, and the foreground hook
        // will set it. Reporting null instead would make the differ reapply on
        // every reload and would drop appearance.wallpaper out of the
        // read-back, which the provisioning convergence check compares against
        // the pushed file (#37).
        if (applied.pending) return@withContext WallpaperState(applied.image, applied.target)
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
        withContext(Dispatchers.IO) {
            mutex.withLock { applyLocked(image, target).also { publishBackdrop() } }
        }

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

            // Nothing on screen: record the intent and stop. The system does
            // not crop for a background user, so setting here costs 30 s for a
            // result it discards and the foreground hook redoes anyway (#37).
            // The same diagnostic is reported as for a set that did not render,
            // because from a host's point of view it is the same situation:
            // the wallpaper is managed, named, and not visible yet.
            if (!foreground.isForeground) {
                writeApplied(
                    AppliedWallpaper(
                        image = image,
                        target = target,
                        sha256 = before,
                        systemId = 0,
                        lockId = 0,
                        pending = true,
                    )
                )
                // No diagnostic from here. "A wallpaper is outstanding" is a
                // state, not an event, and DefaultConfigStore reports it from
                // pending() on every reload - including this one. Emitting it
                // here as well put it in the report twice on the reload that
                // deferred, which is how the duplicated Security-Fixes trailer
                // in v0.3.1 happened too: two places sure they were the one
                // that had to say it.
                return emptyList()
            }

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

    override suspend fun pending(): WallpaperState? = withContext(Dispatchers.IO) {
        val applied = readApplied() ?: return@withContext null
        if (!applied.pending) return@withContext null
        WallpaperState(applied.image, applied.target)
    }

    override suspend fun ensureRendered(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val applied = readApplied() ?: return@withLock false
            // A pending record was never handed to the system, so whatever is
            // rendered belongs to someone else and says nothing about us (#37).
            if (!applied.pending && applier.isRendered(applied.target)) return@withLock false
            // isRendered says "no file yet", which covers both "never asked"
            // and "asked, still cropping" - WallpaperManagerService produces
            // the crop asynchronously, and on the emulator it took longer than
            // the 15 s between two activity resumes. Without this the second
            // resume sees the same false and sets the same image again, paying
            // for another crop pass (issue #29, and issue #37 on what one
            // costs). Refuse to re-apply the very same record twice inside the
            // window; a different one - a new config, a new file - is not the
            // record we just asked for and goes through.
            val last = lastReapply
            if (last != null &&
                last.first == applied &&
                elapsedRealtime() - last.second < ReapplyCooldownMs
            ) {
                return@withLock false
            }
            // A wallpaper the user set by hand in the meantime is drift, not a
            // pending render; the next explicit reload decides, not this hook.
            // A pending record is exempt: nothing was ever set under it, so
            // there are no ids of ours for the system to still be holding.
            if (!applied.pending && !applied.holds(applier.currentIds())) return@withLock false
            val file = ConfigLocation.wallpaperFile(appContext, applied.image) ?: return@withLock false
            if (!file.isFile || file.readBytes().sha256Hex() != applied.sha256) return@withLock false
            // Checked again here, not only by the caller: everything above -
            // reading the record, resolving the file, hashing it - happens on
            // the IO dispatcher while the lock is held, and the last activity
            // can pause in the meantime. Going ahead then would start exactly
            // the crop this change exists to avoid, in the background, for
            // nobody. The record stays pending so the next resume retries.
            if (!foreground.isForeground) return@withLock false

            val ids = applier.apply(file, applied.target)
            // Same guard as apply(): a same-name upload landing mid-set must not
            // be recorded under the old hash.
            if (file.readBytes().sha256Hex() != applied.sha256) return@withLock false
            val reapplied = applied.copy(systemId = ids.system, lockId = ids.lock, pending = false)
            writeApplied(reapplied)
            lastReapply = reapplied to elapsedRealtime()
            publishBackdrop()
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
