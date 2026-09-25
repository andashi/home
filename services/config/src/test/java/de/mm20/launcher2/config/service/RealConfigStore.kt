package de.mm20.launcher2.config.service

import android.content.ComponentName
import android.content.Context
import android.os.Process
import android.os.UserHandle
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.homegrid.HomeGridInitLock
import de.mm20.launcher2.preferences.config.LauncherConfigSettings
import de.mm20.launcher2.preferences.preferencesModule
import de.mm20.launcher2.profiles.Profile
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

/**
 * [DefaultConfigStore] over the real settings - `LauncherConfigSettings` on a
 * real DataStore - and the shared fakes for the repository-backed sections.
 * Starts Koin with the preferences module; [close] stops it.
 */
internal class RealConfigStore {
    val context: Context = ApplicationProvider.getApplicationContext()
    val personal: UserHandle = Process.myUserHandle()
    val work: UserHandle = TestUsers.userHandleFor(10)
    val apps = FakeAppRepository()
    val grid = FakeHomeGridRepository()

    val store: DefaultConfigStore

    init {
        stopKoin()
        startKoin {
            androidContext(context)
            modules(preferencesModule)
        }
        store = DefaultConfigStore(
            GlobalContext.get().get<LauncherConfigSettings>(),
            grid,
            FakeInitFlag(),
            HomeGridInitLock(),
            FakeGridLimitsSource(),
            FakeGridRowsSource(),
            FakeSavableSearchableRepository(),
            apps,
            FakeProfileResolver(
                personal = Profile(Profile.Type.Personal, personal, 0),
                work = Profile(Profile.Type.Work, work, 10),
            ),
            FakeWallpaperStore(),
            FakeSearchActionStore(),
        )
    }

    /** Makes [packageName] an installed app of [user]. */
    fun install(packageName: String, user: UserHandle = personal) {
        apps.apps[packageName to user] = FakeApplication(ComponentName(packageName, "$packageName.MainActivity"), user)
    }

    fun close() = stopKoin()
}
