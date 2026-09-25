package de.mm20.launcher2.config.service

import de.mm20.launcher2.config.ConfigMutation
import de.mm20.launcher2.config.ConfigState
import de.mm20.launcher2.config.Diagnostic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** #3 slice 4 (B): every change of the effective state asks for one write-back. */
@RunWith(RobolectricTestRunner::class)
class ConfigWriteBackTriggerTest {

    private class Changes : ConfigStore {
        val flow = MutableSharedFlow<Unit>()
        override suspend fun readState() = ConfigState()
        override suspend fun apply(mutations: List<ConfigMutation>) = emptyList<Diagnostic>()
        override fun changes(): Flow<Unit> = flow
    }

    private fun TestScope.trigger(store: ConfigStore, write: suspend () -> Unit) =
        ConfigWriteBackTrigger(store, write, backgroundScope).also { it.start(); runCurrent() }

    @Test
    fun `a change asks for a write-back`() = runTest(StandardTestDispatcher()) {
        val store = Changes()
        var writes = 0
        trigger(store) { writes++ }

        store.flow.emit(Unit)
        runCurrent()

        assertEquals(1, writes)
    }

    /** A slider dragged across: the changes that arrive while a write runs make one more, not one each. */
    @Test
    fun `changes during a write-back are taken together`() = runTest(StandardTestDispatcher()) {
        val store = Changes()
        val gate = CompletableDeferred<Unit>()
        var writes = 0
        trigger(store) { writes++; if (writes == 1) gate.await() }

        store.flow.emit(Unit)
        runCurrent()
        repeat(3) { store.flow.emit(Unit) }
        gate.complete(Unit)
        runCurrent()

        assertEquals(2, writes)
    }

    @Test
    fun `a write-back that fails does not stop the next one`() = runTest(StandardTestDispatcher()) {
        val store = Changes()
        var writes = 0
        trigger(store) { writes++; if (writes == 1) error("disk full") }

        store.flow.emit(Unit)
        runCurrent()
        store.flow.emit(Unit)
        runCurrent()

        assertEquals(2, writes)
    }
}
