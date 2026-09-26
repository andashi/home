package de.mm20.launcher2.ui.launcher.grid

import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.ExternalResource
import org.robolectric.Shadows.shadowOf

/**
 * Ends the view models a test built before the next test can touch
 * `Dispatchers.Main`.
 *
 * A view model's scope outlives the test that built it: nothing clears it.
 * Its settings collectors are resumed from real IO threads (DataStore), and
 * each resume dispatches into Main. When a later test sets or resets Main
 * during one of those resumes, kotlinx-coroutines fails that test with
 * "Dispatchers.Main is used concurrently with setting it" (CI, 2026-09-26).
 * Which test falls over depends only on timing, not on what it tests.
 *
 * So the rule cancels every [track]ed scope when the test ends and drains
 * Main until each has completed. A completed scope has no continuation left
 * for an IO thread to resume, so it cannot dispatch into Main any more. Only
 * then is Main reset, and only when the rule set it ([main]). Without [main]
 * the scopes run on Robolectric's main looper, which is drained instead.
 *
 * Order it inside the Koin rule and outside the Compose rule (a higher
 * `order` is further inside, and its teardown runs first):
 * - inside Koin, because the view models read settings that Koin provides
 *   until they are cancelled; stopping Koin first pulls those settings from
 *   under a scope that is still running;
 * - outside Compose, so the composition is disposed before the scopes are
 *   cancelled; the other way round, a still-live composition keeps
 *   collecting from a view model whose scope is already gone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelScopeRule(private val main: TestDispatcher? = null) : ExternalResource() {

    private val scopes = mutableListOf<Job>()

    fun <T : ViewModel> track(vm: T): T {
        scopes += vm.viewModelScope.coroutineContext.job
        return vm
    }

    override fun before() {
        main?.let { Dispatchers.setMain(it) }
    }

    override fun after() {
        try {
            scopes.forEach { it.cancel() }
            val deadline = System.nanoTime() + DrainTimeoutNanos
            while (scopes.any { !it.isCompleted }) {
                check(System.nanoTime() < deadline) { "a view model scope was still running after ${DrainTimeoutNanos / 1_000_000_000}s" }
                if (main != null) main.scheduler.advanceUntilIdle() else shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(1)
            }
        } finally {
            scopes.clear()
            main?.let { Dispatchers.resetMain() }
        }
    }

    private companion object {
        const val DrainTimeoutNanos = 5_000_000_000L
    }
}
