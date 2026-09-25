package de.mm20.launcher2.icons

import de.mm20.launcher2.config.IconsConfig

/**
 * The icon pack in effect (#86): the one configured (`icons.pack`, or chosen
 * in the settings), else Lawnicons when it is installed - provisioning
 * installs it, and the Clear look (ADR 0004) wants its line glyphs. Not
 * bundled: see #86 for why.
 */
object DefaultIconPack {
    const val Lawnicons = "app.lawnchair.lawnicons"

    /**
     * The apps' own icons, chosen: no pack and no Lawnicons fallback. Stored as
     * is and written as `icons.pack: "none"` (#3 D6); a package name always has
     * a dot, so it cannot be one. Not the same as no pack chosen (null), which
     * falls back to Lawnicons.
     */
    const val None = IconsConfig.NoPack

    suspend fun effective(configured: String?, isInstalled: suspend (String) -> Boolean): String? {
        if (configured == None) return null
        configured?.takeIf { it.isNotBlank() }?.let { return it }
        return Lawnicons.takeIf { isInstalled(it) }
    }
}
