package de.mm20.launcher2.config.service

import de.mm20.launcher2.config.ConfigDiffer
import de.mm20.launcher2.preferences.config.LauncherConfigSettings
import de.mm20.launcher2.config.ConfigMutation
import de.mm20.launcher2.config.ConfigParser
import de.mm20.launcher2.config.Diagnostic
import de.mm20.launcher2.config.LauncherConfig
import de.mm20.launcher2.config.ReloadTrigger
import de.mm20.launcher2.config.Severity
import de.mm20.launcher2.config.toLauncherConfig
import de.mm20.launcher2.homegrid.HomeGridItem
import de.mm20.launcher2.homegrid.HomeGridLayouts
import de.mm20.launcher2.homegrid.HomeGridWidgets
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.koin.core.context.GlobalContext
import de.mm20.launcher2.preferences.ui.UiSettings
import java.util.UUID
import de.mm20.launcher2.config.ReloadReport
import de.mm20.launcher2.preferences.BuiltInColorSchemes
import de.mm20.launcher2.config.ThemeColors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #3 slice 4: a change made on the device goes back into the keys the file
 * has, compared with what the file produced once applied - on the real store
 * and the real reload, so the baseline is what production records.
 */
@RunWith(RobolectricTestRunner::class)
class ConfigWriteBackTest {

    private lateinit var real: RealConfigStore

    /** Runs once, right after the next settings write has landed and before anything reads after it. */
    private var afterSettingsWrite: (suspend () -> Unit)? = null
    private val context get() = real.context
    private val file: File get() = ConfigLocation.configFile(context)!!
    private lateinit var reportStore: ReloadReportStore
    private lateinit var baselineStore: AppliedBaselineStore
    private val lock = ConfigFileLock()
    private lateinit var reloader: ConfigReloader
    private lateinit var writeBack: ConfigWriteBack

    @Before
    fun setUp() {
        real = RealConfigStore(decorateSettings = { settings ->
            object : LauncherConfigSettings by settings {
                override suspend fun applyAndRead(mutations: List<ConfigMutation>) =
                    settings.applyAndRead(mutations).also { afterSettingsWrite?.let { hook -> afterSettingsWrite = null; hook() } }
            }
        })
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

    /** The L4 write-back scenario's steps 2-4, on the real store: out, reloaded as a no-op, and back. */
    @Test
    fun `a change written back, reloaded, and changed back gives the file it started from`() = runBlocking {
        applied(searchFile)
        onDevice("""{"schemaVersion":2,"search":{"layout":"list"}}""")
        assertTrue(writeBack.write() is WriteBackResult.Written)
        reloader.reload(file)
        onDevice("""{"schemaVersion":2,"icons":{"enforceThemed":true}}""")
        assertEquals(WriteBackResult.Unchanged, writeBack.write())

        onDevice("""{"schemaVersion":2,"search":{"layout":"grid"}}""")
        val result = writeBack.write()

        assertTrue(result.toString(), result is WriteBackResult.Written)
        assertEquals(searchFile, file.readText())
    }

    // ----- appearance.theme (#3 slice 3) -----

    private val themeFile = """
        {
          // zone: home
          "schemaVersion": 2,
          "appearance": { "theme": { "mode": "system", "colors": "system" /* the zone's palette */ } }
        }
    """.trimIndent()

    /** A scheme a person made in the launcher's settings: a random id the file cannot name. */
    private suspend fun ownColorSchemeOnDevice() {
        GlobalContext.get().get<UiSettings>().setColorsId(UUID.fromString("7d7c3a2e-5b1f-4c8e-9a61-2f0b9d4e1c33"))
        withTimeout(10_000) { while (real.store.readState().themeColors != null) delay(20) }
    }

    private fun keptColors(report: ReloadReport?) =
        report?.diagnostics?.any {
            it.code == ConfigWriteBack.SkipCodePrefix + "colors-custom" && it.message.contains("appearance.theme.colors")
        } == true

    @Test
    fun `a theme mode changed on the device is written into the file`() = runBlocking {
        applied(themeFile)
        onDevice("""{"schemaVersion":2,"appearance":{"theme":{"mode":"dark"}}}""")

        val result = writeBack.write()

        assertTrue(result.toString(), result is WriteBackResult.Written)
        assertEquals(themeFile.replace("\"mode\": \"system\"", "\"mode\": \"dark\""), file.readText())
    }

    /**
     * The file keeps what it asked, and the report says why the effect
     * differs: writing `system` for a scheme nobody chose would put a choice
     * into the file that no one made.
     */
    @Test
    fun `a colour scheme a person made leaves colors as written, and the report says why`() = runBlocking {
        applied(themeFile)
        ownColorSchemeOnDevice()

        val result = writeBack.write()

        assertEquals(WriteBackResult.Unchanged, result)
        assertEquals(themeFile, file.readText())
        assertTrue(reportStore.read()?.diagnostics.toString(), keptColors(reportStore.read()))
    }

    @Test
    fun `with a person's own scheme, a mode change is still written, and the report says colors was kept`() = runBlocking {
        applied(themeFile)
        ownColorSchemeOnDevice()
        onDevice("""{"schemaVersion":2,"appearance":{"theme":{"mode":"light"}}}""")

        val result = writeBack.write()

        assertTrue(result.toString(), result is WriteBackResult.Written)
        assertEquals(themeFile.replace("\"mode\": \"system\"", "\"mode\": \"light\""), file.readText())
        assertTrue(reportStore.read()?.diagnostics.toString(), keptColors(reportStore.read()))
    }

    /** The warning describes the device as it is now: back on a built-in scheme, it goes (#183 review). */
    @Test
    fun `back from a person's own scheme to a built-in one, the warning goes`() = runBlocking {
        applied(themeFile)
        ownColorSchemeOnDevice()
        writeBack.write()
        assertTrue(keptColors(reportStore.read()))

        GlobalContext.get().get<UiSettings>().setColorsId(BuiltInColorSchemes.System)
        withTimeout(10_000) { while (real.store.readState().themeColors != ThemeColors.System) delay(20) }
        val result = writeBack.write()

        assertEquals(WriteBackResult.Unchanged, result)
        assertEquals(themeFile, file.readText())
        assertTrue(reportStore.read()?.diagnostics.toString(), !keptColors(reportStore.read()))
    }

    /**
     * Two reasons at once: a skip warning must not evict the colour-scheme
     * warning, which still describes the device (#183 simplify).
     */
    @Test
    fun `a skipped write-back leaves the colour-scheme warning in place`() = runBlocking {
        applied(themeFile)
        ownColorSchemeOnDevice()
        writeBack.write()
        val grid = GridWriteBack(context, real.grid, real.store, reportStore, lock, baselineStore = baselineStore)
        val items = listOf(HomeGridItem(HomeGridLayouts.Phone, "dock", HomeGridWidgets.Favorites, null, 0, 5, 4, 1, position = 0))

        val result = grid.write(HomeGridLayouts.Phone, items)

        assertEquals("grid-unmanaged", (result as WriteBackResult.Skipped).code)
        val codes = reportStore.read()!!.diagnostics.map { it.code }
        assertTrue(codes.toString(), ConfigWriteBack.SkipCodePrefix + "grid-unmanaged" in codes)
        assertTrue(codes.toString(), keptColors(reportStore.read()))
    }

    @Test
    fun `a file without colors says nothing about a person's own scheme`() = runBlocking {
        val modeOnly = themeFile.replace(""", "colors": "system" /* the zone's palette */""", "")
        applied(modeOnly)
        ownColorSchemeOnDevice()

        val result = writeBack.write()

        assertEquals(WriteBackResult.Unchanged, result)
        assertEquals(modeOnly, file.readText())
        assertTrue(reportStore.read()?.diagnostics.toString(), !keptColors(reportStore.read()))
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

    /**
     * Review on #155: the baseline is saved before the report, each on its
     * own. A report that cannot be saved must not leave the baseline behind
     * the file, or every write-back after it skips as not-applied-yet.
     */
    @Test
    fun `a self-write whose report cannot be saved still becomes the baseline`() = runBlocking {
        applied(searchFile)
        onDevice("""{"schemaVersion":2,"search":{"layout":"list"}}""")
        val report = File(context.filesDir, "config/last-reload-report.json")
        report.delete()
        report.mkdirs() // a rename onto a directory fails: the report cannot be saved

        val written = writeBack.write() as WriteBackResult.Written

        assertEquals(written.sha256, baselineStore.read()!!.configSha256)
        onDevice("""{"schemaVersion":2,"search":{"layout":"grid"}}""")
        assertTrue(writeBack.write() is WriteBackResult.Written)
        report.delete()
        Unit
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
        val seen = Channel<Unit>(Channel.UNLIMITED)
        val collecting = launch(Dispatchers.Default) { real.store.changes().collect { seen.send(Unit) } }
        try {
            withTimeout(10_000) { seen.receive() }
            onDevice("""{"schemaVersion":2,"search":{"layout":"list"}}""")
            withTimeout(10_000) { seen.receive() }
        } finally {
            collecting.cancel()
        }
    }

    /**
     * #167: a start is one write-back pass, not one per source. Each source
     * the store follows - settings, favorites, search actions, every grid
     * layout - emits its first value on collection; followed one by one, a
     * start made one full pass (file read, state read, wallpaper hash) for
     * each of them.
     */
    @Test
    fun `a start asks for one write-back, not one per source`() = runBlocking {
        applied(searchFile)
        val seen = Channel<Unit>(Channel.UNLIMITED)
        val collecting = launch(Dispatchers.Default) { real.store.changes().collect { seen.send(Unit) } }
        try {
            withTimeout(10_000) { seen.receive() }
            // Every source has emitted its first value well within this.
            delay(1_000)
            var more = 0
            while (seen.tryReceive().isSuccess) more++
            assertEquals("emissions after the first on start", 0, more)
        } finally {
            collecting.cancel()
        }
    }

    @Test
    fun `a setting changed on the device reaches the file without being asked`() = runBlocking {
        applied(searchFile)
        // Every result, not the last. A Written can be followed within a
        // millisecond by an Unchanged pass - when the trigger still made one
        // pass per source at start (#167), always so - and lastResult, a
        // StateFlow, can drop the Written before a collector sees it. It did
        // under CI load (3 in 40 locally with every core busy); the file was
        // right each time.
        val results = Channel<WriteBackResult>(Channel.UNLIMITED)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            ConfigWriteBackTrigger(real.store, { results.send(writeBack.write()) }, scope).start()
            withTimeout(10_000) { results.receive() }

            onDevice("""{"schemaVersion":2,"search":{"layout":"list"}}""")

            withTimeout(10_000) { while (results.receive() !is WriteBackResult.Written) Unit }
            assertEquals(searchFile.replace("\"layout\": \"grid\"", "\"layout\": \"list\""), file.readText())
        } finally {
            scope.cancel()
        }
    }

    /**
     * The 3a round trip through write-back rather than apply: the complete
     * example, every key off its default, is applied; the device changes a
     * list in each section that has one - favorites reordered, a search
     * action renamed, a grid item moved; the file written back parses clean
     * and says what the device serves, the grid-item options the file wrote
     * out included.
     */
    private suspend fun roundTrip(example: String): Pair<LauncherConfig, LauncherConfig> {
        real.install("com.example.dialer")
        real.install("com.example.work.mail", real.work)
        real.install("com.example.maps")
        applied(example)
        val served = ConfigParser.parse(example).config!!
        onDevice(
            ConfigParser.json.encodeToString(
                LauncherConfig.serializer(),
                served.copy(
                    home = served.home!!.copy(
                        favorites = served.home!!.favorites!!.reversed(),
                        grid = served.home!!.grid!!.copy(
                            layouts = served.home!!.grid!!.layouts!!.mapValues { (_, layout) ->
                                layout.copy(items = layout.items.map { if (it.id == "clock") it.copy(x = 0) else it })
                            },
                        ),
                    ),
                    search = served.search!!.copy(
                        actions = served.search!!.actions!!.map { if (it.label == "Wiki") it.copy(label = "Wikipedia") else it },
                    ),
                ),
            )
        )

        val result = writeBack.write()

        assertTrue(result.toString(), result is WriteBackResult.Written)
        val written = ConfigParser.parse(file.readText())
        assertEquals(emptyList<Diagnostic>(), written.diagnostics)
        return real.store.readState().toLauncherConfig() to written.config!!
    }

    private val completeExample: String
        get() = File(System.getProperty("repoRoot"), "docs/configuration/complete-example.json").readText()

    @Test
    fun `the complete example goes out through write-back and comes back as the device has it`() = runBlocking {
        val unlocked = completeExample.replace("\"locked\": true", "\"locked\": false")
        val (device, written) = roundTrip(unlocked)

        assertEquals(device, written)
    }

    /** The example as it is locks the grid: the clock the device moved stays where the file has it (W2). */
    @Test
    fun `a locked grid comes back as the file has it, everything else as the device has it`() = runBlocking {
        val (device, written) = roundTrip(completeExample)

        assertEquals(device.copy(home = device.home!!.copy(grid = ConfigParser.parse(completeExample).config!!.home!!.grid)), written)
    }

    private val twoSections = """{"schemaVersion":2,"icons":{"themed":false},"search":{"layout":"grid"}}"""

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
            override suspend fun applyAndCapture(mutations: List<ConfigMutation>) =
                real.store.applyAndCapture(mutations).also { onDevice("""{"schemaVersion":2,"icons":{"themed":true}}""") }
        }
        val newer = twoSections.replace("\"grid\"", "\"list\"")
        put(newer)
        ConfigReloader(duringApply, reportStore, lock, baselineStore).reload(file)

        val result = writeBack.write()

        assertTrue(result.toString(), result is WriteBackResult.Written)
        assertEquals(newer.replace("\"themed\":false", "\"themed\":true"), file.readText())
    }

    /**
     * Review on #155: the window after the write. A setting the person
     * changes right after the reload wrote its section, before anything reads
     * the state again, is theirs, not the file's. The baseline is taken from
     * the write itself, so the change is written back.
     */
    @Test
    fun `a setting changed right after the reload wrote its section is written back`() = runBlocking {
        val file = """{"schemaVersion":2,"search":{"layout":"grid","labels":true}}"""
        applied(file)
        val newer = file.replace("\"grid\"", "\"list\"")
        put(newer)
        afterSettingsWrite = {
            real.settings.apply(listOf(ConfigMutation.SetSearch(de.mm20.launcher2.config.SearchConfig(labels = false))))
        }
        reloader.reload(this@ConfigWriteBackTest.file)

        val result = writeBack.write()

        assertTrue(result.toString(), result is WriteBackResult.Written)
        assertEquals(newer.replace("\"labels\":true", "\"labels\":false"), this@ConfigWriteBackTest.file.readText())
    }

    /** The same window for a section in a repository: the grid, moved right after the reload placed it. */
    @Test
    fun `a grid item moved right after the reload placed it is written back`() = runBlocking {
        val file = """{"schemaVersion":2,"home":{"widgets":{"enabled":true},"grid":{"layouts":{"phone":{"items":[{"id":"dock","widget":"favorites","x":0,"y":5,"w":4,"h":1}]}}}}}"""
        applied(file)
        val newer = file.replace("\"y\":5", "\"y\":4")
        put(newer)
        real.grid.afterReplace = { layout ->
            real.grid.replace(layout, real.grid.layouts.getValue(layout).map { it.copy(y = 3) })
        }
        reloader.reload(this@ConfigWriteBackTest.file)

        val result = writeBack.write()

        assertTrue(result.toString(), result is WriteBackResult.Written)
        assertEquals(3, ConfigParser.parse(this@ConfigWriteBackTest.file.readText()).config!!.home!!.grid!!.layouts!!.getValue("phone").items.single().y)
    }

    /**
     * Review on #155: a section whose write failed was not applied, so it
     * stays in the baseline as it was before; the others are as written.
     */
    @Test
    fun `a section whose write failed stays in the baseline as it was before`() = runBlocking {
        val file = """{"schemaVersion":2,"icons":{"themed":false},"search":{"actions":[{"type":"url","label":"Wiki","url":"https://w.example/?q=${'$'}{1}"}]}}"""
        applied(file)
        put(file.replace("\"themed\":false", "\"themed\":true").replace("\"Wiki\"", "\"Wikipedia\""))
        real.actions.failure = IllegalStateException("database locked")
        reloader.reload(this@ConfigWriteBackTest.file)

        val baseline = baselineStore.read()!!.effective

        assertEquals("true", baseline.at(listOf("icons", "themed")).toString())
        assertEquals("\"Wiki\"", (baseline.at(listOf("search", "actions")) as kotlinx.serialization.json.JsonArray)[0].let { (it as kotlinx.serialization.json.JsonObject)["label"].toString() })
    }

    /**
     * The timing half: a write-back asked for while a reload holds the lock
     * waits for it, and then finds that the reload's own changes are no
     * difference - it neither writes the file nor skips.
     */
    @Test
    fun `a write-back asked for during a reload waits for it and finds nothing to write`() = runBlocking {
        applied(twoSections)
        val gate = CompletableDeferred<Unit>()
        val slowApply = object : ConfigStore by real.store {
            override suspend fun apply(mutations: List<ConfigMutation>) =
                gate.await().let { real.store.apply(mutations) }
        }
        val newer = twoSections.replace("\"grid\"", "\"list\"")
        put(newer)

        val reload = launch { ConfigReloader(slowApply, reportStore, lock, baselineStore).reload(file) }
        yield()
        val write = async { writeBack.write() }
        yield()
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
