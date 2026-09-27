package de.mm20.launcher2.ui.launcher.search.favorites

import android.content.Context
import android.os.Bundle
import androidx.compose.ui.test.junit4.createComposeRule
import de.mm20.launcher2.data.customattrs.CustomAttributesRepository
import de.mm20.launcher2.homegrid.SearchLayout
import de.mm20.launcher2.icons.StaticLauncherIcon
import de.mm20.launcher2.search.SavableSearchable
import de.mm20.launcher2.search.SearchableSerializer
import de.mm20.launcher2.searchable.PinnedLevel
import de.mm20.launcher2.searchable.SavableSearchableRepository
import de.mm20.launcher2.services.favorites.FavoritesService
import de.mm20.launcher2.ui.common.FavoritesRow
import de.mm20.launcher2.ui.launcher.grid.ViewModelScopeRule
import de.mm20.launcher2.ui.launcher.search.ProvideSearchGrid
import de.mm20.launcher2.ui.locals.LocalGridSettings
import de.mm20.launcher2.ui.settings.KoinSettingsRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy

/**
 * Search's favorites row is drawn in the home grid's columns (#91), but it
 * fetched its frequently-used apps for upstream's grid column count: on a
 * phone 400dp or wider that count defaults to 5 while the home grid has 4,
 * so the row asked for one app too many and could wrap into a ragged extra
 * row; on a fold's inner display, for too few. The row is now fetched for
 * the widest width there can be and cut to its width where it is drawn, in
 * the frame the width is known. The setting itself stays (Dob, 2026-09-27:
 * a setting that still works stays adjustable).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SearchFavoritesColumnsTest {

    @get:Rule(order = 0)
    val koin = KoinSettingsRule()

    private val dispatcher = StandardTestDispatcher()

    @get:Rule(order = 1)
    val viewModels = ViewModelScopeRule(dispatcher)

    @get:Rule(order = 2)
    val composeRule = createComposeRule()

    private class Fake(override val key: String) : SavableSearchable {
        override val domain = "test"
        override val label = key
        override val preferDetailsOverLaunch = false
        override fun overrideLabel(label: String): SavableSearchable = this
        override fun launch(context: Context, options: Bundle?) = false
        override fun getPlaceholderIcon(context: Context): StaticLauncherIcon = throw UnsupportedOperationException()
        override fun getSerializer(): SearchableSerializer = throw UnsupportedOperationException()
    }

    private fun fakes(prefix: String, n: Int) = (1..n).map { Fake("$prefix-$it") }

    // ---- the cut: upstream's count, where the width is known ----

    private val twoPins = fakes("pin", 2)
    private val often = fakes("often", 32)

    @Test
    fun `a 4-column row with two pins takes two frequently used apps`() {
        assertEquals(4, FavoritesRow(twoPins, often, 1).forColumns(4).size)
    }

    /** The mismatch: fetched for upstream's 5 columns, the row got 3 frequently used apps for 2 free cells. */
    @Test
    fun `a 5-column row takes three, and an 8-column row six`() {
        assertEquals(5, FavoritesRow(twoPins, often, 1).forColumns(5).size)
        assertEquals(8, FavoritesRow(twoPins, often, 1).forColumns(8).size)
    }

    @Test
    fun `pins that start a second row are topped up to fill it`() {
        // Six pins in 4 columns end two into the second row: two more fill it.
        val row = FavoritesRow(fakes("pin", 6), often, 1).forColumns(4)

        assertEquals(8, row.size)
        assertEquals((fakes("pin", 6) + often.take(2)).map { it.key }, row.map { it.key })
    }

    @Test
    fun `more rows take more, and no frequently used apps leaves the pins`() {
        assertEquals(2 + 3 * 4 - 2, FavoritesRow(twoPins, often, 3).forColumns(4).size)
        assertEquals(twoPins, FavoritesRow(twoPins, emptyList(), 1).forColumns(4))
    }

    // ---- the view model: one fetch, for every width ----

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
                flowOf(fakes("often", limit))
            } else {
                flowOf(twoPins)
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
    }

    /** No width goes into the fetch at all: it is for the widest row, so a change of width needs none. */
    @Test
    fun `the row is fetched for the widest row, whatever the grid setting`() = runTest(dispatcher) {
        val vm = viewModels.track(SearchFavoritesVM())

        val row = vm.row.first()

        assertEquals(listOf(1 * FavoritesRow.MaxColumns), frequentlyUsedLimits)
        assertEquals(twoPins, row.pinned)
        assertEquals(FavoritesRow.MaxColumns, row.frequentlyUsed.size)
    }

    // ---- where the width comes from ----

    /**
     * The width search cuts with is the home layout's, which ProvideSearchGrid
     * puts where SearchColumn reads it, in the same frame: a 4-column layout
     * gives a row of four, an 8-column one a row of eight.
     */
    @Test
    fun `the row is cut to the home layout's columns inside search's grid`() {
        val row = FavoritesRow(twoPins, often, 1)
        val sizes = mutableMapOf<Int, Int>()
        composeRule.setContent {
            for (columns in listOf(4, 8)) {
                ProvideSearchGrid(SearchLayout.Single(columns)) {
                    sizes[columns] = row.forColumns(LocalGridSettings.current.columnCount).size
                }
            }
        }
        composeRule.waitForIdle()

        assertEquals(mapOf(4 to 4, 8 to 8), sizes)
    }
}
