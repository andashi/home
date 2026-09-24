package de.mm20.launcher2.config.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.config.Diagnostic
import de.mm20.launcher2.config.SearchActionConfig
import de.mm20.launcher2.config.Severity
import de.mm20.launcher2.searchactions.SearchActionRepository
import de.mm20.launcher2.searchactions.builders.AppSearchActionBuilder
import de.mm20.launcher2.searchactions.builders.CallActionBuilder
import de.mm20.launcher2.searchactions.builders.CustomIntentActionBuilder
import de.mm20.launcher2.searchactions.builders.CustomWebsearchActionBuilder
import de.mm20.launcher2.searchactions.builders.SearchActionBuilder
import de.mm20.launcher2.searchactions.builders.WebsearchActionBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** #106: the device's search actions in the contract's form, both ways. */
@RunWith(RobolectricTestRunner::class)
class AndroidSearchActionStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val template = "https://duckduckgo.com/?q=\${1}"
    private val storeSearch = ComponentName("app.grapheneos.apps", "app.grapheneos.apps.SearchActivity")

    private class FakeRepository(private val context: Context) : SearchActionRepository {
        val stored = MutableStateFlow<List<SearchActionBuilder>>(emptyList())
        override fun getSearchActionBuilders(): Flow<List<SearchActionBuilder>> = stored
        override fun getBuiltinSearchActionBuilders(): List<SearchActionBuilder> =
            listOf(CallActionBuilder(context), WebsearchActionBuilder(context))
        override fun saveSearchActionBuilders(builders: List<SearchActionBuilder>) = throw NotImplementedError()
        override suspend fun replaceSearchActionBuilders(builders: List<SearchActionBuilder>) {
            stored.value = builders
        }
    }

    private val repository = FakeRepository(context)
    private val store = AndroidSearchActionStore(context, repository)

    private fun makeSearchable(component: ComponentName) {
        val pm = shadowOf(context.packageManager)
        pm.addActivityIfNotPresent(component)
        pm.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_SEARCH))
    }

    @Test
    fun `read turns the stored builders into actions`() = runTest {
        repository.stored.value = listOf(
            CallActionBuilder(context),
            WebsearchActionBuilder(context),
            CustomWebsearchActionBuilder(
                "Search", template,
                encoding = CustomWebsearchActionBuilder.QueryEncoding.FormData,
                packageName = "org.torproject.torbrowser",
            ),
            AppSearchActionBuilder("Store", Intent().setComponent(storeSearch)),
        )

        assertEquals(
            listOf(
                SearchActionConfig("call"),
                SearchActionConfig("websearch"),
                SearchActionConfig("url", "Search", template, "org.torproject.torbrowser", "form"),
                SearchActionConfig("app", "Store", packageName = "app.grapheneos.apps"),
            ),
            store.read(),
        )
    }

    /** One a user made in the settings, which the contract cannot write: read back as what it is. */
    @Test
    fun `a custom intent action reads back as its type and label`() = runTest {
        repository.stored.value = listOf(CustomIntentActionBuilder("Mine", queryKey = null, baseIntent = Intent()))

        assertEquals(listOf(SearchActionConfig("intent", "Mine")), store.read())
    }

    @Test
    fun `replace writes builders in order, the app resolved to its search activity`() = runTest {
        makeSearchable(storeSearch)

        val reports = store.replace(
            listOf(
                SearchActionConfig("call"),
                SearchActionConfig("url", "Search", template, "org.torproject.torbrowser"),
                SearchActionConfig("app", "Store", packageName = "app.grapheneos.apps"),
            ),
            "search.actions",
        )

        assertEquals(emptyList<Diagnostic>(), reports)
        val written = repository.stored.value
        assertEquals("call", written[0].key)
        assertEquals(
            CustomWebsearchActionBuilder("Search", template, packageName = "org.torproject.torbrowser"),
            written[1],
        )
        assertEquals(storeSearch, (written[2] as AppSearchActionBuilder).baseIntent.component)
        assertEquals("Store", written[2].label)
    }

    @Test
    fun `an app without a search activity is left out and reported`() = runTest {
        val reports = store.replace(
            listOf(SearchActionConfig("websearch"), SearchActionConfig("app", "None", packageName = "org.example.none")),
            "search.actions",
        )

        assertEquals(listOf("websearch"), repository.stored.value.map { it.key })
        val report = reports.single()
        assertEquals(Severity.Warning, report.severity)
        assertEquals("search-action-app-not-searchable", report.code)
        assertEquals("search.actions[1]", report.path)
    }

    /** What replace writes, read reads back the same: the differ converges. */
    @Test
    fun `replace then read is the same list, encoding made explicit`() = runTest {
        makeSearchable(storeSearch)
        val actions = listOf(
            SearchActionConfig("websearch"),
            SearchActionConfig("url", "Search", template),
            SearchActionConfig("app", "Store", packageName = "app.grapheneos.apps"),
        )

        store.replace(actions, "search.actions")

        assertEquals(
            listOf(actions[0], actions[1].copy(encoding = "url"), actions[2]),
            store.read(),
        )
    }
}
