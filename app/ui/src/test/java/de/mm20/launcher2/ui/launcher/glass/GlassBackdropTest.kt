package de.mm20.launcher2.ui.launcher.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.mm20.launcher2.glass.BackdropImage
import de.mm20.launcher2.glass.Contrast
import de.mm20.launcher2.glass.GlassBackdropSource
import de.mm20.launcher2.glass.GlassInputs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The assertion behind ADR 0004's sentence, in the composition: the blur runs
 * once for N recompositions and M surfaces.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassBackdropTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class FakeSource : GlassBackdropSource {
        override val image = MutableStateFlow<BackdropImage?>(BackdropImage("/w/zone.jpg", "sha1"))
        var refreshes = 0
        var failRefresh = false
        override suspend fun refresh() {
            refreshes++
            if (failRefresh) throw java.io.IOException("file gone mid-read")
        }
    }

    private val source = FakeSource()
    private val glass = MutableStateFlow(GlassInputs(24f, 0.35f, 28f, Contrast.Medium))
    private var renders = 0
    private val controller = GlassBackdropController(
        source,
        glass,
        CoroutineScope(Dispatchers.Unconfined),
    ) { _, key ->
        renders++
        val (w, h) = de.mm20.launcher2.glass.BackdropGeometry.backdropSize(key.windowWidthPx, key.windowHeightPx)
        ImageBitmap(w, h)
    }

    private var tick by mutableIntStateOf(0)

    /** A lifecycle the test moves by hand (lifecycle-runtime-testing is not a dependency here). */
    private class TestLifecycleOwner(initial: Lifecycle.State) : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = initial }
        override val lifecycle: Lifecycle get() = registry
        var currentState: Lifecycle.State
            get() = registry.currentState
            set(value) {
                registry.currentState = value
            }
    }

    private fun setContent(lifecycle: TestLifecycleOwner = TestLifecycleOwner(Lifecycle.State.RESUMED)) {
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                ProvideGlassBackdrop(controller) {
                    Column {
                        repeat(Surfaces) { i ->
                            Box(Modifier.size(40.dp).testTag("surface-$i").glassBackdrop())
                        }
                        Text("recomposition $tick")
                    }
                }
            }
        }
    }

    @Test
    fun `the blur runs once for twenty recompositions and eight surfaces`() {
        setContent()

        repeat(20) {
            tick++
            composeRule.waitForIdle()
        }

        assertEquals(1, renders)
        // Every surface actually drew from it, each its own region.
        val regions = (0 until Surfaces).map { i ->
            composeRule.onNode(SemanticsMatcher.keyIsDefined(GlassBackdropRegion).and(
                SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.TestTag, "surface-$i")
            )).fetchSemanticsNode().config[GlassBackdropRegion]
        }
        assertEquals(Surfaces, regions.distinct().size)
    }

    @Test
    fun `a tint change recomposes the surfaces without re-blurring`() {
        setContent()
        composeRule.waitForIdle()

        glass.value = glass.value.copy(tint = 0.8f)
        composeRule.waitForIdle()

        assertEquals(1, renders)
    }

    @Test
    fun `no managed wallpaper, no backdrop and no region`() {
        source.image.value = null
        setContent()
        composeRule.waitForIdle()

        assertEquals(0, renders)
        composeRule.onNode(SemanticsMatcher.keyIsDefined(GlassBackdropRegion)).assertDoesNotExist()
    }

    @Test
    fun `the source re-checks the wallpaper when the launcher comes to the front`() {
        val lifecycle = TestLifecycleOwner(Lifecycle.State.CREATED)
        setContent(lifecycle)
        composeRule.waitForIdle()
        assertEquals(0, source.refreshes)

        composeRule.runOnIdle { lifecycle.currentState = Lifecycle.State.RESUMED }
        composeRule.waitForIdle()
        assertEquals(1, source.refreshes)

        composeRule.runOnIdle {
            lifecycle.currentState = Lifecycle.State.STARTED
            lifecycle.currentState = Lifecycle.State.RESUMED
        }
        composeRule.waitForIdle()
        assertEquals(2, source.refreshes)
    }

    @Test
    fun `a failing re-check on resume keeps the launcher and the backdrop`() {
        source.failRefresh = true
        setContent()
        composeRule.waitForIdle()

        assertEquals(1, source.refreshes)
        assertEquals(1, renders)
        composeRule.onNode(SemanticsMatcher.keyIsDefined(GlassBackdropRegion).and(
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.TestTag, "surface-0")
        )).assertExists()
    }

    /** A window whose size the test changes, as a fold or unfold does. */
    private class FakeWindowInfo(size: IntSize) : WindowInfo {
        var size by mutableStateOf(size)
        override val isWindowFocused = true
        override val containerSize: IntSize get() = size
    }

    /**
     * What each composition inside [ProvideGlassBackdrop] saw: the window's
     * width and the width of the window the provided backdrop was made for.
     */
    private fun setWindowContent(window: FakeWindowInfo, seen: MutableList<Pair<Int, Int?>>) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(
                LocalWindowInfo provides window,
                LocalLifecycleOwner provides TestLifecycleOwner(Lifecycle.State.RESUMED),
            ) {
                ProvideGlassBackdrop(controller) {
                    val width = LocalWindowInfo.current.containerSize.width
                    val made = LocalGlassBackdrop.current?.key?.windowWidthPx
                    SideEffect { seen += width to made }
                }
            }
        }
    }

    private fun framesUntil(done: () -> Boolean) {
        var frames = 0
        while (!composeRule.runOnIdle(done)) {
            check(++frames < 20) { "never happened" }
            composeRule.mainClock.advanceTimeByFrame()
        }
    }

    /**
     * #130: after a fold or unfold the first composition at the new window
     * size must already draw the backdrop made for it when that backdrop is
     * cached. It drew the previous window's, stretched, for one frame: the
     * window reached the controller in a LaunchedEffect after that
     * composition, and the cache hit came back through the flow.
     */
    @Test
    fun `a window change is composed with the cached backdrop for the new window, in that frame`() {
        val window = FakeWindowInfo(Cover)
        val seen = mutableListOf<Pair<Int, Int?>>()
        setWindowContent(window, seen)
        // Both displays blurred once: fold, unfold, fold.
        framesUntil { seen.lastOrNull()?.second == Cover.width }
        window.size = Inner
        framesUntil { seen.lastOrNull()?.second == Inner.width }
        window.size = Cover
        framesUntil { seen.lastOrNull()?.second == Cover.width }
        assertEquals(2, renders)

        composeRule.runOnIdle { seen.clear() }
        window.size = Inner
        framesUntil { seen.any { it.first == Inner.width } }

        val first = composeRule.runOnIdle { seen.first { it.first == Inner.width } }
        assertEquals("the backdrop in the first composition at ${Inner.width} px", Inner.width, first.second)
        assertEquals("from the cache, not re-blurred", 2, renders)
    }

    /**
     * Control: a window never blurred before keeps the previous backdrop
     * until its own is made. The composition never waits for a render.
     */
    @Test
    fun `a window never blurred keeps the previous backdrop until its own is made`() {
        val window = FakeWindowInfo(Cover)
        val seen = mutableListOf<Pair<Int, Int?>>()
        setWindowContent(window, seen)
        framesUntil { seen.lastOrNull()?.second == Cover.width }

        composeRule.runOnIdle { seen.clear() }
        window.size = Inner
        framesUntil { seen.lastOrNull()?.second == Inner.width }

        val first = composeRule.runOnIdle { seen.first { it.first == Inner.width } }
        assertEquals("the previous backdrop in the first composition", Cover.width, first.second)
        assertEquals(2, renders)
    }

    private companion object {
        const val Surfaces = 8
        val Cover = IntSize(1080, 2364)
        val Inner = IntSize(2076, 2152)
    }
}
