package de.mm20.launcher2.config.service

import de.mm20.launcher2.database.AppDatabase

/**
 * What an icon pack's index knows about one of its drawables (#3 slice 4,
 * PR 2). `apps[].icon` names a pack icon by pack and drawable only; the rest
 * of what the icon picker stores - the icon's type, a clock's layers,
 * whether it may be themed - comes from here, as the picker takes it from
 * the same index.
 */
interface IconPackIndex {
    suspend fun resolve(pack: String, drawable: String): Resolution

    sealed interface Resolution {
        /** As the picker would store it: [type] app, calendar or clock; [extras] a clock's layers. */
        data class Found(val type: String, val drawable: String, val extras: String?, val themed: Boolean) : Resolution

        /** The pack is not installed (or not indexed yet). */
        data object PackMissing : Resolution

        /** The pack is installed and has no such drawable. */
        data object DrawableMissing : Resolution
    }
}

internal class RoomIconPackIndex(private val database: AppDatabase) : IconPackIndex {
    override suspend fun resolve(pack: String, drawable: String): IconPackIndex.Resolution {
        val dao = database.iconDao()
        if (dao.getIconPack(pack) == null) return IconPackIndex.Resolution.PackMissing
        // The index's own lookup by name, the one the picker's search results come from.
        val icon = dao.getIcon(drawable, pack) ?: return IconPackIndex.Resolution.DrawableMissing
        return IconPackIndex.Resolution.Found(icon.type, icon.drawable ?: drawable, icon.extras, icon.themed)
    }
}
