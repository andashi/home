package de.mm20.launcher2.data

import android.content.Context
import android.icu.text.Transliterator
import android.icu.util.ULocale
import de.mm20.launcher2.crashreporter.CrashReporter
import de.mm20.launcher2.search.StringNormalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.apache.commons.lang3.StringUtils
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal class IcuStringNormalizer(
    private val context: Context,
    /** The stored transliterator: null off, "" auto, else an ICU id (LocaleSettings.transliterator). */
    transliteratorSetting: Flow<String?>,
    private val newTransliterator: (String) -> Transliterator = Transliterator::getInstance,
) : StringNormalizer {

    override val id: String
        get() = transliteratorId.value

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val transliteratorId = transliteratorSetting
        .map {
            getTransliteratorId(it)
        }
        .stateIn(scope, SharingStarted.Eagerly, DisabledTransliteratorId)

    /**
     * Ids this device's ICU does not have. Looked up once: normalize runs for
     * every item on every keystroke, and each lookup of a missing id threw and
     * logged again (#3 slice 1). Only failures are kept; a working id is
     * created per call as before, since ICU does not promise that one instance
     * may be shared across the search coroutines.
     */
    private val unavailableIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun normalize(input: String): String {
        val id = transliteratorId.value

        val transliterator = if (id in unavailableIds) null else try {
            newTransliterator(id)
        } catch (e: IllegalArgumentException) {
            if (unavailableIds.add(id)) CrashReporter.logException(e)
            null
        }

        if (transliterator ==  null) {
            return StringUtils.stripAccents(input.lowercase(Locale.getDefault()))
                .replace("æ", "ae")
                .replace("œ", "oe")
                .replace("ß", "ss")
        }

        return transliterator.transliterate(input).lowercase()
    }

    private fun getTransliteratorId(preferenceValue: String?): String {
        val id = preferenceValue ?: return DisabledTransliteratorId

        if (id.isNotBlank()) {
            return "$id;$BaseTransliteratorId"
        }

        val locales = context.resources.configuration.locales

        if (locales.isEmpty) {
            return BaseTransliteratorId
        }

        val scripts = mutableSetOf<String>()
        val languages = mutableSetOf<String>()

        val availableIds = Transliterator.getAvailableIDs().toList()

        for (i in 0..<locales.size()) {
            val locale = locales.get(i)
            val ulocale = ULocale.addLikelySubtags(ULocale.forLocale(locale))

            val lng = ulocale.language
            val scr = ulocale.script

            if (!languages.contains(lng)) {
                val filter = "${lng}-${lng}_Latn"

                val id = availableIds.find { it.startsWith(filter) }

                if (id != null) {
                    return "$id;$BaseTransliteratorId"
                }

                languages.add(lng)
            }

            if (!scripts.contains(ulocale.script)) {
                val filter = "${scr}-Latn"

                val id = availableIds.find { it.startsWith(filter) }

                if (id != null) {
                    return "$id;$BaseTransliteratorId"
                }
                scripts.add(ulocale.script)
            }
        }
        return BaseTransliteratorId
    }

    companion object {
        /**
         * Transliterator that is used when transliteration is disabled
         */
        private const val DisabledTransliteratorId = "Latin-ASCII"

        /**
         * Transliterator that is used when no script or language is specified
         */
        private const val BaseTransliteratorId = "Any-Latin;Latin-ASCII"
    }
}