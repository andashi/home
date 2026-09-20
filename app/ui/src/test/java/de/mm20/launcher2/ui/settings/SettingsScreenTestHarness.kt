package de.mm20.launcher2.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.permissions.PermissionGroup
import de.mm20.launcher2.permissions.PermissionsManager
import de.mm20.launcher2.preferences.preferencesModule
import de.mm20.launcher2.ui.locals.LocalBackStack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.rules.ExternalResource
import java.io.File
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

/**
 * Every permission granted, so a golden shows the unlocked form of each
 * [de.mm20.launcher2.ui.component.preferences.GuardedPreference]. Goldens must
 * not depend on device state, which is the whole reason the real manager is
 * replaced here.
 */
class FakePermissionsManager(
    private val granted: Boolean = true,
) : PermissionsManager {
    override fun requestPermission(context: AppCompatActivity, permissionGroup: PermissionGroup) {}
    override fun checkPermissionOnce(permissionGroup: PermissionGroup): Boolean = granted
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {}
    override fun hasPermission(permissionGroup: PermissionGroup): Flow<Boolean> = flowOf(granted)
    override fun reportNotificationListenerState(running: Boolean) {}
    override fun reportAccessibilityServiceState(running: Boolean) {}
}

/**
 * Starts Koin with the real preferences module and a fake permissions manager.
 *
 * The settings classes are concrete and read from `LauncherDataStore`, which
 * under Robolectric writes into a fresh app directory per test run, so every
 * value is the documented default. That keeps the goldens deterministic
 * without having to hoist state out of the screens.
 */
class KoinSettingsRule(
    private val permissionsGranted: Boolean = true,
) : ExternalResource() {
    override fun before() {
        seedDefaultSettingsFile()
        stopKoin()
        startKoin {
            androidContext(ApplicationProvider.getApplicationContext())
            modules(
                preferencesModule,
                module {
                    single<PermissionsManager> { FakePermissionsManager(permissionsGranted) }
                },
            )
        }
    }

    override fun after() {
        stopKoin()
    }

    /**
     * Writes a minimal settings file before DataStore first reads one.
     *
     * Without it DataStore falls back to `serializer.defaultValue`, whose
     * Context-based `LauncherSettingsData` constructor reads
     * `R.integer.config_columnCount` — a `core:preferences` resource that does
     * not resolve from this module under Robolectric. Every field absent from
     * the file takes its Kotlin default, which is what a golden should show.
     * `core:preferences` has the same workaround in its own tests
     * (`seedSettingsFile`), but it is internal to that module.
     */
    private fun seedDefaultSettingsFile() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = File(context.filesDir, "datastore")
        dir.mkdirs()
        File(dir, "settings.json").writeText("""{"schemaVersion":6}""")
    }
}

/**
 * Robolectric device qualifiers for the goldens: a fixed phone, so a screen
 * renders identically on every machine and in CI. 412x915dp at 420dpi is a
 * Pixel-class portrait window.
 */
const val PHONE_QUALIFIERS =
    "w412dp-h915dp-normal-long-notround-port-420dpi"

/**
 * A full-window frame with a back stack, so a screen renders exactly the same
 * way on every machine.
 */
@Composable
fun SettingsScreenFrame(content: @Composable () -> Unit) {
    val backStack = rememberNavBackStack()
    CompositionLocalProvider(LocalBackStack provides backStack) {
        MaterialTheme {
            Box(modifier = Modifier.fillMaxSize()) {
                content()
            }
        }
    }
}
