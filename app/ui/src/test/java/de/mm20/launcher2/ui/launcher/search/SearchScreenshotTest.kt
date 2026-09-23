package de.mm20.launcher2.ui.launcher.search

import android.content.Context
import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import de.mm20.launcher2.glass.BackdropGeometry
import de.mm20.launcher2.glass.BackdropImage
import de.mm20.launcher2.glass.Contrast
import de.mm20.launcher2.glass.GlassInputs
import de.mm20.launcher2.glass.GlassStyle
import de.mm20.launcher2.glass.WindowInputs
import de.mm20.launcher2.icons.StaticLauncherIcon
import de.mm20.launcher2.preferences.SearchBarStyle
import de.mm20.launcher2.search.SavableSearchable
import de.mm20.launcher2.search.SearchableSerializer
import de.mm20.launcher2.ui.R
import de.mm20.launcher2.ui.component.Banner
import de.mm20.launcher2.ui.component.SearchBar
import de.mm20.launcher2.ui.component.SearchBarLevel
import de.mm20.launcher2.ui.component.ShapedLauncherIcon
import de.mm20.launcher2.ui.launcher.glass.GlassChip
import de.mm20.launcher2.ui.launcher.glass.GlassWallpaper
import de.mm20.launcher2.ui.launcher.glass.LocalClearIcons
import de.mm20.launcher2.ui.launcher.glass.LocalGlassBackdrop
import de.mm20.launcher2.ui.launcher.glass.LocalGlassSearchWallpaperBlur
import de.mm20.launcher2.ui.launcher.glass.LocalGlassStyle
import de.mm20.launcher2.ui.launcher.glass.LocalGlassWallpaperBlur
import de.mm20.launcher2.ui.launcher.glass.LocalOnGlass
import de.mm20.launcher2.ui.launcher.glass.ZoneSeed
import de.mm20.launcher2.ui.launcher.glass.dockIcons
import de.mm20.launcher2.ui.launcher.glass.mauritiusBackdrop
import de.mm20.launcher2.ui.launcher.search.common.grid.GridResults
import de.mm20.launcher2.ui.launcher.search.common.list.ListResults
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * L3 golden of the search screen in the glass look (#91): the blurred
 * backdrop behind search with a sharp home (`searchWallpaperBlur` on its
 * own), the open pill with its action chips, an app section as one card of
 * glass segments, a list section, a permission banner and the filter chips.
 * The pieces are the production ones; the results are fixtures, since the
 * real screen needs the search services.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class Fake(override val key: String) : SavableSearchable {
        override val domain = "test"
        override val label = key
        override val preferDetailsOverLaunch = false
        override fun overrideLabel(label: String): SavableSearchable = this
        override fun launch(context: Context, options: Bundle?) = false
        override fun getPlaceholderIcon(context: Context): StaticLauncherIcon = throw UnsupportedOperationException()
        override fun getSerializer(): SearchableSerializer = throw UnsupportedOperationException()
    }

    private val apps = listOf("Calculator", "Camera", "Clock", "Contacts", "Lawnicons").map(::Fake)
    private val contacts = listOf("Carla", "Chris", "Conny").map(::Fake)

    @Composable
    private fun Frame(contrast: Contrast) {
        val context = LocalContext.current
        val window = LocalWindowInfo.current.containerSize
        val density = LocalDensity.current.density
        val inputs = GlassInputs(24f, 0.12f, 28f, contrast)
        val backdrop = remember(window) {
            val blurPx = BackdropGeometry.key(
                BackdropImage("", "mauritius"), WindowInputs(window.width, window.height, density), inputs,
            ).blurPx
            mauritiusBackdrop(false, window.width, window.height, blurPx)
        }
        val icons = remember { dockIcons(context) }
        MaterialTheme(colorScheme = ZoneSeed.Home.colorScheme()) {
            CompositionLocalProvider(
                LocalGlassBackdrop provides backdrop,
                LocalGlassStyle provides GlassStyle.resolve(inputs),
                // A sharp home, search open: the backdrop is search's own.
                LocalGlassWallpaperBlur provides false,
                LocalGlassSearchWallpaperBlur provides true,
                LocalClearIcons provides true,
                LocalOnGlass provides true,
            ) {
                Box(Modifier.fillMaxSize()) {
                    GlassWallpaper(searchProgress = { 1f })
                    Column(Modifier.fillMaxSize().padding(8.dp)) {
                        SearchBar(
                            modifier = Modifier.fillMaxWidth(),
                            style = SearchBarStyle.Transparent,
                            level = SearchBarLevel.Active,
                            value = "c",
                            onValueChange = {},
                            glass = true,
                            actions = {
                                Row(
                                    Modifier.padding(8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    GlassChip("Web search", onClick = {}, selected = true)
                                    GlassChip("Wikipedia", onClick = {})
                                }
                            },
                        )
                        LazyColumn(Modifier.padding(top = 8.dp)) {
                            GridResults(
                                key = "apps",
                                items = apps,
                                columns = 4,
                                itemContent = { app ->
                                    Column(
                                        Modifier.padding(vertical = 8.dp).fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        ShapedLauncherIcon(size = 48.dp, icon = { icons[apps.indexOf(app) % icons.size] })
                                        Text(app.label, style = MaterialTheme.typography.bodySmall)
                                    }
                                },
                            )
                            ListResults(
                                key = "contacts",
                                items = contacts,
                                itemContent = { contact, _, _ ->
                                    Text(contact.label, Modifier.padding(16.dp))
                                },
                            )
                            item {
                                Banner(
                                    text = "Contacts permission is required to search your contacts",
                                    icon = R.drawable.star_24px,
                                )
                            }
                            item {
                                Row(
                                    Modifier.padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    GlassChip("Apps", onClick = {}, selected = true)
                                    GlassChip("Shortcuts", onClick = {})
                                    GlassChip("Contacts", onClick = {})
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun golden(contrast: Contrast = Contrast.Medium) {
        composeRule.setContent { Frame(contrast) }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage()
    }

    @Test @Config(qualifiers = Phone) fun phone() = golden()

    /** `contrast: high` over search: the scrim that keeps result text readable. */
    @Test @Config(qualifiers = Phone) fun phoneHighContrast() = golden(Contrast.High)

    private companion object {
        const val Phone = "w412dp-h915dp-normal-long-notround-port-420dpi"
    }
}
