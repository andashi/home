package de.mm20.launcher2.homegrid

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val homeGridModule = module {
    // One writer for the grid; see HomeGridRepository.
    single<HomeGridRepository> { HomeGridRepositoryImpl(get()) }
    single<FormFactorDetector> { AndroidFormFactorDetector(androidContext()) }
    // One instance: the renderer writes the rows it measured, the config
    // store reads them.
    single { MeasuredGridRows() }
    single<GridRowsSource> { get<MeasuredGridRows>() }
    single<HomeGridSeedFlag> { UiSettingsSeedFlag(get()) }
    single { HomeGridSeeder(androidContext(), get(), get(), get()) }
}
