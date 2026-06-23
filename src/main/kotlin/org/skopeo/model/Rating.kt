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
        require(numericValue in 1.0..7.0) { "NTRP rating must be between 1.0 and 7.0, got $value" }
    }

    companion object {
        /** Create a Rating with its published level derived from the value (v1 stateless). */
        fun fromValue(value: String): Rating = Rating(value = value, publishedLevel = Level.fromValue(value))
    }
}

/**
 * Serializer for [Rating]. On deserialize, the published level is used if present, otherwise
 * derived from the value — so clients can send just `value`. On serialize, both are emitted.
 */
object RatingSerializer : KSerializer<Rating> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("Rating") {
            element<String>("value")
            element<Level>("publishedLevel")
        }

    override fun serialize(
        encoder: Encoder,
        value: Rating,
    ) {
        encoder.encodeStructure(descriptor) {
            encodeStringElement(descriptor, 0, value.value)
            encodeSerializableElement(descriptor, 1, Level.serializer(), value.publishedLevel)
        }
    }

    override fun deserialize(decoder: Decoder): Rating {
        return if (decoder is JsonDecoder) {
            val jsonObject = decoder.decodeJsonElement().jsonObject

            val value =
                jsonObject["value"]?.jsonPrimitive?.content
                    ?: throw SerializationException("Missing 'value' field in Rating")

            val publishedLevel =
                if (jsonObject.containsKey("publishedLevel")) {
                    decoder.json.decodeFromJsonElement(Level.serializer(), jsonObject["publishedLevel"]!!)
                } else {
                    Level.fromValue(value)
                }

            Rating(value = value, publishedLevel = publishedLevel)
        } else {
            decoder.decodeStructure(descriptor) {
                var value: String? = null
                var publishedLevel: Level? = null

                while (true) {
                    when (val index = decodeElementIndex(descriptor)) {
                        0 -> value = decodeStringElement(descriptor, 0)
                        1 -> publishedLevel = decodeSerializableElement(descriptor, 1, Level.serializer())
                        -1 -> break
                        else -> error("Unexpected index: $index")
                    }
                }

                Rating(
                    value = value ?: throw SerializationException("Missing value"),
                    publishedLevel = publishedLevel ?: throw SerializationException("Missing publishedLevel"),
                )
            }
        }
    }
}
