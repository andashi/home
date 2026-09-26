package de.mm20.launcher2.ui.launcher.grid

import android.content.Context
import android.os.Bundle
import de.mm20.launcher2.data.customattrs.CustomAttributesRepository
import de.mm20.launcher2.icons.StaticLauncherIcon
import de.mm20.launcher2.preferences.search.FavoritesSettings
import de.mm20.launcher2.search.SavableSearchable
import de.mm20.launcher2.search.SearchableSerializer
import de.mm20.launcher2.searchable.PinnedLevel
import de.mm20.launcher2.searchable.SavableSearchableRepository
import de.mm20.launcher2.services.favorites.FavoritesService
import de.mm20.launcher2.ui.settings.KoinSettingsRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy

/**
 * The dock shows the pins and nothing else (D2). It inherited the search
 * favorites' flow, which appends frequently-used apps after the pins while
 * that setting is on (the default), so they filled a dock's empty slots:
 * a provisioned dock showed apps the file never listed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FavoritesGridVMTest {

    @get:Rule(order = 0)
    val koin = KoinSettingsRule()

    private val dispatcher = StandardTestDispatcher()

    @get:Rule(order = 1)
    val viewModels = ViewModelScopeRule(dispatcher)

    private class Fake(override val key: String) : SavableSearchable {
        override val domain = "test"
        override val label = key
        override val preferDetailsOverLaunch = false
        override fun overrideLabel(label: String): SavableSearchable = this
        override fun launch(context: Context, options: Bundle?) = false
        override fun getPlaceholderIcon(context: Context): StaticLauncherIcon = throw UnsupportedOperationException()
        override fun getSerializer(): SearchableSerializer = throw UnsupportedOperationException()
    }

    private val pins = listOf(Fake("pin-a"), Fake("pin-b"))
    private val frequentlyUsed = listOf(Fake("often-1"), Fake("often-2"), Fake("often-3"))

    /** Answers only what the favorites flow asks; anything else fails the test loudly. */
    private inline fun <reified T> stub(crossinline answer: (name: String, args: Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "stub ${T::class.java.simpleName}"
                else -> answer(method.name, args ?: emptyArray())
            }
        } as T

    @Before
    fun setUp() {
        val searchables = stub<SavableSearchableRepository> { name, args ->
            check(name == "get") { "unexpected call $name" }
            // get(includeTypes, excludeTypes, minPinnedLevel, maxPinnedLevel, ...)
            val min = args[2] as PinnedLevel
            val max = args[3] as PinnedLevel
            flowOf(if (max == PinnedLevel.FrequentlyUsed && min == PinnedLevel.FrequentlyUsed) frequentlyUsed else pins)
        }
        val attributes = stub<CustomAttributesRepository> { name, _ ->
            check(name == "getCustomLabels") { "unexpected call $name" }
            flowOf(emptyList<Any>())
        }
        loadKoinModules(
            module {
                single { FavoritesService(searchables) }
                single<CustomAttributesRepository> { attributes }
            }
        )
    }

    @Test
    fun `with frequently used on, the dock shows the pins and nothing unpinned`() = runTest(dispatcher) {
        val settings: FavoritesSettings = GlobalContext.get().get()
        assertTrue("the fixture needs frequently used on", settings.frequentlyUsed.first())
        val vm = viewModels.track(FavoritesGridVM())

        val shown = vm.favorites.first()

        assertEquals(pins.map { it.key }, shown.map { it.key })
    }

    /**
     * Control: search's favorites row, the same flow, still appends the
     * frequently-used apps. The fix is the dock's, not the feature's.
     */
    @Test
    fun `search favorites still show the frequently used apps after the pins`() = runTest(dispatcher) {
        val vm = viewModels.track(de.mm20.launcher2.ui.launcher.search.favorites.SearchFavoritesVM())

        val shown = vm.favorites.first()

        assertEquals((pins + frequentlyUsed).map { it.key }, shown.map { it.key })
    }
}
