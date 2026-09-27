package de.mm20.launcher2.config.service

import android.content.Context
import android.content.pm.ApplicationInfo
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

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
    /** How the file wrote the apps of each list that names them (AppNaming): `apps`, `tags`. */
    private val namings: List<AppNaming> = emptyList(),
    /**
     * A signal per package that may have become available, by name
     * (PackageArrivals); null when nothing watches them.
     */
    private val arrivals: Flow<String>? = null,
) {
    private val appContext = context.applicationContext

    private var observer: FileObserver? = null
    private var startJob: Job? = null
    private var measureJob: Job? = null
    private var appsJob: Job? = null
    // Each new measurement is a generation; a fit clears only the one it read
    // before its reload, so a measurement arriving during a reload stays
    // pending (#178 review). Written by the collector, read under fitLock.
    private val measuredGeneration = AtomicLong(0)
    private var fittedGeneration = 0L
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
        if (appsJob?.isActive != true) appsJob = watchArrivals()
    }

    fun stop() {
        startJob?.cancel()
        startJob = null
        measureJob?.cancel()
        measureJob = null
        appsJob?.cancel()
        appsJob = null
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
                measuredGeneration.incrementAndGet()
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
        val generation = measuredGeneration.get()
        if (generation == fittedGeneration) return@withLock
        val file = ConfigLocation.configFile(appContext) ?: return@withLock
        if (!file.exists()) return@withLock
        val report = reloader.reload(file, ReloadTrigger.GridMeasured)
        if (report.success || GridSection in report.appliedMutations) fittedGeneration = generation
    }

    /**
     * Applies what the file names once it is installed (#207 review). A
     * favorite, a widget provider, an app's label or visibility, or a profile's
     * entry naming something absent is skipped and reported, and when it
     * arrives nothing else reloads the file: the file did not change, so no
     * watcher event, no startup drift.
     *
     * Observing the stores cannot carry this, however natural that looks. The
     * app customizations' change stream describes the installed apps' state,
     * and an arriving app with no customization yet describes as nothing, so
     * `distinctUntilChanged()` lets nothing through; favorites and widgets
     * have no such stream at all. Nor can the list of launcher apps: a package
     * that brings only a widget adds no launcher entry (review on #213). The
     * trigger is the package signal itself ([arrivals]).
     *
     * Once at start, then per signal: a reload while the last report waits on
     * something absent ([WaitingCodes]), and nothing otherwise. The start
     * covers an app that arrived while the launcher was not running, which no
     * signal will report again (review on #213).
     */
    internal fun watchArrivals(): Job? {
        val arrivals = arrivals ?: return null
        return scope.launch {
            // Listened to before the start is decided: the package callback
            // registers when its flow is collected, and a package installed
            // during the start reload would otherwise go unheard (review on
            // #213). Signals queue meanwhile. The start's first read of the
            // report suspends on IO before the reload reads the device, which
            // lets the callback register first.
            val queued = Channel<String>(Channel.UNLIMITED)
            launch { arrivals.collect { queued.send(it) } }
            yield()
            onArrival(null)
            for (signal in queued) onArrival(signal)
        }
    }

    /**
     * Reloads if the last report waits on something absent. The decision is
     * made under the reload lock ([ConfigReloader.reloadIf]): a reload already
     * running reports first, so an arrival cannot read the report from before
     * it and skip (review on #213). [what] is the package, or null at start.
     */
    internal suspend fun onArrival(what: String?) {
        val file = ConfigLocation.configFile(appContext) ?: return
        if (!file.exists()) return
        val report = reloader.reloadIf(file, ReloadTrigger.AppsChanged) {
            reportStore.read()?.diagnostics.orEmpty().any { it.code in WaitingCodes }
        }
        // Which packages a person has is theirs: only a debuggable build names
        // it, for the device tests that wait for the signal to arrive.
        if (debuggable) Log.d(TAG, "arrival ${what ?: "(start)"}: ${if (report != null) "reloaded" else "nothing waits"}")
    }

    private val debuggable = appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

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
            // After an update from a build without it, the record of how the
            // file wrote its apps is missing while everything else matches:
            // one reload makes it. A file that spells out a first activity
            // then reads differently from the device, so the reload applies
            // `apps` and records the form; one that does not needs no form,
            // and an empty record says the reload has run (review on #207).
            val noNaming = namings.any { !it.recorded() }
            // The reload makes the record if it goes through (ConfigReloader);
            // a failed one leaves none, and the next start tries again.
            if (report == null || report.configSha256 != hash || noBaseline || noNaming) {
                reloader.reload(file, ReloadTrigger.StartupCheck)
            }
            fitPendingMeasurement()
        }
    }

    companion object {
        const val DefaultDebounceMs = 300L

        /**
         * The reports of something absent that an installed app can make
         * present. Not the search actions' codes: those name a feature this
         * device lacks, which no install brings.
         */
        val WaitingCodes: Set<String> = setOf(
            "favorite-unavailable",
            "unknown-widget-provider",
            "app-unavailable",
            "profile-unavailable",
            // A gesture's app (#3 slice 2): the file keeps it for the day it is installed.
            "gesture-app-unavailable",
            // An icon pack (#3 slice 4): the app shows its normal icon, a tag its own, until the pack is installed.
            "icon-pack-unavailable",
        )
        private const val GridSection = "home.grid"
        private const val StartupRetryCount = 40
        private const val StartupRetryDelayMs = 250L
        private const val TAG = "ConfigWatcher"
    }
}
