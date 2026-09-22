package de.mm20.launcher2.config.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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

    @Before
    fun setup() {
        applier = FakeApplier()
        elapsed = 0L
        store = DefaultWallpaperStore(context, applier) { elapsed }
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
        val racingStore = DefaultWallpaperStore(context, racing)

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
}
