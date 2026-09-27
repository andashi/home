package de.mm20.launcher2.config.service

import android.icu.text.Transliterator
import de.mm20.launcher2.applications.AppRepository
import de.mm20.launcher2.glass.GlassBackdropSource
import de.mm20.launcher2.homegrid.HomeGridWriteBack
import de.mm20.launcher2.homegrid.MeasuredGridRows
import de.mm20.launcher2.permissions.PermissionGroup
import de.mm20.launcher2.permissions.PermissionsManager
import kotlinx.coroutines.flow.map
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
    factory<ProfileResolver> { ProfileManagerProfileResolver(get()) }
    single { ForegroundState() }
    single { DefaultWallpaperStore(androidContext(), AndroidWallpaperApplier(androidContext()), get()) }
    single<WallpaperStore> { get<DefaultWallpaperStore>() }
    // The glass backdrop's source is the managed wallpaper (#74).
    single<GlassBackdropSource> { get<DefaultWallpaperStore>() }
    factory<GridLimitsSource> { AppWidgetGridLimitsSource(androidContext(), get()) }
    // GridRowsSource comes from homeGridModule: the renderer's measured rows.
    factory<ConfigStore> {
        DefaultConfigStore(
            settings = get(),
            homeGridRepository = get(),
            homeGridInitFlag = get(),
            homeGridInitLock = get(),
            gridLimits = get(),
            gridRows = get(),
            searchableRepository = get(),
            appRepository = get(),
            profileResolver = get(),
            wallpapers = get(),
            searchActions = get(),
            apps = get(),
        )
    }
    factory<AppCustomizationStore> { AndroidAppCustomizationStore(get(), get(), get(), get(), get()) }
    // One instance: it holds the form every store reads apps back in (review on #207).
    single<AppNaming> { FileAppNaming(androidContext()) }
    factory<SearchActionStore> { AndroidSearchActionStore(androidContext(), get()) }
    single { ReloadReportStore(androidContext()) }
    // One lock around launcher.json: reloads (watcher, receiver) and every
    // write-back must serialize on it.
    single { ConfigFileLock() }
    single { AppliedBaselineStore(androidContext()) }
    // What the file asks for that this profile cannot do is reported (#140).
    single {
        val permissions = get<PermissionsManager>()
        ConfigReloader(
            get(), get(), get(), get(),
            capabilities = CapabilityDiagnostics(
                contactsGranted = { permissions.checkPermissionOnce(PermissionGroup.Contacts) },
                callGranted = { permissions.checkPermissionOnce(PermissionGroup.Call) },
                // The lookup the normalizer makes, so the report and the search agree.
                transliteratorAvailable = { id -> runCatching { Transliterator.getInstance(id) }.isSuccess },
                // What ScreenOffComponent and the others check before they act.
                accessibilityOn = { permissions.checkPermissionOnce(PermissionGroup.Accessibility) },
            ),
            // Every reload that goes through records the apps' form where none exists (review on #214).
            appNaming = get(),
        )
    }
    single { ConfigWriteBack(androidContext(), get(), get(), get(), get(), appNaming = get()) }
    single { GridWriteBack(androidContext(), get(), get(), get(), get(), engine = get()) }
    // What the grid's edit mode calls on Done (data/homegrid's interface).
    single<HomeGridWriteBack> { HomeGridWriteBackAdapter(get()) }
    single(createdAtStart = true) {
        ConfigWatcher(
            androidContext(), get(), get(), baselineStore = get(),
            // A layout kept as written before its rows were measured is fitted once they are (#90).
            measurements = get<MeasuredGridRows>().measurements,
            // A build updated from one without the record reloads once to make it (review on #207).
            appNaming = get(),
            // What the file names and a device lacked is applied when it is installed (#207 review).
            arrivals = packageArrivals(androidContext(), get<AppRepository>()),
        ).also { it.start() }
    }
    // Every change on the device goes back into the file (#3 slice 4).
    single(createdAtStart = true) {
        val writeBack = get<ConfigWriteBack>()
        ConfigWriteBackTrigger(get(), { writeBack.write() }, appNaming = get()).also { it.start() }
    }
    single(createdAtStart = true) { WallpaperForegroundFixer(androidContext(), get(), get()).also { it.start() } }
}
