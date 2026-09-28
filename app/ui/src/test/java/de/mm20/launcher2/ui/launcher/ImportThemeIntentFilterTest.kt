package de.mm20.launcher2.ui.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The exported theme import resolves for content URIs only (#15). A filter
 * that names a MIME type and no scheme matches `file:` too, and every theme
 * file the launcher produces or receives in practice is a content URI: the
 * export writes through the Storage Access Framework or shares through a
 * FileProvider, and a file manager or the Downloads app hands content URIs.
 *
 * This checks what the filter resolves; an explicit intent to the exported
 * activity bypasses filters, so ThemeFile refuses other schemes in code too
 * (ThemeFileTest).
 */
@RunWith(RobolectricTestRunner::class)
class ImportThemeIntentFilterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun resolvesToImport(uri: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(uri), "application/vnd.de.mm20.launcher2.theme")
            .setPackage(context.packageName)
        return context.packageManager.queryIntentActivities(intent, 0)
            .any { it.activityInfo.name == ImportThemeActivity::class.java.name }
    }

    @Test
    fun `a content URI resolves to the import`() {
        // The control: without it, the refusals below would pass on a
        // manifest the test does not see at all.
        assertTrue(resolvesToImport("content://com.android.providers.downloads.documents/document/42"))
    }

    @Test
    fun `a file URI does not resolve to the import`() {
        assertFalse(resolvesToImport("file:///sdcard/Download/Lagoon.kvtheme"))
    }

    @Test
    fun `a web URI does not resolve to the import`() {
        // ContentResolver.openInputStream cannot read one either, so a filter
        // for it would be surface for a flow that cannot work.
        assertFalse(resolvesToImport("https://example.com/Lagoon.kvtheme"))
    }
}
