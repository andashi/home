package de.mm20.launcher2.homegrid

import org.koin.dsl.module

val homeGridModule = module {
    // One writer for the grid; see HomeGridRepository.
    single<HomeGridRepository> { HomeGridRepositoryImpl(get()) }
}
