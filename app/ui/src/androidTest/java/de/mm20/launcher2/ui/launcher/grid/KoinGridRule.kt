package de.mm20.launcher2.ui.launcher.grid

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import de.mm20.launcher2.grid.SizeLimits
import de.mm20.launcher2.homegrid.FormFactor
import de.mm20.launcher2.homegrid.GridItemLimits
import de.mm20.launcher2.homegrid.HomeGridInitFlag
import de.mm20.launcher2.homegrid.HomeGridItem
import de.mm20.launcher2.homegrid.MeasuredGridRows
import de.mm20.launcher2.permissions.PermissionGroup
import de.mm20.launcher2.permissions.PermissionsManager
import de.mm20.launcher2.preferences.preferencesModule
import de.mm20.launcher2.preferences.ui.UiSettings
import de.mm20.launcher2.profiles.ProfileManager
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.rules.ExternalResource
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.dsl.module

/**
 * Koin for the grid's device tests, modelled on the JVM tests'
 * `KoinSettingsRule`: the real preferences module (a fresh DataStore on the
 * device), a granted permissions manager, and the grid's collaborators as
 * fakes the test can read and steer.
 */
class KoinGridRule(
    private val items: Map<String, List<HomeGridItem>>,
    private val formFactor: FormFactor = FormFactor.Phone,
    private val limits: Map<String, SizeLimits> = emptyMap(),
) : ExternalResource() {

    lateinit var repository: FakeHomeGridRepository
        private set
    lateinit var writeBack: FakeWriteBack
        private set
    val locked = MutableStateFlow(false)

    override fun before() {
        // A google_apis image boots to its lock screen and the test activity
        // stays behind it (mViewVisibility GONE, seen on a Pixel-Fold-shaped
        // AVD): wake and dismiss before the activity is launched, the same
        // way the L4 script does before every dump.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (command in listOf("input keyevent KEYCODE_WAKEUP", "wm dismiss-keyguard")) {
            automation.executeShellCommand(command).close()
        }
        repository = FakeHomeGridRepository(items)
        writeBack = FakeWriteBack(repository)
        locked.value = false
        // Koin starts once per test process. The preferences module owns a
        // DataStore on the settings file, and DataStore refuses a second
        // instance on the same file, which is exactly what a stop/start per
        // test produced: every test after the first timed out waiting for a
        // grid whose settings flow had failed. The fakes are per test and
        // are handed to the view model directly; Koin only serves what the
        // grid resolves itself (the profile manager, the settings).
        if (GlobalContext.getOrNull() == null) {
            seedDefaultSettingsFile()
            startKoin {
                androidContext(ApplicationProvider.getApplicationContext())
                modules(
                    preferencesModule,
                    module {
                        single<PermissionsManager> { GrantedPermissions() }
                        single { ProfileManager(androidContext(), get()) }
                        single { MeasuredGridRows() }
                        single<HomeGridInitFlag> { FakeInitFlag() }
                    },
                )
            }
        }
    }

    override fun after() = Unit

    /** A view model over the rule's fakes, the way `HomeGridVM.factory` would build it. */
    fun viewModel(): HomeGridVM {
        val koin = GlobalContext.get()
        return HomeGridVM(
            repository = repository,
            uiSettings = koin.get<UiSettings>(),
            formFactorDetector = FakeFormFactorDetector(formFactor),
            measuredRows = koin.get(),
            initFlag = koin.get(),
            writeBack = writeBack,
            itemLimits = GridItemLimits { item, _ -> limits[item.id] ?: SizeLimits.Unbounded },
            locked = locked,
        )
    }

    private fun seedDefaultSettingsFile() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.filesDir, "datastore")
        dir.mkdirs()
        File(dir, "settings.json").writeText("""{"schemaVersion":6}""")
    }

    private class GrantedPermissions : PermissionsManager {
        override fun requestPermission(context: androidx.appcompat.app.AppCompatActivity, permissionGroup: PermissionGroup) = Unit
        override fun checkPermissionOnce(permissionGroup: PermissionGroup): Boolean = true
        override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) = Unit
        override fun hasPermission(permissionGroup: PermissionGroup): Flow<Boolean> = flowOf(true)
        override fun reportNotificationListenerState(running: Boolean) = Unit
        override fun reportAccessibilityServiceState(running: Boolean) = Unit
    }
}
