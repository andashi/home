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
 * The keys that appear in [keys] after its first value, each once: the first
 * value is what was there already, and a key leaving makes nothing applicable.
 */
internal fun appKeyGrowth(keys: Flow<Set<String>>): Flow<String> = flow {
    var known: Set<String>? = null
    keys.collect { now ->
        val before = known
        known = now
        if (before != null) (now - before).forEach { emit(it) }
    }
}
