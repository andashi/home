package de.mm20.launcher2.ui.launcher.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
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

    private companion object {
        const val Surfaces = 8
    }
}
