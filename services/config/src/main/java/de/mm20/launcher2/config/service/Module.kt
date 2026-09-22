package de.mm20.launcher2.config.service

import de.mm20.launcher2.themes.ThemeRepository
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Fork addition (Phase 2, ADR 0003): config convergence services plus the
 * Android surface around them — [ReloadConfigReceiver] and
 * [ConfigStateProvider] are manifest-declared and resolve [ConfigReloader] /
 * [ConfigStore] / [ReloadReportStore] from here ([ConfigIngestProvider] is
 * pure transport and needs nothing); [ConfigWatcher] is created
 * eagerly so it observes the config file for the whole process lifetime;
 * [WallpaperForegroundFixer] likewise, to re-apply a wallpaper set from the
 * background once the profile is in the foreground.
 */
val configModule = module {
    // Reuse upstream's construction instead of binding a second instance.
    factory { get<ThemeRepository>().transparencies }
    factory<ProfileResolver> { ProfileManagerProfileResolver(get()) }
    single { ForegroundState() }
    single<WallpaperStore> { DefaultWallpaperStore(androidContext(), AndroidWallpaperApplier(androidContext()), get()) }
    factory<GridLimitsSource> { AppWidgetGridLimitsSource(androidContext(), get()) }
    // Replaced by the renderer's measured rows in PR 4; see GridRowsSource.
    factory<GridRowsSource> { DefaultGridRowsSource() }
    factory<ConfigStore> {
        DefaultConfigStore(
            settings = get(),
            transparenciesRepository = get(),
            homeGridRepository = get(),
            gridLimits = get(),
            gridRows = get(),
            searchableRepository = get(),
            appRepository = get(),
            profileResolver = get(),
            wallpapers = get(),
        )
    }
    single { ReloadReportStore(androidContext()) }
    // One lock around launcher.json: reloads (watcher, receiver) and the
    // edit-mode write-back must serialize on it.
    single { ConfigFileLock() }
    single { ConfigReloader(get(), get(), get()) }
    single { GridWriteBack(androidContext(), get(), get(), get(), get()) }
    single(createdAtStart = true) { ConfigWatcher(androidContext(), get(), get()).also { it.start() } }
    single(createdAtStart = true) { WallpaperForegroundFixer(androidContext(), get(), get()).also { it.start() } }
}
