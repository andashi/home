package de.mm20.launcher2.config.service

import android.content.Context
import de.mm20.launcher2.applications.AppRepository
import de.mm20.launcher2.applications.packageEvents
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * A signal per package that may have become available, in every profile the
 * launcher serves (review on #213): what the config watcher reloads on while
 * something the file names is absent.
 *
 * Two sources, because neither sees everything:
 * - the system's package callbacks ([packageEvents]), which report a package
 *   that brings only a widget and so never shows in the app list;
 * - the app list growing, which is how a profile's apps appear when the
 *   profile becomes available, with no package event at all.
 *
 * A package can arrive through both; the second reload then finds nothing
 * waiting.
 */
internal fun packageArrivals(context: Context, apps: AppRepository): Flow<String> = merge(
    packageEvents(context),
    appKeyGrowth(apps.findMany().map { list -> list.mapTo(HashSet()) { it.key } }),
)

/**
 * [FirstAppListRead] for the first value of [keys], then each key that
 * appears after it, once; a key leaving makes nothing applicable.
 *
 * The first value is a signal too, not only a baseline: a profile that
 * became available after the watcher's start decision but before this read
 * is already in it and grows nothing afterwards, so the watcher decides once
 * more on it, under the reload lock like any arrival (review on #213).
 */
internal fun appKeyGrowth(keys: Flow<Set<String>>): Flow<String> = flow {
    var known: Set<String>? = null
    keys.collect { now ->
        val before = known
        known = now
        if (before == null) emit(FirstAppListRead) else (now - before).forEach { emit(it) }
    }
}

/** What [appKeyGrowth] signals for the app list's first read; never a package name. */
internal const val FirstAppListRead = "(first app list)"
