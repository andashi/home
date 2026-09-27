package de.mm20.launcher2.config.service

import android.content.Context
import de.mm20.launcher2.config.ConfigParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.io.IOException

/**
 * How the file wrote each app it customizes, by the app's key: the `activity`
 * the claiming entry gave, or null where it left the activity out (review on
 * #207). A package's first launcher entry can be named either way, and only
 * the device knows which entry is first; so the store reads an app back as
 * the file wrote it, and the baseline, the device and the file all carry the
 * same form. Read in the other form, a rename on the phone came back from
 * write-back as a second entry next to the stale one.
 *
 * Replaced whole by every apply, like the list it records.
 */
interface AppNaming {
    fun observe(): Flow<Map<String, String?>>

    suspend fun replace(naming: Map<String, String?>)

    /**
     * Whether a record was ever made. After an update from a build without
     * one, it is missing although a file was applied long ago; the startup
     * check reloads once to make it (ConfigWatcher).
     */
    suspend fun recorded(): Boolean
}

/**
 * [AppNaming] in app-internal storage (`files/config/app-naming.json`), next
 * to the baseline: a write-back can run after a restart. Writes are atomic.
 * A missing or corrupt file reads as empty, which reads every app back by the
 * device's rule, as before this existed. A missing one is also "no record
 * yet", which the startup check answers with one reload.
 */
internal class FileAppNaming(context: Context) : AppNaming {
    private val file = File(context.filesDir, "config/app-naming.json")
    private val serializer = MapSerializer(String.serializer(), String.serializer().nullable)
    private val state = MutableStateFlow<Map<String, String?>?>(null)
    private val lock = Mutex()

    override fun observe(): Flow<Map<String, String?>> = flow {
        load()
        state.collect { it?.let { naming -> emit(naming) } }
    }

    override suspend fun replace(naming: Map<String, String?>) = lock.withLock {
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            file.replaceAtomically(ConfigParser.json.encodeToString(serializer, naming))
        }
        state.value = naming
    }

    /** A file that decodes: a corrupt one reads as empty, so it is no record (review on #214). */
    override suspend fun recorded(): Boolean = withContext(Dispatchers.IO) { decode() != null }

    private suspend fun load() = lock.withLock {
        if (state.value != null) return@withLock
        state.value = withContext(Dispatchers.IO) { decode() ?: emptyMap() }
    }

    /** The record, or null when there is none or it does not decode. */
    private fun decode(): Map<String, String?>? = try {
        if (file.exists()) ConfigParser.json.decodeFromString(serializer, file.readText()) else null
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: IOException) {
        null
    }
}
