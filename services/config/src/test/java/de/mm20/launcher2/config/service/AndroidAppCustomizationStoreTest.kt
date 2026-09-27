package de.mm20.launcher2.config.service

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.UserHandle
import de.mm20.launcher2.applications.AppRepository
import de.mm20.launcher2.config.AppConfig
import de.mm20.launcher2.config.AppVisibility
import de.mm20.launcher2.config.Diagnostic
import de.mm20.launcher2.config.Severity
import de.mm20.launcher2.data.customattrs.CustomAttributesRepository
import de.mm20.launcher2.icons.StaticLauncherIcon
import de.mm20.launcher2.profiles.Profile
import de.mm20.launcher2.search.Application
import de.mm20.launcher2.search.SavableSearchable
import de.mm20.launcher2.search.SearchableSerializer
import de.mm20.launcher2.searchable.PinnedLevel
import de.mm20.launcher2.searchable.SavableSearchableRepository
import de.mm20.launcher2.searchable.VisibilityLevel
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy
import de.mm20.launcher2.config.Profile as ConfigProfile

/**
 * `apps` on the device (#3 slice 4): which app an entry means, what applying
 * the list does to the installed apps, and what reads back.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidAppCustomizationStoreTest {

    private val personal = TestUsers.userHandleFor(0)
    private val work = TestUsers.userHandleFor(10)

    /** Keyed as the launcher keys an app: package, activity, and the user outside the main profile. */
    private class App(
        override val componentName: ComponentName,
        override val user: UserHandle,
        serial: Int,
    ) : Application {
        override val key = "app://${componentName.packageName}:${componentName.className}" + if (serial == 0) "" else ":$serial"
        override val domain = "app"
        override val label = componentName.packageName
        override val isSuspended = false
        override val versionName: String? = null
        override val canUninstall = false
        override val canShareApk = false
        override fun overrideLabel(label: String): SavableSearchable = this
        override fun launch(context: Context, options: Bundle?) = false
        override fun getPlaceholderIcon(context: Context): StaticLauncherIcon = throw NotImplementedError()
        override fun getSerializer(): SearchableSerializer = throw NotImplementedError()
        override fun uninstall(context: Context) = throw NotImplementedError()
        override fun openAppDetails(context: Context) = throw NotImplementedError()
    }

    private fun app(pkg: String, cls: String = "$pkg.Main", work: Boolean = false) =
        App(ComponentName(pkg, cls), if (work) this.work else personal, if (work) 10 else 0)

    private val signal = app("org.thoughtcrime.securesms")
    private val stk = app("com.android.stk")
    private val twoFirst = app("com.example.two", "com.example.two.First")
    private val twoSecond = app("com.example.two", "com.example.two.Second")
    private val workMail = app("com.example.mail", work = true)

    private val installed = MutableStateFlow<List<Application>>(listOf(signal, stk, twoFirst, twoSecond, workMail))
    private val labels = MutableStateFlow<Map<String, String>>(emptyMap())
    private val levels = MutableStateFlow<Map<String, VisibilityLevel>>(emptyMap())
    private val visibilityWrites = mutableListOf<Map<String, VisibilityLevel>>()
    private var labelWriteFailure: Exception? = null

    private inline fun <reified T> stub(crossinline answer: (name: String, args: Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "stub ${T::class.java.simpleName}"
                else -> answer(method.name, args ?: emptyArray())
            }
        } as T

    private val apps = object : AppRepository {
        override fun findOne(packageName: String, user: UserHandle) =
            installed.map { list -> list.firstOrNull { it.componentName.packageName == packageName && it.user == user } }
        override fun findMany() = installed.map { it.toImmutableList() }
        override fun search(query: String): Flow<List<Application>> = throw NotImplementedError()
    }

    @Suppress("UNCHECKED_CAST")
    private val attributes = stub<CustomAttributesRepository> { name, args ->
        when (name) {
            "getAppLabels" -> labels
            "replaceCustomLabelsAwaited" -> {
                labelWriteFailure?.let { throw it }
                val items = args[0] as List<SavableSearchable>
                val wanted = args[1] as Map<String, String>
                labels.value = labels.value.filterKeys { key -> items.none { it.key == key } } + wanted
                Unit
            }
            else -> error("unexpected call $name")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private val searchables = stub<SavableSearchableRepository> { name, args ->
        when (name) {
            // getKeys(includeTypes, excludeTypes, minPinnedLevel, maxPinnedLevel, minVisibility, maxVisibility, limit)
            "getKeys" -> {
                val level = args[4] as VisibilityLevel
                check(level == args[5]) { "one level at a time" }
                levels.map { map -> map.filterValues { it == level }.keys.toList() }
            }
            "setVisibilitiesAwaited" -> {
                val wanted = (args[0] as Map<SavableSearchable, VisibilityLevel>).mapKeys { it.key.key }
                visibilityWrites += wanted
                levels.value = levels.value + wanted
                Unit
            }
            else -> error("unexpected call $name")
        }
    }

    private val profiles = FakeProfileResolver(
        personal = Profile(Profile.Type.Personal, personal, 0),
        work = Profile(Profile.Type.Work, work, 10),
    )

    /** How the file wrote each app, as the real one keeps it: a map that outlives a store. */
    private val naming = object : AppNaming {
        val written = MutableStateFlow<Map<String, String?>>(emptyMap())
        override fun observe(): Flow<Map<String, String?>> = written
        override suspend fun replace(naming: Map<String, String?>) {
            written.value = naming
        }
        override suspend fun recorded() = true
    }

    private val store = AndroidAppCustomizationStore(apps, profiles, attributes, searchables, naming)

    // ---- reading ----

    @Test
    fun `labels and visibility read back as entries named by package and profile`() = runTest {
        labels.value = mapOf(signal.key to "Chat", workMail.key to "Work mail")
        levels.value = mapOf(stk.key to VisibilityLevel.Hidden, workMail.key to VisibilityLevel.SearchOnly)

        assertEquals(
            listOf(
                AppConfig("com.android.stk", visibility = AppVisibility.Hidden),
                AppConfig("org.thoughtcrime.securesms", label = "Chat"),
                AppConfig("com.example.mail", profile = ConfigProfile.Work, label = "Work mail", visibility = AppVisibility.SearchOnly),
            ),
            store.read(),
        )
    }

    /** A package's first entry is the one an entry without an activity means; a second one names its activity. */
    @Test
    fun `a second launcher entry of a package reads back with its activity`() = runTest {
        labels.value = mapOf(twoFirst.key to "One", twoSecond.key to "Two")

        assertEquals(
            listOf(
                AppConfig("com.example.two", label = "One"),
                AppConfig("com.example.two", activity = "com.example.two.Second", label = "Two"),
            ),
            store.read(),
        )
    }

    /** A label left by an uninstalled app stays in the database (it comes back with the app) but is not the phone's state. */
    @Test
    fun `an uninstalled app's leftovers do not read back`() = runTest {
        labels.value = mapOf("app://com.gone:com.gone.Main" to "Gone", signal.key to "Chat")
        levels.value = mapOf("app://com.gone:com.gone.Main" to VisibilityLevel.Hidden)

        assertEquals(listOf(AppConfig("org.thoughtcrime.securesms", label = "Chat")), store.read())
    }

    // ---- applying: the whole state ----

    @Test
    fun `applying sets the named apps and clears every other installed one`() = runTest {
        labels.value = mapOf(stk.key to "Old", workMail.key to "Old mail")
        levels.value = mapOf(twoFirst.key to VisibilityLevel.Hidden)

        val (diagnostics, written) = store.replaceAndRead(
            listOf(
                AppConfig("org.thoughtcrime.securesms", label = "Chat"),
                AppConfig("com.android.stk", visibility = AppVisibility.Hidden),
            )
        )

        assertEquals(emptyList<Any>(), diagnostics)
        assertEquals(mapOf(signal.key to "Chat"), labels.value)
        assertEquals(VisibilityLevel.Hidden, levels.value[stk.key])
        assertEquals("an unnamed app is shown normally again", VisibilityLevel.Default, levels.value[twoFirst.key])
        assertEquals(
            listOf(
                AppConfig("com.android.stk", visibility = AppVisibility.Hidden),
                AppConfig("org.thoughtcrime.securesms", label = "Chat"),
            ),
            written,
        )
    }

    /** A key an entry leaves out is its default: a label alone makes a hidden app visible again. */
    @Test
    fun `an entry without a visibility makes the app visible`() = runTest {
        levels.value = mapOf(signal.key to VisibilityLevel.Hidden)

        store.replaceAndRead(listOf(AppConfig("org.thoughtcrime.securesms", label = "Chat")))

        assertEquals(VisibilityLevel.Default, levels.value[signal.key])
    }

    /** Only what differs is written: an app shown normally that stays so gets no row. */
    @Test
    fun `apps that stay shown normally are not written`() = runTest {
        store.replaceAndRead(listOf(AppConfig("com.android.stk", visibility = AppVisibility.Hidden)))

        assertEquals(listOf(mapOf(stk.key to VisibilityLevel.Hidden)), visibilityWrites)
    }

    @Test
    fun `an activity picks that entry of the package`() = runTest {
        store.replaceAndRead(listOf(AppConfig("com.example.two", activity = "com.example.two.Second", label = "Two")))

        assertEquals(mapOf(twoSecond.key to "Two"), labels.value)
    }

    @Test
    fun `the work profile's copy is the work profile's`() = runTest {
        store.replaceAndRead(listOf(AppConfig("com.example.mail", profile = ConfigProfile.Work, label = "Mail")))

        assertEquals(mapOf(workMail.key to "Mail"), labels.value)
    }

    /** Reported, not an error: the file keeps its entry, and the rest applies. */
    @Test
    fun `an app that is not installed is reported and the rest applies`() = runTest {
        val (diagnostics, _) = store.replaceAndRead(
            listOf(
                AppConfig("com.not.installed", label = "Nope"),
                AppConfig("com.example.two", activity = "com.example.two.Missing", label = "Nope"),
                AppConfig("org.thoughtcrime.securesms", label = "Chat"),
            )
        )

        assertEquals(listOf("apps[0]", "apps[1]"), diagnostics.map { it.path })
        assertTrue(diagnostics.all { it.code == "app-unavailable" && it.severity == Severity.Warning })
        assertEquals(mapOf(signal.key to "Chat"), labels.value)
    }

    /**
     * Without an activity an entry means the package's first launcher entry,
     * so naming that activity as well is the same app twice - which only the
     * device can tell. The first entry applies and the second is reported,
     * never a silent last-one-wins (review on #207).
     */
    @Test
    fun `two entries that are the same app here are reported and the first applies`() = runTest {
        val (diagnostics, _) = store.replaceAndRead(
            listOf(
                AppConfig("com.example.two", label = "First"),
                AppConfig("com.example.two", activity = "com.example.two.First", label = "Again", visibility = AppVisibility.Hidden),
            )
        )

        assertEquals(listOf("apps[1]"), diagnostics.map { it.path })
        assertTrue(diagnostics.all { it.code == "duplicate-app" && it.severity == Severity.Warning })
        assertEquals(mapOf(twoFirst.key to "First"), labels.value)
        // The second entry's hidden did not apply either.
        assertEquals(emptyMap<String, VisibilityLevel>(), levels.value)
    }

    /** Control: the package's other activity is another app. */
    @Test
    fun `the package's other activity is not a duplicate`() = runTest {
        val (diagnostics, _) = store.replaceAndRead(
            listOf(
                AppConfig("com.example.two", label = "First"),
                AppConfig("com.example.two", activity = "com.example.two.Second", label = "Second"),
            )
        )

        assertEquals(emptyList<Diagnostic>(), diagnostics)
        assertEquals(mapOf(twoFirst.key to "First", twoSecond.key to "Second"), labels.value)
    }

    @Test
    fun `an entry for a profile the device lacks is reported`() = runTest {
        profiles.work = null

        val (diagnostics, _) = store.replaceAndRead(listOf(AppConfig("com.example.mail", profile = ConfigProfile.Work, label = "Mail")))

        assertEquals(listOf("profile-unavailable"), diagnostics.map { it.code })
    }

    /** The sharp edge, on the device: an empty list clears every installed app's customization. */
    @Test
    fun `an empty list clears every installed app`() = runTest {
        labels.value = mapOf(signal.key to "Chat")
        levels.value = mapOf(stk.key to VisibilityLevel.Hidden)

        val (_, written) = store.replaceAndRead(emptyList())

        assertEquals(emptyMap<String, String>(), labels.value)
        assertEquals(VisibilityLevel.Default, levels.value[stk.key])
        assertEquals(emptyList<AppConfig>(), written)
    }

    // ---- how the file wrote an app (review on #207, WriteBackPlan thread) ----

    /**
     * The file may spell out a package's first activity, which an entry could
     * also leave out. The store reads the app back as the file wrote it, so
     * the baseline, the device and the file all have one form and write-back
     * pairs them: read in the other form, a rename on the device came back as
     * a second entry next to the stale one.
     */
    @Test
    fun `an entry that spells out the first activity reads back with it`() = runTest {
        val (_, written) = store.replaceAndRead(
            listOf(AppConfig("com.example.two", activity = "com.example.two.First", label = "One")),
        )

        assertEquals(listOf(AppConfig("com.example.two", activity = "com.example.two.First", label = "One")), written)
    }

    /** A rename made on the phone later keeps the form, which is what write-back pairs on. */
    @Test
    fun `a rename on the phone keeps the activity the file wrote`() = runTest {
        store.replaceAndRead(listOf(AppConfig("com.example.two", activity = "com.example.two.First", label = "One")))

        labels.value = mapOf(twoFirst.key to "Uno")

        assertEquals(listOf(AppConfig("com.example.two", activity = "com.example.two.First", label = "Uno")), store.read())
    }

    /** Write-back can run after a restart: the form is kept, not held in memory. */
    @Test
    fun `the form survives a new store`() = runTest {
        store.replaceAndRead(listOf(AppConfig("com.example.two", activity = "com.example.two.First", label = "One")))

        val restarted = AndroidAppCustomizationStore(apps, profiles, attributes, searchables, naming)

        assertEquals(listOf(AppConfig("com.example.two", activity = "com.example.two.First", label = "One")), restarted.read())
    }

    /** Controls: what the file left out stays out, and an app the file never named reads back by the rule. */
    @Test
    fun `an entry without the activity, and an app only the phone customized, read back without it`() = runTest {
        store.replaceAndRead(listOf(AppConfig("com.example.two", label = "One")))
        labels.value = labels.value + (signal.key to "Chat")

        assertEquals(
            listOf(
                AppConfig("com.example.two", label = "One"),
                AppConfig("org.thoughtcrime.securesms", label = "Chat"),
            ),
            store.read(),
        )
    }

    /** `[]` names no app, so nothing is remembered from the list before. */
    @Test
    fun `an empty list forgets how the list before wrote its apps`() = runTest {
        store.replaceAndRead(listOf(AppConfig("com.example.two", activity = "com.example.two.First", label = "One")))
        store.replaceAndRead(emptyList())

        labels.value = mapOf(twoFirst.key to "Uno")

        assertEquals(listOf(AppConfig("com.example.two", label = "Uno")), store.read())
    }

    /**
     * The form is recorded before the device writes, because the label write
     * sets off a change that must read the new form. If a device write fails,
     * the apply is reported failed and the baseline stays as it was, so the
     * record goes back to what it was too (review on #214): a record of a form
     * the apply never established would pair write-back wrongly.
     */
    @Test
    fun `a failed device write puts the recorded form back`() = runTest {
        store.replaceAndRead(listOf(AppConfig("com.example.two", activity = "com.example.two.First", label = "One")))
        labelWriteFailure = IllegalStateException("database locked")

        val failed = runCatching { store.replaceAndRead(listOf(AppConfig("com.example.two", label = "Two"))) }

        assertTrue(failed.isFailure)
        assertEquals(mapOf(twoFirst.key to "com.example.two.First"), naming.written.value)
    }

    // ---- changes ----

    /** It tells write-back when what the file would say changes, and not when an app without a name is installed. */
    @Test
    fun `changes follow the customizations, not every install`() = runTest {
        val seen = mutableListOf<Unit>()
        val collecting = launch { store.changes().take(2).toList(seen) }
        testScheduler.runCurrent()
        assertEquals("the current state on collection", 1, seen.size)

        installed.value = installed.value + app("com.example.new")
        testScheduler.runCurrent()
        assertEquals("an install with nothing to say", 1, seen.size)

        labels.value = mapOf(signal.key to "Chat")
        testScheduler.runCurrent()
        assertEquals("a rename", 2, seen.size)
        collecting.cancel()
    }
}
