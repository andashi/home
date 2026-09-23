package de.mm20.launcher2.icons

/**
 * The icon pack in effect (#86): the one configured (`icons.pack`, or chosen
 * in the settings), else Lawnicons when it is installed - provisioning
 * installs it, and the Clear look (ADR 0004) wants its line glyphs. Not
 * bundled: see #86 for why.
 */
object DefaultIconPack {
    const val Lawnicons = "app.lawnchair.lawnicons"

    suspend fun effective(configured: String?, isInstalled: suspend (String) -> Boolean): String? {
        configured?.takeIf { it.isNotBlank() }?.let { return it }
        return Lawnicons.takeIf { isInstalled(it) }
    }
}
