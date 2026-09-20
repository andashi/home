package de.mm20.launcher2.preferences

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement

/**
 * Decodes a list of enum values, dropping entries this build no longer knows.
 *
 * `ignoreUnknownKeys` covers an unknown *key*; it does nothing for an unknown
 * *value* inside a list, and `coerceInputValues` only substitutes defaults for
 * properties that have one. A stored `["online", "apps"]` read by a build that
 * has since dropped `online` therefore fails the whole document, which for
 * [LauncherSettingsDataSerializer] means the corruption handler replaces every
 * setting the user has with defaults.
 *
 * Removing a value from an enum that is persisted in a list is a normal
 * consequence of removing a feature (ADR 0008), so it must not be able to wipe
 * unrelated settings. Unknown entries are skipped; the rest decode as usual.
 */
internal class TolerantEnumListSerializer<T : Any>(
    private val elementSerializer: KSerializer<T>,
) : KSerializer<List<T>> {

    private val delegate = ListSerializer(elementSerializer)

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: List<T>) {
        delegate.serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): List<T> {
        val jsonDecoder = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        val array = jsonDecoder.decodeJsonElement() as? JsonArray ?: return emptyList()
        return array.mapNotNull { element: JsonElement ->
            runCatching { jsonDecoder.json.decodeFromJsonElement(elementSerializer, element) }
                .getOrNull()
        }
    }
}

internal object KeyboardFilterBarItemListSerializer :
    KSerializer<List<KeyboardFilterBarItem>> by TolerantEnumListSerializer(
        KeyboardFilterBarItem.serializer()
    )
