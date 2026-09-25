package de.mm20.launcher2.ui.settings.icons

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import de.mm20.launcher2.icons.StaticLauncherIcon
import de.mm20.launcher2.search.Application
import de.mm20.launcher2.search.SavableSearchable
import de.mm20.launcher2.search.SearchableSerializer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #139: the System card previews the apps' own icons, so it needs installed
 * apps. On a fresh profile - every zone provisioning creates - the app
 * repository is still empty when the screen opens; the preview has to follow
 * it, not keep the empty first value.
 */
@RunWith(RobolectricTestRunner::class)
class SystemPreviewAppsTest {

    private val me = Process.myUserHandle()
    private val settings = app("com.android.settings", "com.android.settings.Settings")
    private val dialer = app("com.android.dialer", "com.android.dialer.main.impl.MainActivity")

    /** One subscription, held like the open screen holds it; not one per assertion. */
    private fun TestScope.shown(preview: Flow<List<Application>>): List<List<Application>> {
        val seen = mutableListOf<List<Application>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { preview.toList(seen) }
        return seen
    }

    @Test
    fun `apps the repository finds after the screen opened are previewed`() = runTest {
        val installed = MutableStateFlow<List<Application>>(emptyList())
        val seen = shown(systemPreviewApps(flowOf(emptyList()), installed, me))
        assertEquals(emptyList<Application>(), seen.last())

        installed.value = listOf(settings, dialer)

        assertEquals(listOf(settings, dialer), seen.last())
    }

    @Test
    fun `an app removed while the screen is open leaves the preview`() = runTest {
        val installed = MutableStateFlow<List<Application>>(listOf(settings, dialer))
        val seen = shown(systemPreviewApps(flowOf(emptyList()), installed, me))

        installed.value = listOf(settings)

        assertEquals(listOf(settings), seen.last())
    }

    @Test
    fun `favorites come first and are not repeated`() = runTest {
        val preview = systemPreviewApps(flowOf(listOf(dialer)), flowOf(listOf(settings, dialer)), me)

        assertEquals(listOf(dialer, settings), preview.first())
    }

    /** A work profile's or private space's apps are not this profile's System icons. */
    @Test
    fun `apps of another profile are not previewed`() = runTest {
        val work = UserHandle.getUserHandleForUid(10 * 100_000)
        val workSettings = FakeApp(settings.componentName, work)
        val preview = systemPreviewApps(flowOf(emptyList()), flowOf(listOf(workSettings, dialer)), me)

        assertEquals(listOf(dialer), preview.first())
    }

    private fun app(pkg: String, cls: String) = FakeApp(ComponentName(pkg, cls), me)

    private class FakeApp(override val componentName: ComponentName, override val user: UserHandle) : Application {
        override val key = "app://${componentName.flattenToString()}"
        override val domain = "app"
        override val label = componentName.packageName
        override val isSuspended = false
        override val versionName: String? = null
        override val canUninstall = false
        override val canShareApk = false
        override fun overrideLabel(label: String): SavableSearchable = this
        override fun launch(context: Context, options: Bundle?) = false
        override fun getPlaceholderIcon(context: Context): StaticLauncherIcon = throw NotImplementedError()
        override fun getSerializer(): SearchableSerializer = throw NotImplementedError()
        override fun uninstall(context: Context) = throw NotImplementedError()
        override fun openAppDetails(context: Context) = throw NotImplementedError()
        override fun toString() = componentName.flattenToShortString()
    }
}
