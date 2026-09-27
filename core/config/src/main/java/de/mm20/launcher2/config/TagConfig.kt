package de.mm20.launcher2.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive

/**
 * One tag (#3 slice 4): its [name], its [icon], and the apps that carry it.
 * The contacts and shortcuts a tag holds are the phone's; [apps] are only
 * its apps, and a tag without apps is `apps` left out.
 */
@Serializable
data class TagConfig(
    val name: String,
    val icon: TagIcon? = null,
    val apps: List<TagApp> = emptyList(),
)

/**
 * An app a tag holds, named as an `apps` entry is: by package and profile,
 * and [activity] for a launcher entry other than the package's first. A
 * personal app's first entry is written as its package name alone.
 */
@Serializable(with = TagAppSerializer::class)
data class TagApp(
    val packageName: String,
    val profile: Profile = Profile.Personal,
    val activity: String? = null,
)

/**
 * A tag's icon, in the forms the tag's icon picker offers and no other: a
 * pack's drawable (its unthemed variant with [Pack.themed] false), or text,
 * an emoji as the picker's other tab gives it.
 */
@Serializable(with = TagIconSerializer::class)
sealed interface TagIcon {
    data class Pack(val pack: String, val drawable: String, val themed: Boolean = true) : TagIcon

    data class Text(val text: String) : TagIcon
}

internal object TagAppSerializer : KSerializer<TagApp> {
    @Serializable
    @SerialName("TagApp")
    private data class TagAppObject(
        val packageName: String,
        val profile: Profile = Profile.Personal,
        val activity: String? = null,
    )

    private val objectSerializer = TagAppObject.serializer()

    override val descriptor: SerialDescriptor = objectSerializer.descriptor

    override fun serialize(encoder: Encoder, value: TagApp) {
        if (value.profile == Profile.Personal && value.activity == null && encoder is JsonEncoder) {
            encoder.encodeJsonElement(JsonPrimitive(value.packageName))
            return
        }
        objectSerializer.serialize(encoder, TagAppObject(value.packageName, value.profile, value.activity))
    }

    override fun deserialize(decoder: Decoder): TagApp {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return objectSerializer.deserialize(decoder).let { TagApp(it.packageName, it.profile, it.activity) }
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> {
                if (!element.isString) throw SerializationException("A tag's app must be a package name or an object")
                TagApp(element.content)
            }

            else -> jsonDecoder.json.decodeFromJsonElement(objectSerializer, element)
                .let { TagApp(it.packageName, it.profile, it.activity) }
        }
    }
}

internal object TagIconSerializer : KSerializer<TagIcon> {
    private const val Field = "tags[].icon"

    @Serializable
    @SerialName("TagIcon")
    internal data class TagIconObject(
        val pack: String? = null,
        val drawable: String? = null,
        val themed: Boolean? = null,
        val text: String? = null,
    )

    private val objectSerializer = TagIconObject.serializer()

    override val descriptor: SerialDescriptor = objectSerializer.descriptor

    override fun serialize(encoder: Encoder, value: TagIcon) {
        val obj = when (value) {
            // Only the unthemed variant is written: themed is the default.
            is TagIcon.Pack -> TagIconObject(pack = value.pack, drawable = value.drawable, themed = false.takeIf { !value.themed })
            is TagIcon.Text -> TagIconObject(text = value.text)
        }
        objectSerializer.serialize(encoder, obj)
    }

    override fun deserialize(decoder: Decoder): TagIcon {
        val obj = objectSerializer.deserialize(decoder)
        val isPack = obj.pack != null || obj.drawable != null || obj.themed != null
        return when {
            isPack && obj.text == null && obj.pack != null && obj.drawable != null ->
                TagIcon.Pack(obj.pack, obj.drawable, themed = obj.themed ?: true)
            !isPack && obj.text != null -> TagIcon.Text(obj.text)
            else -> throw SerializationException("$Field is either a pack's drawable (pack and drawable, and themed) or text")
        }
    }
}

/**
 * The tags as a set, in a stable order: what two lists are compared on, and
 * what the read-back serves. Tags by name; a tag's apps are a set.
 */
fun List<TagConfig>.normalizedTags(): List<TagConfig> =
    map { tag ->
        tag.copy(apps = tag.apps.distinct().sortedWith(compareBy({ it.profile }, { it.packageName }, { it.activity ?: "" })))
    }.sortedBy { it.name }
