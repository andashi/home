package de.mm20.launcher2.ui.launcher.grid

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.grid.SizeLimits
import de.mm20.launcher2.homegrid.FormFactor
import de.mm20.launcher2.homegrid.GridItemLimits
import de.mm20.launcher2.homegrid.HomeGridItem
import de.mm20.launcher2.homegrid.HomeGridSeeding
import de.mm20.launcher2.homegrid.MeasuredGridRows
import de.mm20.launcher2.permissions.PermissionGroup
import de.mm20.launcher2.permissions.PermissionsManager
import de.mm20.launcher2.preferences.preferencesModule
import de.mm20.launcher2.preferences.ui.UiSettings
import de.mm20.launcher2.profiles.ProfileManager
import de.mm20.launcher2.widgets.WidgetRepository
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.rules.ExternalResource
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
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
        seedDefaultSettingsFile()
        repository = FakeHomeGridRepository(items)
        writeBack = FakeWriteBack(repository)
        stopKoin()
        startKoin {
            androidContext(ApplicationProvider.getApplicationContext())
            modules(
                preferencesModule,
                module {
                    single<PermissionsManager> { GrantedPermissions() }
                    single { ProfileManager(androidContext(), get()) }
                    single<de.mm20.launcher2.homegrid.HomeGridRepository> { repository }
                    single<de.mm20.launcher2.homegrid.FormFactorDetector> { FakeFormFactorDetector(formFactor) }
                    single { MeasuredGridRows() }
                    single<HomeGridSeeding> { FakeSeeding() }
                    single<WidgetRepository> { emptyColumn }
                    single<de.mm20.launcher2.homegrid.HomeGridWriteBack> { writeBack }
                    single<GridItemLimits> { GridItemLimits { item, _ -> limits[item.id] ?: SizeLimits.Unbounded } }
                },
            )
        }
    }

    override fun after() {
        stopKoin()
    }

    /** A view model over the rule's fakes, the way `HomeGridVM.factory` would build it. */
    fun viewModel(): HomeGridVM {
        val koin = GlobalContext.get()
        return HomeGridVM(
            repository = repository,
            uiSettings = koin.get<UiSettings>(),
            formFactorDetector = FakeFormFactorDetector(formFactor),
            measuredRows = koin.get(),
            seeder = koin.get<HomeGridSeeding>(),
            widgetRepository = emptyColumn,
            writeBack = writeBack,
            itemLimits = koin.get(),
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
