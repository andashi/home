package de.mm20.launcher2.ui.launcher

import android.content.Context
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.icons.StaticLauncherIcon
import de.mm20.launcher2.preferences.GestureAction
import de.mm20.launcher2.preferences.preferencesModule
import de.mm20.launcher2.preferences.ui.GestureSettings
import de.mm20.launcher2.search.SavableSearchable
import de.mm20.launcher2.search.SearchableSerializer
import de.mm20.launcher2.searchable.SavableSearchableRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.lang.reflect.Proxy

/**
 * A gesture on a merged contact launches it (review on #254). The gesture
 * names Bob by his stored key; while Alice and Bob are merged, Bob resolves to
 * the merged contact, whose key is the merged one. The launcher matched the
 * resolved items back by their own key and found none: the gesture did
 * nothing until the contacts were split again.
 *
 * The repository answers as the real one does: getByKeys hands back the
 * merged contact under its merged key, getByStoredKeys under Bob's stored key.
 */
@RunWith(RobolectricTestRunner::class)
class LauncherScaffoldGestureTest {

    private val bobStored = "contact://0r2-B"
    private val merged = Item("contact://0r1-A.0r2-B")

    @Before
    fun koin() {
        val repository = Proxy.newProxyInstance(
            SavableSearchableRepository::class.java.classLoader,
            arrayOf(SavableSearchableRepository::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getByKeys" -> flowOf(listOf(merged))
                "getByStoredKeys" -> flowOf(mapOf(bobStored to merged))
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SavableSearchableRepository
        stopKoin()
        startKoin {
            androidContext(ApplicationProvider.getApplicationContext())
            modules(preferencesModule, module { single { repository } })
        }
    }

    @After
    fun stop() = stopKoin()

    @Test
    fun aGestureOnAMergedContactLaunchesTheMergedContact() = runBlocking {
        val gestures = GlobalContext.get().get<GestureSettings>()
        gestures.setSwipeLeft(GestureAction.Launch(bobStored))
        while (gestures.swipeLeft.first() != GestureAction.Launch(bobStored)) shadowOf(Looper.getMainLooper()).idle()

        val vm = LauncherScaffoldVM()
        var state = vm.gestureState.value
        val deadline = System.currentTimeMillis() + 5000
        while ((state == null || state.swipeLeftAction != GestureAction.Launch(bobStored)) && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
            state = vm.gestureState.value
        }

        assertEquals(GestureAction.Launch(bobStored), state?.swipeLeftAction)
        assertEquals("the gesture's contact", merged.key, state?.swipeLeftApp?.key)
    }

    private class Item(override val key: String) : SavableSearchable {
        override val domain = "contact"
        override val label = key
        override val preferDetailsOverLaunch = false
        override fun overrideLabel(label: String): SavableSearchable = this
        override fun launch(context: Context, options: Bundle?) = false
        override fun getPlaceholderIcon(context: Context): StaticLauncherIcon = throw NotImplementedError()
        override fun getSerializer(): SearchableSerializer = throw NotImplementedError()
    }
}
