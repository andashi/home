package de.mm20.launcher2.config.service

import de.mm20.launcher2.applications.AppRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * A signal per package that may have become available, in every profile the
 * launcher serves (review on #213): what the config watcher reloads on while
 * something the file names is absent.
 *
 * Three sources, because none sees everything:
 * - the system's package callbacks (`packageEvents`), which report a package
 *   that brings only a widget and so never shows in the app list;
 * - the app list growing, which is how a profile's apps appear when the
 *   profile becomes available, with no package event at all;
 * - the icon pack index growing: a pack's package event races its
 *   indexing, so a reload on the event can look before the pack is there
 *   (#3 slice 4).
 *
 * [events] is the first, a parameter so the three can be tested apart.
 *
 * A package can arrive through more than one; a later reload then finds nothing
 * waiting.
 */
internal fun packageArrivals(events: Flow<String>, apps: AppRepository, iconPacks: IconPackIndex): Flow<String> = merge(
    events,
    appKeyGrowth(apps.findMany().map { list -> list.mapTo(HashSet()) { it.key } }),
    iconPackGrowth(iconPacks),
)

/** [FirstIconPackListRead], then each pack as it enters the index; see [IconPackIndex.indexed]. */
internal fun iconPackGrowth(iconPacks: IconPackIndex): Flow<String> = appKeyGrowth(iconPacks.indexed(), FirstIconPackListRead)

/**
 * [FirstAppListRead] for the first value of [keys], then each key that
 * appears after it, once; a key leaving makes nothing applicable.
 *
 * The first value is a signal too, not only a baseline: a profile that
 * became available after the watcher's start decision but before this read
 * is already in it and grows nothing afterwards, so the watcher decides once
 * more on it, under the reload lock like any arrival (review on #213).
 */
internal fun appKeyGrowth(keys: Flow<Set<String>>, first: String = FirstAppListRead): Flow<String> = flow {
    var known: Set<String>? = null
    keys.collect { now ->
        val before = known
        known = now
        if (before == null) emit(first) else (now - before).forEach { emit(it) }
    }
}

/** What [appKeyGrowth] signals for the app list's first read; never a package name. */
internal const val FirstAppListRead = "(first app list)"

/** What [iconPackGrowth] signals for the index's first read; never a package name. */
internal const val FirstIconPackListRead = "(first icon pack list)"
