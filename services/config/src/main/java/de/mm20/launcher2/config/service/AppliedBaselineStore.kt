package de.mm20.launcher2.config.service

import android.content.Context
import de.mm20.launcher2.config.ConfigParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException

/**
 * What the file with hash [configSha256] produced once applied: the effective
 * state served right after its reload or self-write (#3 slice 4, D).
 */
@Serializable
data class AppliedBaseline(
    val configSha256: String,
    val effective: JsonObject,
)

/**
 * Persists the [AppliedBaseline] in app-internal storage
 * (`files/config/applied-baseline.json`), next to the reload report.
 *
 * A write-back compares the device with it, not with the file's literal
 * values: the launcher applies some values differently from how they are
 * written (a widget height clamped to what the widget allows, #140), and only
 * the state after the apply knows that. Nothing in it is not already in the
 * config file and the settings.
 *
 * Writes are atomic (temp file, then rename). A missing or corrupt baseline
 * reads back as null, and a write-back without one skips the file.
 */
class AppliedBaselineStore(context: Context) {
    private val file = File(context.filesDir, "config/applied-baseline.json")

    suspend fun save(baseline: AppliedBaseline) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(ConfigParser.json.encodeToString(AppliedBaseline.serializer(), baseline))
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Could not replace ${file.name}")
        }
    }

    suspend fun read(): AppliedBaseline? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        try {
            ConfigParser.json.decodeFromString(AppliedBaseline.serializer(), file.readText())
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: IOException) {
            null
        }
    }
}
