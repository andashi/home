package de.mm20.launcher2.ui.launcher.scaffold

import android.app.Activity
import de.mm20.launcher2.preferences.GestureAction
import de.mm20.launcher2.search.SavableSearchable
import de.mm20.launcher2.ui.launcher.scaffold.components.FeedComponent
import de.mm20.launcher2.ui.launcher.scaffold.components.LaunchComponent
import de.mm20.launcher2.ui.launcher.scaffold.components.LauncherSettingsComponent
import de.mm20.launcher2.ui.launcher.scaffold.components.NotificationsComponent
import de.mm20.launcher2.ui.launcher.scaffold.components.PowerMenuComponent
import de.mm20.launcher2.ui.launcher.scaffold.components.QuickSettingsComponent
import de.mm20.launcher2.ui.launcher.scaffold.components.RecentsComponent
import de.mm20.launcher2.ui.launcher.scaffold.components.ScreenOffComponent
import de.mm20.launcher2.ui.launcher.scaffold.components.SearchComponent

/**
 * The component and animation a gesture's action opens. Out of the
 * activity so that LockoutTest asks this, not a copy of it: the lockout
 * check depends on which component each action gets (#229).
 */
internal fun scaffoldGesture(
    action: GestureAction?,
    searchable: SavableSearchable?,
    gesture: Gesture,
    searchComponent: SearchComponent,
    activity: Activity,
): ScaffoldGesture? {
    return when (action) {
        is GestureAction.Search -> ScaffoldGesture(
            component = searchComponent,
            animation = when (gesture) {
                Gesture.SwipeDown -> ScaffoldAnimation.Rubberband
                Gesture.LongPress -> ScaffoldAnimation.ZoomIn
                Gesture.DoubleTap -> ScaffoldAnimation.ZoomIn
                else -> ScaffoldAnimation.Push
            },
        )
    
        is GestureAction.Notifications -> ScaffoldGesture(
            component = NotificationsComponent,
            animation = if (gesture.orientation == null) ScaffoldAnimation.ZoomIn else ScaffoldAnimation.Push,
        )
    
        is GestureAction.QuickSettings -> ScaffoldGesture(
            component = QuickSettingsComponent,
            animation = if (gesture.orientation == null) ScaffoldAnimation.ZoomIn else ScaffoldAnimation.Push,
        )
    
        is GestureAction.Recents -> ScaffoldGesture(
            component = RecentsComponent,
            animation = if (gesture.orientation == null) ScaffoldAnimation.ZoomIn else ScaffoldAnimation.Push,
        )
    
        is GestureAction.PowerMenu -> ScaffoldGesture(
            component = PowerMenuComponent,
            animation = if (gesture.orientation == null) ScaffoldAnimation.ZoomIn else ScaffoldAnimation.Push,
        )
    
        is GestureAction.ScreenLock -> ScaffoldGesture(
            component = ScreenOffComponent,
            animation = if (gesture.orientation == null) ScaffoldAnimation.ZoomIn else ScaffoldAnimation.Push,
        )
    
        is GestureAction.Feed -> ScaffoldGesture(
            component = FeedComponent,
            animation = if (gesture.orientation == null) ScaffoldAnimation.ZoomIn else ScaffoldAnimation.Push,
        )
    
        is GestureAction.Launch if (searchable != null) -> ScaffoldGesture(
            component = LaunchComponent(
                activity,
                searchable
            ),
            animation = if (gesture.orientation == null) ScaffoldAnimation.ZoomIn else ScaffoldAnimation.Push,
        )
    
        is GestureAction.LauncherSettings -> ScaffoldGesture(
            component = LauncherSettingsComponent(activity),
            animation = if (gesture.orientation == null) ScaffoldAnimation.ZoomIn else ScaffoldAnimation.Push,
        )
    
        else -> null
    }
}
