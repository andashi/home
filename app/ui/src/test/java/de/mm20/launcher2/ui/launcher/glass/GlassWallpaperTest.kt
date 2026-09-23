package de.mm20.launcher2.ui.launcher.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.mm20.launcher2.glass.BackdropImage
import de.mm20.launcher2.glass.BackdropKey
import de.mm20.launcher2.glass.Contrast
import de.mm20.launcher2.glass.GlassBackdropSource
import de.mm20.launcher2.glass.GlassInputs
import de.mm20.launcher2.glass.RenderedBackdrop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** `appearance.glass.wallpaperBlur` (#82): the home background as the blurred backdrop. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassWallpaperTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val backdrop = RenderedBackdrop(BackdropKey("sha", 1080, 2400, 8), ImageBitmap(135, 300))

    private fun show(blur: Boolean, withBackdrop: Boolean = true) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalGlassBackdrop provides if (withBackdrop) backdrop else null,
                LocalGlassWallpaperBlur provides blur,
            ) {
                Box(Modifier.size(200.dp)) { GlassWallpaper() }
            }
        }
    }

    @Test
    fun `on, with a backdrop, the background is the blurred backdrop`() {
        show(blur = true)
        composeRule.onNodeWithTag(GlassWallpaperTag).assertExists()
    }

    @Test
    fun `off, the sharp wallpaper shows through`() {
        show(blur = false)
        composeRule.onNodeWithTag(GlassWallpaperTag).assertDoesNotExist()
    }

    @Test
    fun `without a backdrop there is nothing to draw`() {
        show(blur = true, withBackdrop = false)
        composeRule.onNodeWithTag(GlassWallpaperTag).assertDoesNotExist()
    }

    @Test
    fun `the controller follows the setting`() {
        val setting = MutableStateFlow(true)
        val source = object : GlassBackdropSource {
            override val image = MutableStateFlow<BackdropImage?>(null)
            override suspend fun refresh() = Unit
        }
        val controller = GlassBackdropController(
            source,
            MutableStateFlow(GlassInputs(24f, 0.12f, 28f, Contrast.Medium)),
            CoroutineScope(Dispatchers.Unconfined),
            render = { _, _ -> null },
            wallpaperBlur = setting,
        )
        assertEquals(true, controller.wallpaperBlur.value)
        setting.value = false
        assertEquals(false, controller.wallpaperBlur.value)
    }
}
