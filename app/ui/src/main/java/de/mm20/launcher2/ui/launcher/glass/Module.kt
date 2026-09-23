package de.mm20.launcher2.ui.launcher.glass

import de.mm20.launcher2.config.GlassContrast
import de.mm20.launcher2.glass.Contrast
import de.mm20.launcher2.glass.GlassInputs
import de.mm20.launcher2.preferences.ui.UiSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import org.koin.dsl.module

/** The glass backdrop for the process (#74); its source comes from the config service. */
val glassModule = module {
    single {
        GlassBackdropController(
            source = get(),
            glass = get<UiSettings>().glass.map {
                GlassInputs(it.blur, it.tint, it.radius, it.contrast.toGlass())
            },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            wallpaperBlur = get<UiSettings>().glass.map { it.wallpaperBlur },
            render = AndroidBackdropRenderer::render,
        )
    }
}

private fun GlassContrast.toGlass(): Contrast = when (this) {
    GlassContrast.Low -> Contrast.Low
    GlassContrast.Medium -> Contrast.Medium
    GlassContrast.High -> Contrast.High
}
