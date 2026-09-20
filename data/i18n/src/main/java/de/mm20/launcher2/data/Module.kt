package de.mm20.launcher2.data

import de.mm20.launcher2.search.StringNormalizer
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val i18nDataModule = module {
    single<StringNormalizer> {
        IcuStringNormalizer(androidContext(), get())
    }
}