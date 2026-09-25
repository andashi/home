package de.mm20.launcher2.glass

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The sentence behind the design (ADR 0004): one blurred backdrop per
 * wallpaper change, not per frame. Each trigger that must re-blur, and each
 * change that must not, is counted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackdropPipelineTest {

    private val image = MutableStateFlow<BackdropImage?>(BackdropImage("/w/zone.jpg", "sha1"))
    private val glass = MutableStateFlow(GlassInputs(24f, 0.35f, 28f, Contrast.Medium))
    private val window = MutableStateFlow<WindowInputs?>(WindowInputs(1080, 2364, 2.625f))
    private val rendered = mutableListOf<BackdropKey>()
    private val pipeline = BackdropPipeline(image, glass, window) { _, key -> rendered += key; "bitmap-${rendered.size}" }
    private var latest: RenderedBackdrop<String>? = null

    private fun TestScope.collect() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            pipeline.backdrop.collect { latest = it }
        }
    }

    @Test
    fun `the first key is rendered once`() = runTest {
        collect()

        assertEquals(1, rendered.size)
        assertEquals("bitmap-1", latest?.bitmap)
    }

    @Test
    fun `a new wallpaper re-blurs`() = runTest {
        collect()
        image.value = BackdropImage("/w/zone.jpg", "sha2")

        assertEquals(listOf("sha1", "sha2"), rendered.map { it.sha256 })
    }

    @Test
    fun `a blur change re-blurs, and so does a contrast change`() = runTest {
        collect()
        glass.value = glass.value.copy(blurDp = 12f)
        glass.value = glass.value.copy(contrast = Contrast.High)

        assertEquals(3, rendered.size)
    }

    @Test
    fun `a display change re-blurs, and folding back comes from the cache`() = runTest {
        collect()
        window.value = WindowInputs(2076, 2152, 2.625f) // unfolded
        window.value = WindowInputs(1080, 2364, 2.625f) // folded again

        assertEquals(2, rendered.size)
        assertEquals("bitmap-1", latest?.bitmap)
    }

    @Test
    fun `tint and radius changes do not re-blur`() = runTest {
        collect()
        glass.value = glass.value.copy(tint = 0.8f)
        glass.value = glass.value.copy(radiusDp = 12f)

        assertEquals(1, rendered.size)
    }

    @Test
    fun `no managed wallpaper or no window means no backdrop and no work`() = runTest {
        image.value = null
        collect()
        assertNull(latest)

        window.value = null
        image.value = BackdropImage("/w/zone.jpg", "sha1")
        assertNull(latest)
        assertEquals(0, rendered.size)
    }

    @Test
    fun `a render that fails yields no backdrop`() = runTest {
        val failing = BackdropPipeline<String>(image, glass, window) { _, _ -> null }
        var value: RenderedBackdrop<String>? = RenderedBackdrop(BackdropKey("x", 1, 1, 0), "stale")
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { failing.backdrop.collect { value = it } }

        assertNull(value)
    }

    /**
     * #130: a window the flow has not reached yet - the composition sees a
     * new window before the flow does - is looked up from the cache at once.
     */
    @Test
    fun `peek gives the cached backdrop for a window the flow has not reached`() = runTest {
        collect()
        val cover = WindowInputs(1080, 2364, 2.625f)
        val inner = WindowInputs(2076, 2152, 2.625f)
        window.value = inner
        window.value = cover

        val found = pipeline.peek(image.value, glass.value, inner)

        assertEquals(2076, found?.key?.windowWidthPx)
        assertEquals("bitmap-2", found?.bitmap)
        assertEquals("peek rendered nothing", 2, rendered.size)
    }

    /** Control: nothing made for that window, nothing found - a real miss stays asynchronous. */
    @Test
    fun `peek finds nothing for a window never rendered, or without a wallpaper`() = runTest {
        collect()

        assertNull(pipeline.peek(image.value, glass.value, WindowInputs(2076, 2152, 2.625f)))
        assertNull(pipeline.peek(null, glass.value, WindowInputs(1080, 2364, 2.625f)))
        assertEquals(1, rendered.size)
    }
}
