package de.mm20.launcher2.ui.launcher.grid

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Process
import android.os.UserHandle
import de.mm20.launcher2.homegrid.AppWidgetHostPort
import de.mm20.launcher2.profiles.Profile
import de.mm20.launcher2.profiles.ProfileManager
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * The real [AppWidgetHostPort] over the process-wide [AppWidgetHost] the
 * launcher already runs (`ProvideAppWidgetHost`). Profiles are named the way
 * the config names them (`personal`, `work`, `private`); an unknown or
 * missing profile means the owner.
 */
internal class AndroidAppWidgetHostPort(
    private val context: Context,
    private val host: AppWidgetHost,
) : AppWidgetHostPort, KoinComponent {

    private val profileManager: ProfileManager by inject()
    private val manager: AppWidgetManager get() = AppWidgetManager.getInstance(context)

    override fun boundIds(): List<Int> = host.appWidgetIds.toList()

    override fun allocate(): Int = host.allocateAppWidgetId()

    override fun release(id: Int) = host.deleteAppWidgetId(id)

    override fun isProviderAvailable(id: Int): Boolean = manager.getAppWidgetInfo(id) != null

    override fun bind(id: Int, widget: String, profile: String?): Boolean {
        val component = ComponentName.unflattenFromString(widget) ?: return false
        val userHandle = userHandleFor(profile) ?: return false
        return try {
            manager.bindAppWidgetIdIfAllowed(id, userHandle, component, null)
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    private fun userHandleFor(profile: String?): UserHandle? {
        val type = when (profile) {
            null, "personal" -> return Process.myUserHandle()
            "work" -> Profile.Type.Work
            "private" -> Profile.Type.Private
            else -> return null
        }
        return profileManager.getProfile(type)?.userHandle
    }
}
