package de.mm20.launcher2.config.service

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.UserHandle
import de.mm20.launcher2.applications.AppRepository
import de.mm20.launcher2.config.Diagnostic
import de.mm20.launcher2.config.Severity
import de.mm20.launcher2.config.TagApp
import de.mm20.launcher2.config.TagConfig
import de.mm20.launcher2.config.TagIcon
import de.mm20.launcher2.data.customattrs.CustomAttributesRepository
import de.mm20.launcher2.data.customattrs.CustomIcon
import de.mm20.launcher2.data.customattrs.CustomIconPackIcon
import de.mm20.launcher2.data.customattrs.CustomTextIcon
import de.mm20.launcher2.data.customattrs.ForceThemedIcon
import de.mm20.launcher2.icons.StaticLauncherIcon
import de.mm20.launcher2.profiles.Profile
import de.mm20.launcher2.search.Application
import de.mm20.launcher2.search.SavableSearchable
import de.mm20.launcher2.search.SearchableSerializer
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
 * `tags` on the device (#3 slice 4): which apps carry which tags, a tag's
 * icon, what applying the list does to the installed apps, and what reads
 * back.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidTagStoreTest {

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

    private val newpipe = app("org.schabi.newpipe")
    private val signal = app("org.thoughtcrime.securesms")
    private val twoFirst = app("com.example.two", "com.example.two.First")
    private val twoSecond = app("com.example.two", "com.example.two.Second")
    private val workMail = app("com.example.mail", work = true)
    private val gone = "app://com.gone:com.gone.Main"

    private val installed = MutableStateFlow<List<Application>>(listOf(newpipe, signal, twoFirst, twoSecond, workMail))
    /** Every item's tags, by key: apps, and a contact's, which are the phone's. */
    private val tags = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    private val tagIcons = MutableStateFlow<Map<String, CustomIcon>>(emptyMap())
    private var tagWriteFailure: Exception? = null

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
            // As the DAO reads them: app keys only.
            "getAppTags" -> tags.map { all -> all.filterKeys { it.startsWith("app://") } }
            "getTagIcons" -> tagIcons
            "replaceAppTagsAwaited" -> {
                tagWriteFailure?.let { throw it }
                val items = args[0] as List<SavableSearchable>
                val wanted = args[1] as Map<String, Set<String>>
                tags.value = tags.value.filterKeys { key -> items.none { it.key == key } } + wanted.filterValues { it.isNotEmpty() }
                Unit
            }
            "replaceTagIconsAwaited" -> {
                val named = args[0] as Collection<String>
                val wanted = args[1] as Map<String, CustomIcon>
                tagIcons.value = tagIcons.value.filterKeys { it !in named } + wanted
                Unit
            }
            else -> error("unexpected call $name")
        }
    }

    private val profiles = FakeProfileResolver(
        personal = Profile(Profile.Type.Personal, personal, 0),
        work = Profile(Profile.Type.Work, work, 10),
    )

    /** How the file wrote each tagged app, as the real one keeps it: a map that outlives a store. */
    private val naming = object : AppNaming {
        val written = MutableStateFlow<Map<String, String?>>(emptyMap())
        override fun observe(): Flow<Map<String, String?>> = written
        override suspend fun replace(naming: Map<String, String?>) {
            written.value = naming
        }
        override suspend fun recorded() = true
        override suspend fun awaitRecorded(): Unit = error("not used here")
        override suspend fun forget() {
            written.value = emptyMap()
        }
    }

    /** Lawnicons with two drawables, one of which it cannot theme; no other pack is installed. */
    private val index = object : IconPackIndex {
        override suspend fun resolve(pack: String, drawable: String): IconPackIndex.Resolution {
            if (pack != Lawnicons) return IconPackIndex.Resolution.PackMissing
            if (drawable !in setOf("briefcase", "flat")) return IconPackIndex.Resolution.DrawableMissing
            return IconPackIndex.Resolution.Found("app", drawable, extras = null, themed = drawable != "flat")
        }
        override fun indexed(): Flow<Set<String>> = error("not used here")
    }

    private fun store() = AndroidTagStore(apps, profiles, attributes, naming, index)
    private val store = store()

    private val briefcase = CustomIconPackIcon(Lawnicons, "app", "briefcase", extras = null, allowThemed = true)

    // ---- reading ----

    @Test
    fun `the installed apps' tags read back by tag, with each tag's icon`() = runTest {
        tags.value = mapOf(
            newpipe.key to setOf("Music"),
            signal.key to setOf("Chat", "Music"),
            workMail.key to setOf("Work"),
        )
        tagIcons.value = mapOf("Music" to CustomTextIcon("🎵"), "Work" to briefcase)

        assertEquals(
            listOf(
                TagConfig("Chat", apps = listOf(TagApp("org.thoughtcrime.securesms"))),
                TagConfig(
                    "Music", icon = TagIcon.Text("🎵"),
                    apps = listOf(TagApp("org.schabi.newpipe"), TagApp("org.thoughtcrime.securesms")),
                ),
                TagConfig(
                    "Work", icon = TagIcon.Pack(Lawnicons, "briefcase"),
                    apps = listOf(TagApp("com.example.mail", profile = ConfigProfile.Work)),
                ),
            ),
            store.read(),
        )
    }

    /**
     * A tag carried only by an uninstalled app, or only by a contact, is not
     * the apps' state: the file neither lists nor removes it.
     */
    @Test
    fun `a tag no installed app carries does not read back`() = runTest {
        tags.value = mapOf(gone to setOf("Old"), "contact://1" to setOf("Friends"), signal.key to setOf("Chat"))
        tagIcons.value = mapOf("Old" to CustomTextIcon("x"), "Friends" to CustomTextIcon("y"))

        assertEquals(listOf(TagConfig("Chat", apps = listOf(TagApp("org.thoughtcrime.securesms")))), store.read())
    }

    @Test
    fun `a second launcher entry of a package reads back with its activity`() = runTest {
        tags.value = mapOf(twoFirst.key to setOf("Two"), twoSecond.key to setOf("Two"))

        assertEquals(
            listOf(
                TagConfig(
                    "Two",
                    apps = listOf(TagApp("com.example.two"), TagApp("com.example.two", activity = "com.example.two.Second")),
                ),
            ),
            store.read(),
        )
    }

    /** What the picker's emoji tab and pack tab set; anything else a tag cannot have in the file. */
    @Test
    fun `a tag's icon reads back in the file's forms, and one the file cannot name reads as none`() = runTest {
        tags.value = mapOf(signal.key to setOf("Text", "Pack", "Flat", "Coloured", "Other"))
        tagIcons.value = mapOf(
            "Text" to CustomTextIcon("AB"),
            "Pack" to briefcase,
            // The pack cannot theme it: stored unthemed whatever the file said.
            "Flat" to CustomIconPackIcon(Lawnicons, "app", "flat", extras = null, allowThemed = false),
            // Nothing the launcher writes: the picker's text icon is colour 0.
            "Coloured" to CustomTextIcon("C", color = 5),
            "Other" to ForceThemedIcon,
        )

        assertEquals(
            mapOf(
                "Coloured" to null,
                "Flat" to TagIcon.Pack(Lawnicons, "flat"),
                "Other" to null,
                "Pack" to TagIcon.Pack(Lawnicons, "briefcase"),
                "Text" to TagIcon.Text("AB"),
            ),
            store.read().associate { it.name to it.icon },
        )
    }

    // ---- applying: the whole state ----

    @Test
    fun `applying tags the named apps and untags every other installed one`() = runTest {
        tags.value = mapOf(
            twoFirst.key to setOf("Old"),
            signal.key to setOf("Old"),
            gone to setOf("Old"),
            "contact://1" to setOf("Music"),
        )

        val (diagnostics, written) = store.replaceAndRead(
            listOf(
                TagConfig("Music", apps = listOf(TagApp("org.schabi.newpipe"), TagApp("org.thoughtcrime.securesms"))),
                TagConfig("Chat", apps = listOf(TagApp("org.thoughtcrime.securesms"))),
            ),
        )

        assertEquals(emptyList<Diagnostic>(), diagnostics)
        assertEquals(
            "an uninstalled app's and a contact's tags are left alone",
            mapOf(
                newpipe.key to setOf("Music"),
                signal.key to setOf("Chat", "Music"),
                gone to setOf("Old"),
                "contact://1" to setOf("Music"),
            ),
            tags.value,
        )
        assertEquals(listOf("Chat", "Music"), written.map { it.name })
    }

    @Test
    fun `an empty list untags every installed app`() = runTest {
        tags.value = mapOf(signal.key to setOf("Chat"), workMail.key to setOf("Work"))

        val (_, written) = store.replaceAndRead(emptyList())

        assertEquals(emptyMap<String, Set<String>>(), tags.value)
        assertEquals(emptyList<TagConfig>(), written)
    }

    @Test
    fun `an activity and a profile pick that app`() = runTest {
        store.replaceAndRead(
            listOf(
                TagConfig(
                    "T",
                    apps = listOf(
                        TagApp("com.example.two", activity = "com.example.two.Second"),
                        TagApp("com.example.mail", profile = ConfigProfile.Work),
                    ),
                ),
            ),
        )

        assertEquals(mapOf(twoSecond.key to setOf("T"), workMail.key to setOf("T")), tags.value)
    }

    /** Reported, not an error: the file keeps the entry, and the rest applies. */
    @Test
    fun `an app that is not installed is reported and the rest applies`() = runTest {
        val (diagnostics, _) = store.replaceAndRead(
            listOf(
                TagConfig("A", apps = listOf(TagApp("com.not.installed"), TagApp("org.thoughtcrime.securesms"))),
                TagConfig("B", apps = listOf(TagApp("com.example.two", activity = "com.example.two.Missing"))),
            ),
        )

        assertEquals(listOf("tags[0].apps[0]", "tags[1].apps[0]"), diagnostics.map { it.path })
        assertTrue(diagnostics.all { it.code == "app-unavailable" && it.severity == Severity.Warning })
        assertEquals(mapOf(signal.key to setOf("A")), tags.value)
    }

    @Test
    fun `an entry for a profile the device lacks is reported`() = runTest {
        profiles.work = null

        val (diagnostics, _) = store.replaceAndRead(
            listOf(TagConfig("W", apps = listOf(TagApp("com.example.mail", profile = ConfigProfile.Work)))),
        )

        assertEquals(listOf("tags[0].apps[0]" to "profile-unavailable"), diagnostics.map { it.path to it.code })
    }

    /** Within one tag, the first entry and its activity spelled out are the same app; only the device can tell. */
    @Test
    fun `the same app twice in one tag is reported, and in two tags is not`() = runTest {
        val (diagnostics, _) = store.replaceAndRead(
            listOf(
                TagConfig(
                    "A",
                    apps = listOf(TagApp("com.example.two"), TagApp("com.example.two", activity = "com.example.two.First")),
                ),
                TagConfig("B", apps = listOf(TagApp("com.example.two"))),
            ),
        )

        assertEquals(listOf("tags[0].apps[1]" to "duplicate-app-on-device"), diagnostics.map { it.path to it.code })
        assertTrue(diagnostics.all { it.severity == Severity.Warning })
        assertEquals(mapOf(twoFirst.key to setOf("A", "B")), tags.value)
    }

    // ---- applying: icons ----

    @Test
    fun `a tag's icon is written, a pack icon completed from the pack's index`() = runTest {
        store.replaceAndRead(
            listOf(
                TagConfig("Music", icon = TagIcon.Text("🎵"), apps = listOf(TagApp("org.schabi.newpipe"))),
                TagConfig("Work", icon = TagIcon.Pack(Lawnicons, "briefcase", themed = false), apps = listOf(TagApp("org.thoughtcrime.securesms"))),
            ),
        )

        assertEquals(
            mapOf("Music" to CustomTextIcon("🎵"), "Work" to briefcase.copy(allowThemed = false)),
            tagIcons.value,
        )
        assertEquals(TagIcon.Pack(Lawnicons, "briefcase", themed = false), store.read().single { it.name == "Work" }.icon)
    }

    /** A listed tag without an icon loses its own; a tag the file does not list keeps its icon (its contacts may carry it). */
    @Test
    fun `a listed tag without an icon loses it, an unlisted tag keeps its own`() = runTest {
        tagIcons.value = mapOf("Listed" to CustomTextIcon("L"), "Unlisted" to CustomTextIcon("U"))

        store.replaceAndRead(listOf(TagConfig("Listed", apps = listOf(TagApp("org.schabi.newpipe")))))

        assertEquals(mapOf("Unlisted" to CustomTextIcon("U")), tagIcons.value)
    }

    @Test
    fun `a pack that is not installed is reported, and the tag shows its own icon`() = runTest {
        tagIcons.value = mapOf("Work" to CustomTextIcon("W"))

        val (diagnostics, _) = store.replaceAndRead(
            listOf(
                TagConfig("Work", icon = TagIcon.Pack("com.not.a.pack", "briefcase"), apps = listOf(TagApp("org.schabi.newpipe"))),
                TagConfig("Other", icon = TagIcon.Pack(Lawnicons, "nope"), apps = listOf(TagApp("org.schabi.newpipe"))),
            ),
        )

        assertEquals(
            listOf("tags[0].icon" to "icon-pack-unavailable", "tags[1].icon" to "icon-pack-unavailable"),
            diagnostics.map { it.path to it.code },
        )
        assertTrue(diagnostics.all { it.severity == Severity.Warning })
        assertEquals(emptyMap<String, CustomIcon>(), tagIcons.value)
        assertEquals(mapOf(newpipe.key to setOf("Other", "Work")), tags.value)
    }

    // ---- the form the file wrote ----

    @Test
    fun `an entry that spells out the first activity reads back with it`() = runTest {
        val entry = TagApp("com.example.two", activity = "com.example.two.First")

        val (_, written) = store.replaceAndRead(listOf(TagConfig("T", apps = listOf(entry))))

        assertEquals(listOf(TagConfig("T", apps = listOf(entry))), written)
    }

    /** The phone tags another app; the one the file wrote keeps its form, across a new store too. */
    @Test
    fun `a change on the phone keeps the activity the file wrote`() = runTest {
        val entry = TagApp("com.example.two", activity = "com.example.two.First")
        store.replaceAndRead(listOf(TagConfig("T", apps = listOf(entry))))

        tags.value = tags.value + (signal.key to setOf("T"))

        assertEquals(
            listOf(TagConfig("T", apps = listOf(entry, TagApp("org.thoughtcrime.securesms")))),
            store().read(),
        )
    }

    /** The form is per tag: the same app written two ways in two tags reads back each way. */
    @Test
    fun `the same app written two ways in two tags reads back each way`() = runTest {
        val spelled = TagApp("com.example.two", activity = "com.example.two.First")
        val plain = TagApp("com.example.two")

        val (_, written) = store.replaceAndRead(
            listOf(TagConfig("A", apps = listOf(spelled)), TagConfig("B", apps = listOf(plain))),
        )

        assertEquals(
            listOf(TagConfig("A", apps = listOf(spelled)), TagConfig("B", apps = listOf(plain))),
            written,
        )
    }

    @Test
    fun `a failed device write puts the recorded form back`() = runTest {
        val entry = TagApp("com.example.two", activity = "com.example.two.First")
        store.replaceAndRead(listOf(TagConfig("T", apps = listOf(entry))))
        val before = naming.written.value
        tagWriteFailure = IllegalStateException("disk full")

        val failure = runCatching {
            store.replaceAndRead(listOf(TagConfig("T", apps = listOf(TagApp("com.example.two")))))
        }.exceptionOrNull()

        assertEquals("disk full", failure?.message)
        assertEquals(before, naming.written.value)
    }

    // ---- changes ----

    @Test
    fun `changes follow the tags, not every install`() = runTest {
        val seen = mutableListOf<Unit>()
        val job = launch { store.changes().take(2).toList(seen) }
        testScheduler.runCurrent()

        installed.value = installed.value + app("com.example.new")
        testScheduler.runCurrent()
        assertEquals("an install that changes no tag is no change", 1, seen.size)

        tags.value = mapOf(signal.key to setOf("Chat"))
        testScheduler.runCurrent()
        assertEquals(2, seen.size)
        job.join()
    }

    private companion object {
        const val Lawnicons = "app.lawnchair.lawnicons"
    }
}
