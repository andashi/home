package de.mm20.launcher2.config.service

import android.content.Context
import java.io.File

/**
 * Fork addition (Phase 2, ADR 0003): the single source of truth for where the
 * launcher config lives: `<external-files-dir>/config/launcher.json`.
 *
 * External *app-specific* storage is used so that `adb push` (shell user) can
 * write the file without any storage permission — no broad-storage
 * permission is requested, which keeps the app compatible with GrapheneOS
 * Storage Scopes.
 */
object ConfigLocation {
    const val ConfigDirName = "config"
    const val ConfigFileName = "launcher.json"
    const val WallpapersDirName = "wallpapers"

    /**
     * The config directory, or null when external storage is unavailable
     * (e.g. not mounted yet). Callers must treat null as "no config".
     */
    fun configDir(context: Context): File? {
        return try {
            context.getExternalFilesDir(ConfigDirName)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The config file, or null when external storage is unavailable.
     */
    fun configFile(context: Context): File? {
        return configDir(context)?.let { File(it, ConfigFileName) }
    }

    /** Uploaded wallpaper images live next to the config, one file per name. */
    fun wallpapersDir(context: Context): File? {
        return configDir(context)?.let { File(it, WallpapersDirName) }
    }

    fun wallpaperFile(context: Context, image: String): File? {
        return wallpapersDir(context)?.let { File(it, image) }
    }
}
