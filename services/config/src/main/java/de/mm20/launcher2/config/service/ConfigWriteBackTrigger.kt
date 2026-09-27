package de.mm20.launcher2.config.service

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Asks for a write-back whenever the effective state changes (#3 slice 4,
 * B): the settings, the favorites, the search actions, the grid. There is
 * no switch for it (W2); what the device may not change is locked, like
 * `home.grid.locked`.
 *
 * The first write-back comes with the store's first emission, on start: a
 * change made before it ran - one the last process never got to - is not
 * lost.
 *
 * Changes that arrive while a write-back runs are taken together, so
 * dragging a slider makes one more write, not one per step. A change made
 * by a reload asks too, and waits for the reload's lock: the reload's own
 * changes are then no difference, and nothing is written.
 *
 * Write-back is held back until the apps' form is recorded
 * (`apps-form-unrecorded`), and the startup check records it after its
 * reload, which may change nothing the store would announce. So a build
 * that starts without the record asks once more when it is made (review on
 * #214); one that starts with it asks nothing extra.
 */
class ConfigWriteBackTrigger(
    private val store: ConfigStore,
    private val write: suspend () -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val appNaming: AppNaming? = null,
) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            // One conflated buffer for both sources. merge(...).conflate()
            // does not give that: the changes then waited unbuffered behind a
            // running write, and conflating each source as well held one per
            // buffer, three writes for a drag instead of two.
            val asks = Channel<Unit>(Channel.CONFLATED)
            launch { store.changes().collect { asks.send(Unit) } }
            if (appNaming != null) launch {
                if (!appNaming.recorded()) {
                    appNaming.awaitRecorded()
                    asks.send(Unit)
                }
            }
            while (true) {
                asks.receive()
                try {
                    write()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // One failed write must not end write-back for the process.
                    Log.w(TAG, "write-back failed", e)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}

private const val TAG = "ConfigWriteBack"
