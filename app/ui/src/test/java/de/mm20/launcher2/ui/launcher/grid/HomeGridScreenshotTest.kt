package de.mm20.launcher2.ui.launcher.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import de.mm20.launcher2.homegrid.FormFactor
import de.mm20.launcher2.homegrid.HomeGridArrangement
import de.mm20.launcher2.homegrid.HomeGridGeometry
import de.mm20.launcher2.homegrid.HomeGridItem
import de.mm20.launcher2.homegrid.GridItemLimits
import de.mm20.launcher2.homegrid.HomeGridLayouts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.material3.Text
import de.mm20.launcher2.glass.BackdropGeometry
import de.mm20.launcher2.glass.BackdropImage
import de.mm20.launcher2.glass.Contrast
import de.mm20.launcher2.glass.GlassInputs
import de.mm20.launcher2.glass.GlassStyle
import de.mm20.launcher2.glass.WindowInputs
import de.mm20.launcher2.preferences.SearchBarStyle
import de.mm20.launcher2.ui.component.SearchBar
import de.mm20.launcher2.ui.component.SearchBarLevel
import de.mm20.launcher2.ui.component.ShapedLauncherIcon
import de.mm20.launcher2.ui.launcher.glass.GlassWallpaper
import de.mm20.launcher2.ui.launcher.glass.LocalClearIcons
import de.mm20.launcher2.ui.launcher.glass.LocalGlassBackdrop
import de.mm20.launcher2.ui.launcher.glass.LocalGlassStyle
import de.mm20.launcher2.ui.launcher.glass.LocalGlassWallpaperBlur
import de.mm20.launcher2.ui.launcher.glass.ZoneSeed
import de.mm20.launcher2.ui.launcher.glass.dockIcons
import de.mm20.launcher2.ui.launcher.glass.mauritiusBackdrop
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * L3 goldens of the home screen in the glass look (ADR 0004, #77): the
 * reference's own wallpaper - provisioning's `themes/mauritius`, downscaled
 * into the test resources - blurred into the backdrop and the background,
 * the search pill, two widget cards with their labels, and the dock with four
 * Clear icons, one of them the desaturated fallback. On a phone, on a fold's
 * inner display (square wallpaper) and on its cover, per zone color: a cold,
 * a warm and the colorless Home seed; contrast `medium`, plus one `high`
 * image that pins the scrim.
 *
 * Cells are placeholders: Roborazzi cannot host real AppWidgets, so the
 * goldens pin the geometry and the glass, not a provider's pixels.
 * Robolectric's native graphics report a hardware canvas and compile the
 * AGSL edge lens, so the goldens include it (measured for #91);
 * GlassLensTest (L2) pins it on a device.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeGridScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val items = listOf(
        gridItem("weather", 0, 0, 2, 2, position = 0),
        gridItem("calendar", 2, 0, 2, 2, position = 1),
        dockItem(0, 5, 4, 1),
    )

    private val foldItems = listOf(
        // The cover is the right half (#93): what it shows sits in columns 4-7.
        gridItem("weather", 4, 0, 2, 2, HomeGridLayouts.Fold, position = 0),
        gridItem("calendar", 6, 0, 2, 2, HomeGridLayouts.Fold, position = 1),
        // Eight columns on the inner display; the cover clips it to four.
        dockItem(0, 5, 8, 1, HomeGridLayouts.Fold),
    )

    private val labels = mapOf("weather" to "Weather", "calendar" to "Calendar")

    @Composable
    private fun Frame(
        formFactor: FormFactor,
        items: List<HomeGridItem>,
        seed: ZoneSeed,
        contrast: Contrast,
        square: Boolean,
    ) {
        val context = LocalContext.current
        // The window size as production takes it (ProvideGlassBackdrop), so
        // the backdrop maps to surfaces exactly as it does on a device.
        val window = LocalWindowInfo.current.containerSize
        val density = LocalDensity.current.density
        val inputs = GlassInputs(24f, 0.12f, 28f, contrast)
        val backdrop = remember(window) {
            val blurPx = BackdropGeometry.key(
                BackdropImage("", "mauritius"),
                WindowInputs(window.width, window.height, density),
                inputs,
            ).blurPx
            mauritiusBackdrop(square, window.width, window.height, blurPx)
        }
        val icons = remember { dockIcons(context) }
        MaterialTheme(colorScheme = seed.colorScheme()) {
            CompositionLocalProvider(
                LocalGlassBackdrop provides backdrop,
                LocalGlassStyle provides GlassStyle.resolve(inputs),
                LocalGlassWallpaperBlur provides true,
                LocalClearIcons provides true,
            ) {
                Box(Modifier.fillMaxSize()) {
                    GlassWallpaper()
                    Column(Modifier.fillMaxSize().padding(8.dp)) {
                        SearchBar(
                            modifier = Modifier.fillMaxWidth(),
                            style = SearchBarStyle.Transparent,
                            level = SearchBarLevel.Resting,
                            value = "",
                            onValueChange = {},
                            glass = true,
                        )
                        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)) {
                            val geometry = HomeGridGeometry.derive(formFactor, 4, maxWidth.value, maxHeight.value)
                            val cells = HomeGridArrangement.arrange(geometry, items).cells
                            HomeGridLayout(
                                geometry = geometry,
                                cells = cells.map { it.item.id to it.span },
                                modifier = Modifier.fillMaxSize(),
                            ) { id ->
                                if (id == "dock") {
                                    GridCard {
                                        Row(
                                            Modifier.fillMaxSize().padding(8.dp),
                                            horizontalArrangement = Arrangement.SpaceEvenly,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            for (icon in icons) ShapedLauncherIcon(size = 52.dp, icon = { icon })
                                        }
                                    }
                                } else {
                                    Column(Modifier.fillMaxSize()) {
                                        Box(Modifier.weight(1f)) {
                                            GridCard {
                                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                    Text(labels.getValue(id), style = MaterialTheme.typography.titleMedium)
                                                }
                                            }
                                        }
                                        GridLabel(id, labels.getValue(id))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun golden(
        formFactor: FormFactor,
        seed: ZoneSeed,
        contrast: Contrast = Contrast.Medium,
        square: Boolean = false,
    ) {
        val cells = if (formFactor == FormFactor.Fold) foldItems else items
        composeRule.setContent { Frame(formFactor, cells, seed, contrast, square) }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage()
    }

    @Test @Config(qualifiers = Phone) fun phoneCold() = golden(FormFactor.Phone, ZoneSeed.Cold)
    @Test @Config(qualifiers = Phone) fun phoneWarm() = golden(FormFactor.Phone, ZoneSeed.Warm)
    @Test @Config(qualifiers = Phone) fun phoneHome() = golden(FormFactor.Phone, ZoneSeed.Home)

    /** The one `contrast: high` image: the scrim behind glyphs and labels. */
    @Test @Config(qualifiers = Phone) fun phoneHomeHighContrast() = golden(FormFactor.Phone, ZoneSeed.Home, Contrast.High)

    @Test @Config(qualifiers = FoldCover) fun foldCoverCold() = golden(FormFactor.Fold, ZoneSeed.Cold)
    @Test @Config(qualifiers = FoldCover) fun foldCoverWarm() = golden(FormFactor.Fold, ZoneSeed.Warm)
    @Test @Config(qualifiers = FoldCover) fun foldCoverHome() = golden(FormFactor.Fold, ZoneSeed.Home)

    @Test @Config(qualifiers = FoldInner) fun foldInnerCold() = golden(FormFactor.Fold, ZoneSeed.Cold, square = true)
    @Test @Config(qualifiers = FoldInner) fun foldInnerWarm() = golden(FormFactor.Fold, ZoneSeed.Warm, square = true)
    @Test @Config(qualifiers = FoldInner) fun foldInnerHome() = golden(FormFactor.Fold, ZoneSeed.Home, square = true)

    private companion object {
        const val Phone = "w412dp-h915dp-normal-long-notround-port-420dpi"
        const val FoldCover = "w412dp-h923dp-normal-long-notround-port-420dpi"
        const val FoldInner = "w790dp-h820dp-normal-notlong-notround-port-420dpi"
    }
}
