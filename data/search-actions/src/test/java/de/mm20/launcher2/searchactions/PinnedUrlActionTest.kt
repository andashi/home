package de.mm20.launcher2.searchactions

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.searchactions.actions.OpenUrlAction
import de.mm20.launcher2.searchactions.builders.CustomWebsearchActionBuilder
import de.mm20.launcher2.searchactions.builders.SearchActionBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import androidx.core.net.toUri

/**
 * #106: a web search can be pinned to one app (Tor Browser in the Anon zone),
 * so the query never reaches the default browser.
 */
@RunWith(RobolectricTestRunner::class)
class PinnedUrlActionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val template = "https://duckduckgo.com/?q=\${1}"

    @Test
    fun `a pinned url action opens its URL in that app`() {
        OpenUrlAction("Search", "https://duckduckgo.com/?q=x".toUri(), packageName = "org.torproject.torbrowser").start(context)

        val started = shadowOf(context as Application).nextStartedActivity
        assertEquals("org.torproject.torbrowser", started.`package`)
        assertEquals("https://duckduckgo.com/?q=x", started.dataString)
    }

    /** Control: unpinned, the system picks the default browser. */
    @Test
    fun `an unpinned url action names no app`() {
        OpenUrlAction("Search", "https://duckduckgo.com/?q=x".toUri()).start(context)

        assertNull(shadowOf(context as Application).nextStartedActivity.`package`)
    }

    @Test
    fun `the builder hands the pin to the action it builds`() {
        val action = CustomWebsearchActionBuilder("Search", template, packageName = "org.torproject.torbrowser")
            .build(context, TextClassificationResult(text = "tor"))

        assertEquals("org.torproject.torbrowser", (action as OpenUrlAction).packageName)
        assertEquals("https://duckduckgo.com/?q=tor", action.url.toString())
    }

    @Test
    fun `the pin survives the database`() {
        val builder = CustomWebsearchActionBuilder("Search", template, packageName = "org.torproject.torbrowser")

        val entity = SearchActionBuilder.toDatabaseEntity(builder, 3)
        assertTrue(entity.options!!, entity.options!!.contains("org.torproject.torbrowser"))
        assertEquals(builder, SearchActionBuilder.from(context, entity))
    }
}
