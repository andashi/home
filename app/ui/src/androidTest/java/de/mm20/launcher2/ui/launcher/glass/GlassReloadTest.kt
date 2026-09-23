package de.mm20.launcher2.ui.launcher.glass

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.SemanticsMatcher
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
    private val controller = GlassBackdropController(source, glass, CoroutineScope(Dispatchers.Main.immediate)) { _, key ->
        renders++
        val (w, h) = BackdropGeometry.backdropSize(key.windowWidthPx, key.windowHeightPx)
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
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

        composeRule.runOnIdle { glass.value = glass.value.copy(blurDp = 0f) }
        composeRule.waitUntil(5_000) { blurs().all { it == 0 } }
        assertEquals(2, renders)

        composeRule.runOnIdle { glass.value = glass.value.copy(blurDp = 24f) }
        composeRule.waitUntil(5_000) { blurs().all { it == before.single() } }
        assertEquals("the way back comes from the cache", 2, renders)
    }
}
