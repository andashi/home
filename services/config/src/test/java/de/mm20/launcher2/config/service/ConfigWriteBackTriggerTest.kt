package de.mm20.launcher2.config.service

import de.mm20.launcher2.config.ConfigMutation
import de.mm20.launcher2.config.ConfigState
import de.mm20.launcher2.config.Diagnostic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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

    /**
     * Records like FileAppNaming: an empty record is the same value as no
     * record to an observer, so only [recorded] and [awaitRecorded] tell them
     * apart - which is what the trigger must rely on.
     */
    private class Naming(recorded: Boolean) : AppNaming {
        private val state = MutableStateFlow(recorded)
        override fun observe(): Flow<Map<String, String?>> = MutableStateFlow(emptyMap())
        override suspend fun replace(naming: Map<String, String?>) { state.value = true }
        override suspend fun recorded() = state.value
        override suspend fun awaitRecorded() { state.first { it } }
        override suspend fun forget() { state.value = false }
    }

    /**
     * Review on #214: the write-back held back until the apps' form is
     * recorded must run once it is. The startup check records after its
     * reload, and a reload that changed nothing leaves the store silent.
     */
    @Test
    fun `the first record asks for the write-back it held back`() = runTest(StandardTestDispatcher()) {
        val store = Changes()
        val naming = Naming(recorded = false)
        var writes = 0
        ConfigWriteBackTrigger(store, { writes++ }, backgroundScope, namings = listOf(naming)).also { it.start(); runCurrent() }
        store.flow.emit(Unit)
        runCurrent()
        assertEquals("the held-back one", 1, writes)

        naming.replace(emptyMap())
        runCurrent()

        assertEquals(2, writes)
    }

    /** The tags' record, made after the apps' was already there, asks too (review on #224). */
    @Test
    fun `the tags' record asks for the write-back it held back`() = runTest(StandardTestDispatcher()) {
        val store = Changes()
        val tags = Naming(recorded = false)
        var writes = 0
        ConfigWriteBackTrigger(store, { writes++ }, backgroundScope, namings = listOf(Naming(recorded = true), tags))
            .also { it.start(); runCurrent() }
        store.flow.emit(Unit)
        runCurrent()
        assertEquals(1, writes)

        tags.replace(emptyMap())
        runCurrent()

        assertEquals(2, writes)
    }

    /** Control: with a record from an earlier start, no extra pass per cold start (#167). */
    @Test
    fun `an existing record asks for nothing more`() = runTest(StandardTestDispatcher()) {
        val store = Changes()
        val naming = Naming(recorded = true)
        var writes = 0
        ConfigWriteBackTrigger(store, { writes++ }, backgroundScope, namings = listOf(naming)).also { it.start(); runCurrent() }

        store.flow.emit(Unit)
        runCurrent()
        naming.replace(emptyMap())
        runCurrent()

        assertEquals(1, writes)
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
