package de.mm20.launcher2.search

import org.koin.core.qualifier.named
import org.koin.dsl.module

val searchModule = module {
    single<SearchService> {
        SearchServiceImpl(
            get(named<Application>()),
            get(named<AppShortcut>()),
            get(named<Contact>()),
            get(),
            get(),
            get(),
        )
    }
}