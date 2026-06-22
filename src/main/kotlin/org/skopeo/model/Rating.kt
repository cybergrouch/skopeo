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
 * Represents a player's rating with its associated published level.
 *
 * The rating value is continuous (e.g., 4.532), while the published level is discrete (e.g., 4.5).
 *
 * ## v1 (Current - Stateless)
 * - Published level calculated dynamically from rating value
 * - Published level changes immediately when rating crosses boundary
 *
 * ## v2 (Future - Database-backed)
 * - Rating and published level stored together in database
 * - Published level updates on schedule (e.g., nightly, weekly)
 * - Keeps rating and its published level cohesive
 */
@Serializable(with = RatingSerializer::class)
data class Rating(
    val value: String,
    val system: RatingSystem,
    val publishedLevel: Level,
) {
    init {
        // Validate that value is a valid number
        val numericValue =
            value.toDouble()
                ?: throw IllegalArgumentException("Rating value must be a valid number, got '$value'")

        // Validate system-specific constraints
        when (system) {
            RatingSystem.NTRP -> {
                require(numericValue in 1.0..7.0) { "NTRP rating must be between 1.0 and 7.0, got $value" }
            }
            RatingSystem.UTR -> {
                require(numericValue in 1.0..16.0) { "UTR rating must be between 1.0 and 16.0, got $value" }
            }
        }

        // Validate that published level matches the rating system
        require(publishedLevel.system == system) {
            "Published level system ${publishedLevel.system} does not match rating system $system"
        }
    }

    companion object {
        /**
         * Create a Rating with its published level calculated from the rating value.
         * This is the v1 stateless approach.
         */
        fun fromValue(
            value: String,
            system: RatingSystem,
        ): Rating {
            val calculatedLevel = Level.fromValue(value, system)
            return Rating(value = value, system = system, publishedLevel = calculatedLevel)
        }
    }
}

/**
 * Custom serializer for Rating that handles both serialization and deserialization.
 *
 * On deserialization:
 * - If the JSON includes a 'publishedLevel' field, use it as-is
 * - If the JSON only has 'value' and 'system', automatically calculate the published level
 *
 * On serialization:
 * - Always include all three fields: value, system, and publishedLevel
 *
 * This allows clients to send requests without the publishedLevel field while
 * ensuring responses always include it.
 */
object RatingSerializer : KSerializer<Rating> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("Rating") {
            element<String>("value")
            element<RatingSystem>("system")
            element<Level>("publishedLevel")
        }

    override fun serialize(
        encoder: Encoder,
        value: Rating,
    ) {
        encoder.encodeStructure(descriptor) {
            encodeStringElement(descriptor, 0, value.value)
            encodeSerializableElement(descriptor, 1, RatingSystem.serializer(), value.system)
            encodeSerializableElement(descriptor, 2, Level.serializer(), value.publishedLevel)
        }
    }

    override fun deserialize(decoder: Decoder): Rating {
        // Try to decode as JSON to check if publishedLevel field exists
        return if (decoder is JsonDecoder) {
            val jsonObject = decoder.decodeJsonElement().jsonObject

            val value =
                jsonObject["value"]?.jsonPrimitive?.content
                    ?: throw SerializationException("Missing 'value' field in Rating")

            val systemStr =
                jsonObject["system"]?.jsonPrimitive?.content
                    ?: throw SerializationException("Missing 'system' field in Rating")

            val system = RatingSystem.valueOf(systemStr)

            // If publishedLevel field exists, deserialize it; otherwise calculate it
            val publishedLevel =
                if (jsonObject.containsKey("publishedLevel")) {
                    decoder.json.decodeFromJsonElement(
                        Level.serializer(),
                        jsonObject["publishedLevel"]!!,
                    )
                } else {
                    Level.fromValue(value, system)
                }

            Rating(value = value, system = system, publishedLevel = publishedLevel)
        } else {
            // Non-JSON decoder - expect all fields
            decoder.decodeStructure(descriptor) {
                var value: String? = null
                var system: RatingSystem? = null
                var publishedLevel: Level? = null

                while (true) {
                    when (val index = decodeElementIndex(descriptor)) {
                        0 -> value = decodeStringElement(descriptor, 0)
                        1 -> system = decodeSerializableElement(descriptor, 1, RatingSystem.serializer())
                        2 -> publishedLevel = decodeSerializableElement(descriptor, 2, Level.serializer())
                        -1 -> break
                        else -> error("Unexpected index: $index")
                    }
                }

                Rating(
                    value = value ?: throw SerializationException("Missing value"),
                    system = system ?: throw SerializationException("Missing system"),
                    publishedLevel = publishedLevel ?: throw SerializationException("Missing publishedLevel"),
                )
            }
        }
    }
}
