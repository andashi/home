package de.mm20.launcher2.appshortcuts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.Intent.ShortcutIconResource
import android.content.pm.PackageManager
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Bundle
import android.util.Log
import de.mm20.launcher2.crashreporter.CrashReporter
import de.mm20.launcher2.icons.*
import de.mm20.launcher2.ktx.getDrawableOrNull
import de.mm20.launcher2.ktx.tryStartActivity
import de.mm20.launcher2.search.AppShortcut
import de.mm20.launcher2.search.SearchableSerializer

internal data class LegacyShortcut(
    val intent: Intent,
    override val label: String,
    override val appName: String?,
    val iconResource: ShortcutIconResource?,
    override val labelOverride: String? = null,
) : AppShortcut {

    override val domain = Domain
    override val key: String = "$domain://${intent.toUri(0)}"

    override fun overrideLabel(label: String): LegacyShortcut {
        return this.copy(labelOverride = label)
    }


    override fun launch(context: Context, options: Bundle?): Boolean {
        return context.tryStartActivity(intent, options)
    }

    override val componentName: ComponentName?
        get() = intent.component

    override val packageName: String?
        get() = intent.`package` ?: intent.component?.packageName

    override suspend fun loadIcon(context: Context, size: Int, themed: Boolean): LauncherIcon? {
        if (iconResource == null) return null
        val resources = try {
            context.packageManager.getResourcesForApplication(iconResource.packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            CrashReporter.logException(e)
            return null
        }
        val drawableId =
            resources.getIdentifier(iconResource.resourceName, "drawable", iconResource.packageName)
        if (drawableId == 0) return null
        val icon = resources.getDrawableOrNull(drawableId) ?: return null
        if (icon is AdaptiveIconDrawable) {
            if (themed && icon.monochrome != null) {
                return StaticLauncherIcon(
                    foregroundLayer = TintedIconLayer(
                        scale = 1f,
                        icon = icon.monochrome!!,
                    ),
                    backgroundLayer = ColorLayer()
                )
            }
            return StaticLauncherIcon(
                foregroundLayer = icon.foreground?.let {
                    StaticIconLayer(
                        icon = it,
                        scale = 1.5f,
                    )
                } ?: TransparentLayer,
                backgroundLayer = icon.background?.let {
                    StaticIconLayer(
                        icon = it,
                        scale = 1.5f,
                    )
                } ?: TransparentLayer,
            )
        }
        return StaticLauncherIcon(
            foregroundLayer = StaticIconLayer(
                icon = icon,
                scale = 1f
            ),
            backgroundLayer = TransparentLayer
        )
    }

    override fun getSerializer(): SearchableSerializer {
        return LegacyShortcutSerializer()
    }

    companion object {

        const val Domain = "legacyshortcut"

        /**
         * Flags that make an Intent carry a URI grant. A shortcut handed over
         * by another app's config activity has no reason to hold one, and the
         * launcher is the process that would issue the grant when it starts
         * the Intent - against its own providers, with its own permissions.
         * They are dropped on the way in rather than at launch time, so a
         * favorite that is already stored cannot carry them either.
         */
        private const val UriGrantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

        /**
         * How far a selector chain is followed before the Intent is refused
         * for being unreviewable. Nothing legitimate nests this deep; the cap
         * exists so a crafted chain cannot cost unbounded work or slip past
         * the walk below.
         */
        private const val MaxSelectorDepth = 8

        /**
         * Make [intent] safe to persist as a favorite and to start later from
         * the launcher process, or return null if it cannot be.
         *
         * Two rules, both aimed at the confused deputy in andashi/home#5:
         *
         * - an Intent that names this launcher is rejected outright. Starting
         *   it would reach components that are not exported precisely because
         *   no other app is meant to reach them, and a shortcut produced by
         *   another app's config activity never legitimately points back here.
         * - URI grant flags are cleared, see [UriGrantFlags].
         *
         * "Names this launcher" covers the selector chain, not just the
         * Intent's own package and component. When an Intent has no explicit
         * component, PackageManager resolves it through `getSelector()` and
         * takes the component from there, so a selector is a second, quieter
         * way to name a target - and `Intent.parseUri` reconstructs one, which
         * puts it on the deserialisation path as well.
         */
        internal fun sanitize(context: Context, intent: Intent): Intent? {
            val ownPackage = context.packageName
            var link: Intent? = intent
            var depth = 0
            while (link != null) {
                if (depth++ > MaxSelectorDepth) {
                    Log.w("MM20", "Refusing a shortcut intent with an unreasonably nested selector")
                    return null
                }
                if (link.`package` == ownPackage || link.component?.packageName == ownPackage) {
                    Log.w("MM20", "Refusing a shortcut intent that targets the launcher itself")
                    return null
                }
                link = link.selector
            }
            // The copy constructor deep-copies the selector chain, so clearing
            // the flags below cannot reach back into the caller's Intent.
            return Intent(intent).apply {
                flags = intent.flags and UriGrantFlags.inv()
            }
        }

        /**
         * Read the pre-O shortcut shape out of a config activity's result.
         *
         * Only reachable from the favorites editor, where the user picked the
         * activity that answered. It must never be fed a pin request: that
         * path is exported and unauthenticated, which is what made these
         * extras a redirection primitive in the first place.
         */
        fun fromConfigActivityResult(context: Context, data: Intent): LegacyShortcut? {
            val intent: Intent? = data.extras?.getParcelable(Intent.EXTRA_SHORTCUT_INTENT)
            val name: String? = data.extras?.getString(Intent.EXTRA_SHORTCUT_NAME)
            val iconResource: ShortcutIconResource? =
                data.extras?.getParcelable(Intent.EXTRA_SHORTCUT_ICON_RESOURCE)

            if (intent == null || name == null) {
                Log.w("MM20", "Shortcut result is missing required extras: intent=$intent, name=$name")
                return null
            }

            val safeIntent = sanitize(context, intent) ?: return null

            val packageName = safeIntent.`package` ?: safeIntent.component?.packageName

            return LegacyShortcut(
                intent = safeIntent,
                appName = packageName?.let {
                    try {
                        context.packageManager.getApplicationInfo(it, 0)
                            .loadLabel(context.packageManager).toString()
                    } catch (e: PackageManager.NameNotFoundException) {
                        null
                    }
                },
                label = name,
                iconResource = iconResource
            )
        }
    }
}
