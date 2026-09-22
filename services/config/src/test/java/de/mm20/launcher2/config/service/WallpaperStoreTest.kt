package de.mm20.launcher2.config.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.config.Diagnostic
import de.mm20.launcher2.config.WallpaperTarget
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class WallpaperStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FakeApplier : WallpaperApplier {
        var ids = WallpaperIds(system = 1, lock = 1)
        /** Simulates WallpaperManagerService: crops only while the profile is foreground. */
        var foreground = true
        var rendered = false
        val applied = mutableListOf<Pair<File, WallpaperTarget>>()
        override fun currentIds() = ids
        override fun isRendered(target: WallpaperTarget) = rendered
        override fun apply(file: File, target: WallpaperTarget): WallpaperIds {
            applied += file to target
            rendered = foreground
            ids = WallpaperIds(
                system = if (target != WallpaperTarget.Lock) ids.system + 1 else ids.system,
                lock = if (target != WallpaperTarget.Home) ids.lock + 1 else ids.lock,
            )
            return ids
        }
    }

    private lateinit var applier: FakeApplier
    private lateinit var store: DefaultWallpaperStore
    private lateinit var dir: File

    /** Drives the re-apply cooldown; the tests move it by hand. */
    private var elapsed = 0L

    /**
     * Whether anyone can see the result (#37), which is a different question
     * from [FakeApplier.foreground]: that one says whether the *system* crops,
     * this one whether the store even asks it to. Visible by default, so the
     * tests written before the deferral keep meaning what they meant.
     */
    private lateinit var visible: ForegroundState

    @Before
    fun setup() {
        applier = FakeApplier()
        elapsed = 0L
        visible = ForegroundState().apply { onResumed() }
        store = DefaultWallpaperStore(context, applier, visible) { elapsed }
        dir = ConfigLocation.wallpapersDir(context)!!.apply { mkdirs() }
        File(context.filesDir, "config/wallpaper-state.json").delete()
        File(dir, "home.jpg").writeBytes(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `nothing applied yet reads as no managed wallpaper`() = runTest {
        assertNull(store.current())
    }

    @Test
    fun `apply sets the wallpaper and current reports it while in sync`() = runTest {
        val diagnostics = store.apply("home.jpg", WallpaperTarget.Both)

        assertEquals(emptyList<Any>(), diagnostics)
        assertEquals(listOf(File(dir, "home.jpg") to WallpaperTarget.Both), applier.applied)
        assertEquals(WallpaperState("home.jpg", WallpaperTarget.Both), store.current())
    }

    @Test
    fun `a set from the background is stored, warned about, and re-applied on foreground`() = runTest {
        applier.foreground = false

        val diagnostics = store.apply("home.jpg", WallpaperTarget.Both)

        assertEquals(listOf("wallpaper-pending-foreground"), diagnostics.map { it.code })
        assertEquals(WallpaperState("home.jpg", WallpaperTarget.Both), store.current())
        assertEquals(1, applier.applied.size)

        applier.foreground = true
        assertEquals(true, store.ensureRendered())
        assertEquals(2, applier.applied.size)
        assertEquals(WallpaperState("home.jpg", WallpaperTarget.Both), store.current())

        assertEquals(false, store.ensureRendered())
        assertEquals(2, applier.applied.size)
    }

    @Test
    fun `ensureRendered leaves a wallpaper the user changed by hand alone`() = runTest {
        applier.foreground = false
        store.apply("home.jpg", WallpaperTarget.Both)
        applier.ids = applier.ids.copy(system = applier.ids.system + 1) // user picked another wallpaper
        applier.foreground = true

        assertEquals(false, store.ensureRendered())
        assertEquals(1, applier.applied.size)
    }

    @Test
    fun `ensureRendered does nothing without a recorded wallpaper or with a changed file`() = runTest {
        assertEquals(false, store.ensureRendered())
        applier.foreground = false
        store.apply("home.jpg", WallpaperTarget.Both)
        File(dir, "home.jpg").writeBytes(byteArrayOf(9))
        applier.foreground = true

        assertEquals(false, store.ensureRendered())
        assertEquals(1, applier.applied.size)
    }

    @Test
    fun `a wallpaper changed by hand reads as drift`() = runTest {
        store.apply("home.jpg", WallpaperTarget.Both)
        applier.ids = applier.ids.copy(system = applier.ids.system + 1)

        assertNull(store.current())
    }

    @Test
    fun `lock-only target ignores the system wallpaper id`() = runTest {
        store.apply("home.jpg", WallpaperTarget.Lock)
        applier.ids = applier.ids.copy(system = 99)

        assertEquals(WallpaperState("home.jpg", WallpaperTarget.Lock), store.current())
    }

    @Test
    fun `a replaced image file under the same name reads as drift`() = runTest {
        store.apply("home.jpg", WallpaperTarget.Both)
        File(dir, "home.jpg").writeBytes(byteArrayOf(9, 9, 9))

        assertNull(store.current())
    }

    @Test
    fun `a file replaced during apply is not recorded and reported`() = runTest {
        val racing = object : WallpaperApplier {
            override fun currentIds() = applier.currentIds()
            override fun isRendered(target: WallpaperTarget) = applier.isRendered(target)
            override fun apply(file: File, target: WallpaperTarget): WallpaperIds {
                file.writeBytes(byteArrayOf(7, 7, 7)) // a same-name upload lands mid-apply
                return applier.apply(file, target)
            }
        }
        val racingStore = DefaultWallpaperStore(context, racing, visible)

        val diagnostics = racingStore.apply("home.jpg", WallpaperTarget.Both)

        assertEquals(listOf("wallpaper-replaced-during-apply"), diagnostics.map { it.code })
        assertNull(racingStore.current())
    }

    @Test
    fun `a missing upload is a diagnostic, nothing is applied`() = runTest {
        val diagnostics = store.apply("nope.jpg", WallpaperTarget.Both)

        assertEquals(listOf("wallpaper-missing"), diagnostics.map { it.code })
        assertEquals(emptyList<Any>(), applier.applied)
        assertNull(store.current())
    }

    /**
     * Issue #29: the crop is asynchronous, so isRendered stays false for a
     * while after a re-apply. A second resume in that window must not pay for
     * another crop pass.
     */
    @Test
    fun `a re-apply is not repeated while the crop is still in flight`() = runTest {
        applier.foreground = false
        store.apply("home.jpg", WallpaperTarget.Both)
        assertEquals(1, applier.applied.size)

        // Foreground again, but WallpaperManagerService has not finished the
        // crop: the file the store probes still does not exist.
        applier.foreground = false
        assertEquals(true, store.ensureRendered())
        assertEquals(2, applier.applied.size)

        elapsed = 15_000L // the gap that was measured on the emulator
        assertEquals(false, store.ensureRendered())
        assertEquals("the same record must not be set twice", 2, applier.applied.size)
    }

    @Test
    fun `the cooldown expires so a re-apply that failed is retried`() = runTest {
        applier.foreground = false
        store.apply("home.jpg", WallpaperTarget.Both)
        store.ensureRendered()
        assertEquals(2, applier.applied.size)

        elapsed = ReapplyCooldownMs
        assertEquals(true, store.ensureRendered())
        assertEquals(3, applier.applied.size)
    }

    /**
     * The cooldown is keyed on the record, not on the clock alone: a config
     * that names another wallpaper is not the thing we just asked for.
     */
    @Test
    fun `a new wallpaper is not held back by the cooldown`() = runTest {
        File(dir, "other.jpg").writeBytes(byteArrayOf(4, 5, 6))
        applier.foreground = false
        store.apply("home.jpg", WallpaperTarget.Both)
        store.ensureRendered()
        assertEquals(2, applier.applied.size)

        elapsed = 1_000L
        store.apply("other.jpg", WallpaperTarget.Both)
        assertEquals(3, applier.applied.size)

        assertEquals(true, store.ensureRendered())
        assertEquals(4, applier.applied.size)
        assertEquals(File(dir, "other.jpg") to WallpaperTarget.Both, applier.applied.last())
    }

    // ---- #37: a wallpaper nobody can see is not set now ----

    @Test
    fun `nothing on screen defers the set`() = runTest {
        visible.onPaused()

        val diagnostics = store.apply("home.jpg", WallpaperTarget.Both)

        assertEquals("no crop pass may be paid for an invisible result", 0, applier.applied.size)
        assertEquals(WallpaperState("home.jpg", WallpaperTarget.Both), store.pending())
        // Deliberately silent: the outstanding state is reported by whoever
        // reads pending(), on this reload and on every later one. Saying it
        // here as well put it in the report twice.
        assertEquals(emptyList<Diagnostic>(), diagnostics)
    }

    /**
     * The guard that closes the window between the resume which starts this
     * work and the apply itself: reading the record, resolving the file and
     * hashing it happen on the IO dispatcher while the lock is held, and the
     * last activity can pause in the meantime.
     *
     * Exercises the guard rather than the interleaving - nothing the applier
     * exposes is called before it for a pending record, so there is no honest
     * hook to pause from mid-flight. What it does prove is that the decision
     * is taken from the state at that point and that the record survives, so
     * the next resume retries.
     */
    @Test
    fun `a deferred set is not applied while nothing is on screen`() = runTest {
        visible.onPaused()
        store.apply("home.jpg", WallpaperTarget.Both)

        assertEquals(false, store.ensureRendered())

        assertEquals(0, applier.applied.size)
        assertEquals(
            "still outstanding, so the next resume retries",
            WallpaperState("home.jpg", WallpaperTarget.Both),
            store.pending(),
        )
    }

    /**
     * The read-back has to keep reporting it. `DefaultConfigStore.readState`
     * feeds `current()` into `appearance.wallpaper`, and the provisioning
     * convergence check compares that against the pushed file - a deferred
     * wallpaper reading as "none" would fail a zone whose config is correct.
     */
    @Test
    fun `a deferred wallpaper still reads as the managed one`() = runTest {
        visible.onPaused()
        store.apply("home.jpg", WallpaperTarget.Both)

        assertEquals(WallpaperState("home.jpg", WallpaperTarget.Both), store.current())
    }

    @Test
    fun `the foreground hook sets what was deferred`() = runTest {
        visible.onPaused()
        store.apply("home.jpg", WallpaperTarget.Both)
        assertEquals(0, applier.applied.size)

        visible.onResumed()
        assertEquals(true, store.ensureRendered())
        assertEquals(listOf(File(dir, "home.jpg") to WallpaperTarget.Both), applier.applied)
        assertEquals(WallpaperState("home.jpg", WallpaperTarget.Both), store.current())

        // And once it is really set, the ordinary rules apply again.
        assertEquals(false, store.ensureRendered())
        assertEquals(1, applier.applied.size)
    }

    /**
     * `isRendered` reports whatever the system holds, which for a deferred
     * wallpaper belongs to someone else - the previous wallpaper, or the
     * default. Treating that as "already rendered" would strand the deferral
     * forever.
     */
    @Test
    fun `a deferred wallpaper is set even though something else is rendered`() = runTest {
        visible.onPaused()
        store.apply("home.jpg", WallpaperTarget.Both)
        applier.rendered = true // the wallpaper that was there before

        visible.onResumed()

        assertEquals(true, store.ensureRendered())
        assertEquals(1, applier.applied.size)
    }

    @Test
    fun `on screen it is still applied straight away`() = runTest {
        val diagnostics = store.apply("home.jpg", WallpaperTarget.Both)

        assertEquals(emptyList<Any>(), diagnostics)
        assertEquals(1, applier.applied.size)
    }

    /**
     * The condition the provisioning session asked for: a second run that
     * checks whether everything sits must not come back silently green. Once
     * the intent is recorded, `current()` answers with it, the differ sees no
     * difference and `apply` is never reached again - so the pending state has
     * to be readable on its own rather than as a side effect of applying.
     */
    @Test
    fun `a deferred wallpaper stays reportable across reloads`() = runTest {
        visible.onPaused()
        store.apply("home.jpg", WallpaperTarget.Both)

        assertEquals(WallpaperState("home.jpg", WallpaperTarget.Both), store.pending())

        // A second reload changes nothing and must still be able to say so.
        assertEquals(WallpaperState("home.jpg", WallpaperTarget.Both), store.pending())

        visible.onResumed()
        store.ensureRendered()

        assertNull("once it is set there is nothing outstanding", store.pending())
    }

    @Test
    fun `nothing is pending when the wallpaper was applied straight away`() = runTest {
        store.apply("home.jpg", WallpaperTarget.Both)

        assertNull(store.pending())
    }
}
