package de.mm20.launcher2.config.service

import android.content.Context
import android.os.FileObserver
import android.util.Log
import de.mm20.launcher2.config.ReloadTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Fork addition (Phase 2, ADR 0003): the convenience reload path for
 * interactive editing (edit → save → launcher updates). Watches the config
 * directory for `launcher.json` `CLOSE_WRITE` and `MOVED_TO` events (the
 * latter covers atomic save-via-rename) and debounces them by
 * [DefaultDebounceMs] so editors that write non-atomically do not trigger
 * partial reloads.
 *
 * On [start] a drift check runs once: if the config file exists and either no
 * [de.mm20.launcher2.config.ReloadReport] exists yet or the file's SHA-256
 * differs from the hash recorded in the last report, one reload is triggered
 * with [ReloadTrigger.StartupCheck]. This catches config pushes that happened
 * while the launcher was not running.
 *
 * Everything funnels into the same [ConfigReloader] as the broadcast
 * receiver; watcher-triggered reloads carry [ReloadTrigger.FileWatcher].
 */
class ConfigWatcher(
    context: Context,
    private val reloader: ConfigReloader,
    private val reportStore: ReloadReportStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val debounceMs: Long = DefaultDebounceMs,
    private val baselineStore: AppliedBaselineStore? = null,
    /** This device's measured grid rows (MeasuredGridRows); null when nothing measures. */
    private val measurements: Flow<Map<String, Int>>? = null,
) {
    private val appContext = context.applicationContext

    private var observer: FileObserver? = null
    private var startJob: Job? = null
    private var measureJob: Job? = null
    @Volatile private var measurementPending = false
    private val fitLock = Mutex()
    internal var debounceJob: Job? = null

    /**
     * Starts watching and schedules the startup drift check. The app process
     * can be created by the content provider before external files are
     * available, so the initial lookup is retried instead of silently
     * disabling the watcher forever.
     */
    fun start() {
        if (observer != null || startJob?.isActive == true) return
        startJob = scope.launch {
            val dir = awaitConfigDir()
            if (dir == null) {
                Log.w(TAG, "Config directory unavailable; file watcher disabled")
                return@launch
            }
            try {
                dir.mkdirs()
            } catch (e: Exception) {
                Log.w(TAG, "Could not create config directory", e)
                return@launch
            }
            startObserver(dir)
            startupCheck()
        }
        if (measureJob?.isActive != true) measureJob = watchMeasurements()
    }

    fun stop() {
        startJob?.cancel()
        startJob = null
        measureJob?.cancel()
        measureJob = null
        debounceJob?.cancel()
        debounceJob = null
        observer?.stopWatching()
        observer = null
    }

    private suspend fun awaitConfigDir(): File? {
        repeat(StartupRetryCount) {
            ConfigLocation.configDir(appContext)?.let { return it }
            delay(StartupRetryDelayMs)
        }
        return null
    }

    private fun startObserver(dir: File) {
        if (observer != null) return
        @Suppress("DEPRECATION")
        val obs = object : FileObserver(dir.absolutePath, CLOSE_WRITE or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (path == ConfigLocation.ConfigFileName) {
                    onConfigFileEvent()
                }
            }
        }
        try {
            obs.startWatching()
            observer = obs
        } catch (e: Exception) {
            Log.w(TAG, "Could not start config file observer", e)
        }
    }

    internal fun onConfigFileEvent() {
        val file = ConfigLocation.configFile(appContext) ?: return
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(debounceMs)
            if (isOwnWrite(file)) {
                Log.d(TAG, "Config file event matches the launcher's own write; no reload")
                return@launch
            }
            reloader.reload(file, ReloadTrigger.FileWatcher)
            fitPendingMeasurement()
        }
    }

    /**
     * Write-back (ADR 0003, revised 2026-09-22) renames onto `launcher.json`
     * exactly like a push does, and the observer cannot tell who renamed.
     * The self-write report carries the hash of the bytes the launcher
     * wrote; a file with that hash is the launcher's own and needs no
     * reload. Only a self-write report counts: a push of unchanged bytes
     * after a foreign reload still reloads, because provisioning waits for
     * that report (e2e/l4-config.sh step 5).
     */
    private suspend fun isOwnWrite(file: File): Boolean {
        val report = reportStore.read() ?: return false
        if (report.trigger != ReloadTrigger.SelfWrite || report.configSha256 == null) return false
        return report.configSha256 == fileHash(file)
    }

    private suspend fun fileHash(file: File): String? = try {
        withContext(Dispatchers.IO) {
            if (!file.exists()) null else file.readBytes().sha256Hex()
        }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }

    /**
     * Fits a layout that was kept as written because its rows were not known
     * yet (GridRowsSource): each new measurement reloads the config with
     * [ReloadTrigger.GridMeasured], which applies the grid even where the
     * file and the store agree. Without this, a config pushed before the
     * first draw would stay unfitted, and unreported, until an unrelated
     * change reloaded it.
     */
    internal fun watchMeasurements(): Job? {
        val measurements = measurements ?: return null
        return scope.launch {
            measurements.filter { it.isNotEmpty() }.distinctUntilChanged().collect {
                measurementPending = true
                fitPendingMeasurement()
            }
        }
    }

    /**
     * A measurement is pending until a reload has fitted the grid to it. One
     * that could not run (no config directory or file yet) or failed (a file
     * caught half-written) would otherwise be lost: a later reload finds the
     * file and the store agreeing and fits nothing, and a startup check that
     * knows the file skips it. So it is retried after each file-watcher reload
     * and after the startup check, until one succeeds (#178 review).
     */
    private suspend fun fitPendingMeasurement() = fitLock.withLock {
        if (!measurementPending) return@withLock
        val file = ConfigLocation.configFile(appContext) ?: return@withLock
        if (!file.exists()) return@withLock
        val report = reloader.reload(file, ReloadTrigger.GridMeasured)
        if (report.success || GridSection in report.appliedMutations) measurementPending = false
    }

    internal fun startupCheck(): Job? {
        val file = ConfigLocation.configFile(appContext) ?: return null
        return scope.launch {
            val hash = fileHash(file)
            if (hash == null && !file.exists()) return@launch
            val report = reportStore.read()
            // A write-back needs the baseline of exactly this file (#3 slice 4);
            // after an update or with cleared data the report can have it while
            // the baseline does not, and one reload records it.
            val noBaseline = baselineStore != null && baselineStore.read()?.configSha256 != hash
            if (report == null || report.configSha256 != hash || noBaseline) {
                reloader.reload(file, ReloadTrigger.StartupCheck)
            }
            fitPendingMeasurement()
        }
    }

    companion object {
        const val DefaultDebounceMs = 300L
        private const val GridSection = "home.grid"
        private const val StartupRetryCount = 40
        private const val StartupRetryDelayMs = 250L
        private const val TAG = "ConfigWatcher"
    }
}
