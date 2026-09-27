package de.mm20.launcher2.ui.launcher.search.favorites

import android.content.Context
import android.os.Bundle
import de.mm20.launcher2.data.customattrs.CustomAttributesRepository
import de.mm20.launcher2.icons.StaticLauncherIcon
import de.mm20.launcher2.preferences.ui.UiSettings
import de.mm20.launcher2.search.SavableSearchable
import de.mm20.launcher2.search.SearchableSerializer
import de.mm20.launcher2.searchable.PinnedLevel
import de.mm20.launcher2.searchable.SavableSearchableRepository
import de.mm20.launcher2.services.favorites.FavoritesService
import de.mm20.launcher2.ui.launcher.grid.ViewModelScopeRule
import de.mm20.launcher2.ui.settings.KoinSettingsRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
 * Search's favorites row is drawn in the home grid's columns (#91), but it
 * fetched its frequently-used apps for upstream's grid column count: on a
 * phone 400dp or wider that count defaults to 5 while the home grid has 4, so
 * the row asked for one app too many and could wrap into a ragged extra row.
 * The row now fetches for the columns it is drawn in. The setting itself stays
 * (Dob, 2026-09-27: a setting that still works stays adjustable).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SearchFavoritesColumnsTest {

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

    /** The limit of each frequently-used fetch, in order. */
    private val frequentlyUsedLimits = mutableListOf<Int>()

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
            // get(includeTypes, excludeTypes, minPinnedLevel, maxPinnedLevel, minVisibility, maxVisibility, limit)
            val min = args[2] as PinnedLevel
            val max = args[3] as PinnedLevel
            if (max == PinnedLevel.FrequentlyUsed && min == PinnedLevel.FrequentlyUsed) {
                val limit = args[6] as Int
                frequentlyUsedLimits += limit
                flowOf((1..limit).map { Fake("often-$it") })
            } else {
                flowOf(pins)
            }
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
        // Upstream's grid column count, as a 400dp phone stores it by default.
        GlobalContext.get().get<UiSettings>().setGridColumnCount(5)
    }

    /** The write in [setUp] is not awaited: every test waits for it to land before it starts. */
    private suspend fun columnsSetting() = GlobalContext.get().get<UiSettings>().gridSettings.map { it.columnCount }.first { it == 5 }

    @Test
    fun `the frequently used apps fill the row in the columns it is drawn in`() = runTest(dispatcher) {
        columnsSetting()
        val vm = viewModels.track(SearchFavoritesVM())
        vm.setRowColumns(4)
        advanceUntilIdle()

        val shown = vm.favorites.first()

        // One row of 4, two of it pinned: two frequently used apps, not three.
        assertEquals(2, frequentlyUsedLimits.last())
        assertEquals(4, shown.size)
    }

    /** A fold opening to its inner display widens the row: the fetch follows. */
    @Test
    fun `a wider row fetches for its new width`() = runTest(dispatcher) {
        columnsSetting()
        val vm = viewModels.track(SearchFavoritesVM())
        // Collected throughout, as search does: a fresh first() would only
        // read the shared flow's replayed value from before the change.
        backgroundScope.launch { vm.favorites.collect {} }
        vm.setRowColumns(4)
        advanceUntilIdle()

        vm.setRowColumns(8)
        advanceUntilIdle()

        assertEquals(listOf(2, 6), frequentlyUsedLimits.distinct().takeLast(2))
    }

    /**
     * Control: until the row says how wide it is drawn - the dock, and the
     * moment before search is composed - the setting decides, as before.
     */
    @Test
    fun `without a row width the setting still decides`() = runTest(dispatcher) {
        columnsSetting()
        val vm = viewModels.track(SearchFavoritesVM())
        advanceUntilIdle()

        vm.favorites.first()

        assertEquals(5 - pins.size, frequentlyUsedLimits.last())
    }
}
