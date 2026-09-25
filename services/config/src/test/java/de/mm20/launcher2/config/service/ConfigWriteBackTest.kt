package de.mm20.launcher2.config.service

import de.mm20.launcher2.config.ConfigDiffer
import de.mm20.launcher2.config.ConfigParser
import de.mm20.launcher2.config.ReloadTrigger
import de.mm20.launcher2.config.Severity
import de.mm20.launcher2.homegrid.HomeGridItem
import de.mm20.launcher2.homegrid.HomeGridLayouts
import de.mm20.launcher2.homegrid.HomeGridWidgets
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * #3 slice 4: a change made on the device goes back into the keys the file
 * has, compared with what the file produced once applied - on the real store
 * and the real reload, so the baseline is what production records.
 */
@RunWith(RobolectricTestRunner::class)
class ConfigWriteBackTest {

    private lateinit var real: RealConfigStore
    private val context get() = real.context
    private val file: File get() = ConfigLocation.configFile(context)!!
    private lateinit var reportStore: ReloadReportStore
    private lateinit var baselineStore: AppliedBaselineStore
    private val lock = ConfigFileLock()
    private lateinit var reloader: ConfigReloader
    private lateinit var writeBack: ConfigWriteBack

    @Before
    fun setUp() {
        real = RealConfigStore()
        reportStore = ReloadReportStore(context)
        baselineStore = AppliedBaselineStore(context)
        reloader = ConfigReloader(real.store, reportStore, lock, baselineStore)
        writeBack = ConfigWriteBack(context, real.store, reportStore, baselineStore, lock)
        file.delete()
        File(context.filesDir, "config/last-reload-report.json").delete()
        File(context.filesDir, "config/applied-baseline.json").delete()
    }

    @After
    fun tearDown() {
        real.close()
    }

    private fun put(text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    /** Put and reload: the file as a device that has applied it. */
    private suspend fun applied(text: String) {
        put(text)
        reloader.reload(file)
    }

    /** A change made on the device: the settings change, the file does not. */
    private suspend fun onDevice(partial: String) {
        real.store.apply(ConfigDiffer.diff(ConfigParser.parse(partial).config!!, real.store.readState()))
    }

    private val searchFile = """
        {
          // zone: home
          "schemaVersion": 2,
          "search": { "layout": "grid" /* the default look */, "labels": true }
        }
    """.trimIndent()

    @Test
    fun `a setting changed on the device is written into the key the file has`() = runBlocking {
        applied(searchFile)
        onDevice("""{"schemaVersion":2,"search":{"layout":"list"}}""")

        val result = writeBack.write()

        assertTrue(result.toString(), result is WriteBackResult.Written)
        assertEquals(searchFile.replace("\"layout\": \"grid\"", "\"layout\": \"list\""), file.readText())
    }

    @Test
    fun `a key the file leaves out is not added`() = runBlocking {
        applied(searchFile)
        onDevice("""{"schemaVersion":2,"search":{"reversed":true}}""")

        assertEquals(WriteBackResult.Unchanged, writeBack.write())
        assertEquals(searchFile, file.readText())
    }

    @Test
    fun `without a baseline nothing is written, and it says so`() = runBlocking {
        put(searchFile)
        onDevice("""{"schemaVersion":2,"search":{"layout":"list"}}""")

        val result = writeBack.write()

        assertEquals("no-baseline", (result as WriteBackResult.Skipped).code)
        assertEquals(searchFile, file.readText())
    }

    @Test
    fun `a file changed since it was applied is not written`() = runBlocking {
        applied(searchFile)
        val newer = searchFile.replace("\"labels\": true", "\"labels\": false")
        put(newer)
        onDevice("""{"schemaVersion":2,"search":{"layout":"list"}}""")

        val result = writeBack.write()

        assertEquals("not-applied-yet", (result as WriteBackResult.Skipped).code)
        assertEquals(newer, file.readText())
    }

    /** D with the real store: a favorite whose app is not installed was never applied, so it cannot have been removed. */
    @Test
    fun `a favorite the device could not apply keeps its place when the list changes`() = runBlocking {
        real.install("com.example.dialer")
        real.install("com.example.maps")
        applied("""{"schemaVersion":2,"home":{"favorites":["com.example.dialer","org.not.installed"]}}""")
        onDevice("""{"schemaVersion":2,"home":{"favorites":["com.example.dialer","com.example.maps"]}}""")

        val result = writeBack.write()

        assertTrue(result.toString(), result is WriteBackResult.Written)

        assertEquals(
            listOf("com.example.dialer", "org.not.installed", "com.example.maps"),
            ConfigParser.parse(file.readText()).config!!.home!!.favorites!!.map { it.packageName },
        )
    }

    @Test
    fun `the self-write is reported, becomes the baseline, and a second write finds nothing`() = runBlocking {
        applied(searchFile)
        onDevice("""{"schemaVersion":2,"search":{"layout":"list"}}""")

        val written = writeBack.write() as WriteBackResult.Written

        val sha = file.readBytes().sha256Hex()
        assertEquals(sha, written.sha256)
        val report = reportStore.read()!!
        assertEquals(ReloadTrigger.SelfWrite, report.trigger)
        assertEquals(sha, report.configSha256)
        assertEquals(listOf("search.layout"), report.appliedMutations)
        assertEquals(sha, baselineStore.read()!!.configSha256)
        assertEquals(WriteBackResult.Unchanged, writeBack.write())
    }

    @Test
    fun `a skip is a warning on the last reload report, which stays what it was`() = runBlocking {
        applied(searchFile)
        val before = reportStore.read()!!
        put(searchFile.replace("\"labels\": true", "\"labels\": false"))
        onDevice("""{"schemaVersion":2,"search":{"layout":"list"}}""")

        writeBack.write()

        val after = reportStore.read()!!
        assertEquals(before.success, after.success)
        assertEquals(before.configSha256, after.configSha256)
        assertEquals(
            listOf(ConfigWriteBack.SkipCodePrefix + "not-applied-yet"),
            after.diagnostics.filter { it.severity == Severity.Warning }.map { it.code },
        )
    }

    /**
     * B on the real store: its changes arrive once on collection - which is
     * the trigger's first write-back, so a change made before it ran is not
     * lost - and again after a change on the device.
     */
    @Test
    fun `the store's changes arrive on collection and after a change on the device`() = runBlocking {
        applied(searchFile)
        val seen = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val collecting = launch(kotlinx.coroutines.Dispatchers.Default) { real.store.changes().collect { seen.send(Unit) } }
        try {
            kotlinx.coroutines.withTimeout(10_000) { seen.receive() }
            onDevice("""{"schemaVersion":2,"search":{"layout":"list"}}""")
            kotlinx.coroutines.withTimeout(10_000) { seen.receive() }
        } finally {
            collecting.cancel()
        }
    }

    @Test
    fun `a setting changed on the device reaches the file without being asked`() = runBlocking {
        applied(searchFile)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            ConfigWriteBackTrigger(real.store, { writeBack.write() }, scope).start()
            kotlinx.coroutines.withTimeout(10_000) { writeBack.lastResult.first { it != null } }

            onDevice("""{"schemaVersion":2,"search":{"layout":"list"}}""")

            kotlinx.coroutines.withTimeout(10_000) { writeBack.lastResult.first { it is WriteBackResult.Written } }
            assertEquals(searchFile.replace("\"layout\": \"grid\"", "\"layout\": \"list\""), file.readText())
        } finally {
            scope.cancel()
        }
    }

    private val twoSections ="""{"schemaVersion":2,"icons":{"themed":false},"search":{"layout":"grid"}}"""

    /**
     * The comparison half of a device change during a reload: the person
     * switches themed icons on while the reload applies another section. The
     * baseline must not take that in as something the file produced, or the
     * change never reaches the file.
     */
    @Test
    fun `a change made on the device while a reload applies is written back afterwards`() = runBlocking {
        applied(twoSections)
        val duringApply = object : ConfigStore by real.store {
            override suspend fun apply(mutations: List<de.mm20.launcher2.config.ConfigMutation>) =
                real.store.apply(mutations).also { onDevice("""{"schemaVersion":2,"icons":{"themed":true}}""") }
        }
        val newer = twoSections.replace("\"grid\"", "\"list\"")
        put(newer)
        ConfigReloader(duringApply, reportStore, lock, baselineStore).reload(file)

        val result = writeBack.write()

        assertTrue(result.toString(), result is WriteBackResult.Written)
        assertEquals(newer.replace("\"themed\":false", "\"themed\":true"), file.readText())
    }

    /**
     * The timing half: a write-back asked for while a reload holds the lock
     * waits for it, and then finds that the reload's own changes are no
     * difference - it neither writes the file nor skips.
     */
    @Test
    fun `a write-back asked for during a reload waits for it and finds nothing to write`() = runBlocking {
        applied(twoSections)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slowApply = object : ConfigStore by real.store {
            override suspend fun apply(mutations: List<de.mm20.launcher2.config.ConfigMutation>) =
                gate.await().let { real.store.apply(mutations) }
        }
        val newer = twoSections.replace("\"grid\"", "\"list\"")
        put(newer)

        val reload = launch { ConfigReloader(slowApply, reportStore, lock, baselineStore).reload(file) }
        kotlinx.coroutines.yield()
        val write = async { writeBack.write() }
        kotlinx.coroutines.yield()
        assertTrue("the write-back waits for the reload", write.isActive)
        gate.complete(Unit)
        reload.join()

        assertEquals(WriteBackResult.Unchanged, write.await())
        assertEquals(newer, file.readText())
    }

    /**
     * W1, (a): a grid edit on a file that does not manage the grid stays on
     * the device, the file is left byte for byte, and the person is told how
     * to make the file manage it - at the device and in the reload report.
     */
    @Test
    fun `a grid edit on a file without home grid is kept on the device and reported`() = runBlocking {
        val text = """{ "schemaVersion": 2, "home": { "searchBar": { "position": "bottom" } } }"""
        applied(text)
        val grid = GridWriteBack(context, real.grid, real.store, reportStore, lock, baselineStore = baselineStore)
        val items = listOf(HomeGridItem(HomeGridLayouts.Phone, "dock", HomeGridWidgets.Favorites, null, 0, 5, 4, 1, position = 0))

        val result = grid.write(HomeGridLayouts.Phone, items)

        assertEquals("grid-unmanaged", (result as WriteBackResult.Skipped).code)
        assertTrue(result.reason, "\"layouts\": {}" in result.reason)
        assertEquals(items, real.grid.layouts[HomeGridLayouts.Phone])
        assertEquals(text, file.readText())
        assertTrue(reportStore.read()!!.diagnostics.any { it.code == ConfigWriteBack.SkipCodePrefix + "grid-unmanaged" })
    }
}
