package de.mm20.launcher2.ui.launcher.grid

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import de.mm20.launcher2.ui.launcher.sheets.BindAndConfigureAppWidgetActivity

/**
 * Binds a provider through the system's dialog when the host may not bind
 * silently, and runs the provider's configuration activity if it has one:
 * the existing [BindAndConfigureAppWidgetActivity], whose result is the
 * bound host id. A third-party launcher gets the bind-widget grant only
 * from that dialog's "always allow" (the HOME role does not carry it), so
 * this is how a configured item first becomes a widget on a real device.
 */
internal class BindProviderContract : ActivityResultContract<AppWidgetProviderInfo, Int?>() {
    override fun createIntent(context: Context, input: AppWidgetProviderInfo): Intent =
        Intent(context, BindAndConfigureAppWidgetActivity::class.java)
            .putExtra(BindAndConfigureAppWidgetActivity.ExtraAppWidgetProviderInfo, input)

    override fun parseResult(resultCode: Int, intent: Intent?): Int? {
        if (resultCode != Activity.RESULT_OK) return null
        val id = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: return null
        return id.takeIf { it != AppWidgetManager.INVALID_APPWIDGET_ID }
    }
}
