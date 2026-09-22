package de.mm20.launcher2.services.widgets

import android.appwidget.AppWidgetProviderInfo

/** What the widget picker hands back: a bound AppWidget, or the built-in favorites widget. */
sealed class PickedWidget {
    /** An AppWidget the bind-and-configure flow has bound; [appWidgetId] is the host's id. */
    data class App(val appWidgetId: Int, val provider: AppWidgetProviderInfo) : PickedWidget()

    /** The favorites widget, the one built-in (ADR 0008); the grid places it as `favorites`. */
    data object Favorites : PickedWidget()
}

/** The built-in widget types the picker offers. */
object BuiltInWidgets {
    const val Favorites = "favorites"
}
