package de.mm20.launcher2.applications

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/**
 * The name of each package that may have become available, in every profile
 * the launcher serves: an install, an update, a package enabled again (`pm
 * enable` is a change), one back from being unavailable or suspended. Unlike
 * the app list it reports a package that brings only a widget, which adds no
 * launcher entry (review on #213).
 *
 * One signal for everything that waits on a package: the config watcher,
 * which reloads a file naming what was missing, and the home grid, which
 * binds a widget whose provider was missing (review on #219). Signals queue,
 * so none is dropped while a consumer is busy.
 */
fun packageEvents(context: Context): Flow<String> = callbackFlow {
    val launcherApps = context.getSystemService(LauncherApps::class.java)
    val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = Unit
        override fun onPackageAdded(packageName: String, user: UserHandle) {
            trySend(packageName)
        }

        override fun onPackageChanged(packageName: String, user: UserHandle) {
            trySend(packageName)
        }

        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) {
            packageNames.forEach { trySend(it) }
        }

        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = Unit

        override fun onPackagesUnsuspended(packageNames: Array<out String>, user: UserHandle) {
            packageNames.forEach { trySend(it) }
        }
    }
    launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
    awaitClose { launcherApps.unregisterCallback(callback) }
}.buffer(Channel.UNLIMITED)
