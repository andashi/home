package de.mm20.launcher2.appshortcuts

import android.content.Context
import android.content.Intent
import de.mm20.launcher2.search.AppShortcut

/**
 * A shortcut offered through [Intent.ACTION_CONFIRM_PIN_SHORTCUT].
 *
 * That action is only protected as a *broadcast*. As an activity action it
 * reaches the exported `AddItemActivity` from any app on the device, with
 * extras of the caller's choosing, so nothing in [pinRequestIntent] may be
 * trusted on its own. Only the system can produce a `PinItemRequest` for it,
 * which is what `LauncherApps.getPinItemRequest` checks, and the shortcut that
 * comes back is a `ShortcutInfo` belonging to the requesting app rather than an
 * Intent this launcher would later start under its own identity.
 *
 * There is deliberately no legacy fallback here. Reading
 * `EXTRA_SHORTCUT_INTENT` off an unverified pin request let any app persist an
 * arbitrary Intent as a favorite and have the launcher start it, which is
 * andashi/home#5. Shortcuts in the pre-O shape arrive through
 * [appShortcutFromConfigActivityResult] instead, where the user picked the
 * activity that produced them.
 */
fun appShortcutFromPinRequest(context: Context, pinRequestIntent: Intent): AppShortcut? {
    return LauncherShortcut.fromPinRequestIntent(context, pinRequestIntent)
}

/**
 * A shortcut returned by a config activity the user picked in the favorites
 * editor, launched through the IntentSender from
 * `LauncherApps.getShortcutConfigActivityIntent`.
 *
 * Modern activities answer with a pin request; pre-O ones answer with
 * `EXTRA_SHORTCUT_INTENT` and friends, which is why the legacy shape is
 * accepted here and nowhere else. The result still comes from another app, so
 * the Intent it names is sanitised before it is stored - see
 * [LegacyShortcut.fromConfigActivityResult].
 */
fun appShortcutFromConfigActivityResult(context: Context, data: Intent): AppShortcut? {
    return LauncherShortcut.fromPinRequestIntent(context, data)
        ?: LegacyShortcut.fromConfigActivityResult(context, data)
}
