package de.mm20.launcher2.data.customattrs

import org.koin.core.qualifier.named
import org.koin.dsl.module

val customAttrsModule = module {
    factory<CustomAttributesRepository> { CustomAttributesRepositoryImpl(get(), get()) }
}