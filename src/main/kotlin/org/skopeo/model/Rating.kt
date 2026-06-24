// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A player's NTRP rating: the continuous [value] (e.g. "4.532") paired with its discrete
 * [publishedLevel] (e.g. "4.5"). In v1 the level is derived from the value on construction.
 */
@Serializable(with = RatingSerializer::class)
data class Rating(
    val value: String,
    val publishedLevel: Level,
) {
    init {
        val numericValue =
            value.toDoubleOrNull()
                ?: throw IllegalArgumentException("Rating value must be a valid number, got '$value'")
        require(value = numericValue in 1.0..7.0) { "NTRP rating must be between 1.0 and 7.0, got $value" }
    }

    companion object {
        /** Create a Rating with its published level derived from the value (v1 stateless). */
        fun fromValue(value: String): Rating = Rating(value = value, publishedLevel = Level.fromValue(value = value))
    }
}

/**
 * Serializer for [Rating]. On deserialize, the published level is used if present, otherwise
 * derived from the value — so clients can send just `value`. On serialize, both are emitted.
 */
object RatingSerializer : KSerializer<Rating> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor(serialName = "Rating") {
            element<String>(elementName = "value")
            element<Level>(elementName = "publishedLevel")
        }

    override fun serialize(
        encoder: Encoder,
        value: Rating,
    ) {
        encoder.encodeStructure(descriptor = descriptor) {
            encodeStringElement(descriptor = descriptor, index = 0, value = value.value)
            encodeSerializableElement(descriptor = descriptor, index = 1, serializer = Level.serializer(), value = value.publishedLevel)
        }
    }

    override fun deserialize(decoder: Decoder): Rating {
        return if (decoder is JsonDecoder) {
            val jsonObject = decoder.decodeJsonElement().jsonObject

            val value =
                jsonObject["value"]?.jsonPrimitive?.content
                    ?: throw SerializationException(message = "Missing 'value' field in Rating")

            val publishedLevel =
                if (jsonObject.containsKey(key = "publishedLevel")) {
                    decoder.json.decodeFromJsonElement(deserializer = Level.serializer(), element = jsonObject["publishedLevel"]!!)
                } else {
                    Level.fromValue(value = value)
                }

            Rating(value = value, publishedLevel = publishedLevel)
        } else {
            decoder.decodeStructure(descriptor = descriptor) {
                var value: String? = null
                var publishedLevel: Level? = null

                while (true) {
                    when (val index = decodeElementIndex(descriptor = descriptor)) {
                        0 -> value = decodeStringElement(descriptor = descriptor, index = 0)
                        1 ->
                            publishedLevel =
                                decodeSerializableElement(descriptor = descriptor, index = 1, deserializer = Level.serializer())
                        -1 -> break
                        else -> error(message = "Unexpected index: $index")
                    }
                }

                Rating(
                    value = value ?: throw SerializationException(message = "Missing value"),
                    publishedLevel = publishedLevel ?: throw SerializationException(message = "Missing publishedLevel"),
                )
            }
        }
    }
}
