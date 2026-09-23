package de.mm20.launcher2.ui.launcher.glass

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.mm20.launcher2.glass.BackdropGeometry
import de.mm20.launcher2.glass.BackdropImage
import de.mm20.launcher2.glass.Contrast
import de.mm20.launcher2.glass.GlassBackdropSource
import de.mm20.launcher2.glass.GlassInputs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A config reload that changes `glass.blur` from 24 to 0 and back (#77, L2):
 * the surfaces re-render with the new backdrop each time, and the way back
 * comes from the cache instead of a second blur.
 */
@RunWith(AndroidJUnit4::class)
class GlassReloadTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val source = object : GlassBackdropSource {
        override val image = MutableStateFlow<BackdropImage?>(BackdropImage("/w/zone.jpg", "sha1"))
        override suspend fun refresh() = Unit
    }
    private val glass = MutableStateFlow(GlassInputs(24f, 0.12f, 28f, Contrast.Medium))
    private var renders = 0
    /** Blur 0 renders red, any other blur blue: the surfaces show which one they drew. */
    private val controller = GlassBackdropController(source, glass, CoroutineScope(Dispatchers.Main.immediate)) { _, key ->
        renders++
        val (w, h) = BackdropGeometry.backdropSize(key.windowWidthPx, key.windowHeightPx)
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            eraseColor(if (key.blurPx == 0) android.graphics.Color.RED else android.graphics.Color.BLUE)
        }.asImageBitmap()
    }

    /** Red minus blue at the centre of every surface, as drawn on screen. */
    private fun drawnRedness(): List<Float> =
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(GlassBackdropBlurPx)).fetchSemanticsNodes().indices.map { i ->
            val image = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(GlassBackdropBlurPx))[i].captureToImage().toPixelMap()
            val c = image[image.width / 2, image.height / 2]
            c.red - c.blue
        }

    private fun blurs(): List<Int> =
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(GlassBackdropBlurPx)).fetchSemanticsNodes()
            .map { it.config[GlassBackdropBlurPx] }

    @Test
    fun aBlurChangeReRendersTheSurfacesAndTheWayBackIsCached() {
        composeRule.setContent {
            MaterialTheme {
                ProvideGlassBackdrop(controller) {
                    Column {
                        repeat(3) {
                            GlassSurface(Modifier.fillMaxWidth().height(80.dp).padding(8.dp)) {}
                        }
                    }
                }
            }
        }
        composeRule.waitUntil(5_000) { blurs().size == 3 }
        val before = blurs().distinct()
        assertEquals(1, before.size)
        assertTrue("24 dp is more than zero backdrop pixels", before.single() > 0)
        assertTrue("blue backdrop drawn: ${drawnRedness()}", drawnRedness().all { it < 0f })

        composeRule.runOnIdle { glass.value = glass.value.copy(blurDp = 0f) }
        // Three surfaces each time: an empty list would satisfy all { } without checking anything.
        composeRule.waitUntil(5_000) { blurs().let { it.size == 3 && it.all { b -> b == 0 } } }
        assertEquals(2, renders)
        assertTrue("red backdrop drawn: ${drawnRedness()}", drawnRedness().let { it.size == 3 && it.all { r -> r > 0f } })

        composeRule.runOnIdle { glass.value = glass.value.copy(blurDp = 24f) }
        composeRule.waitUntil(5_000) { blurs().let { it.size == 3 && it.all { b -> b == before.single() } } }
        assertEquals("the way back comes from the cache", 2, renders)
        assertTrue("blue again: ${drawnRedness()}", drawnRedness().let { it.size == 3 && it.all { r -> r < 0f } })
    }
}
