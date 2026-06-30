// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

import kotlinx.serialization.Serializable
import java.math.BigDecimal

/**
 * A discrete NTRP rating level (e.g., 3.0, 3.5, 4.0). Levels span 0.5-wide rating bands:
 * 3.0 = [3.00, 3.50), 3.5 = [3.50, 4.00), … up to 7.0 (open-ended).
 *
 * In v1 the level is derived dynamically from the continuous rating value (it changes the
 * moment the rating crosses a boundary). USTA NTRP runs 1.0–7.0 in 0.5 steps.
 *
 * @property value The level designation (e.g., "3.0", "4.5")
 * @property minRating Minimum rating (inclusive) for this level
 * @property maxRating Maximum rating (exclusive) for this level, or null at the 7.0 ceiling
 */
@Serializable
data class Level(
    val value: String,
    val minRating: String,
    val maxRating: String?,
) {
    companion object {
        /** NTRP bands are 0.5 wide. */
        private const val BAND_WIDTH = 0.5

        /** A band's midpoint sits a quarter-point above its floor (half of [BAND_WIDTH]). */
        private val BAND_MIDPOINT_OFFSET = BigDecimal("0.25")

        /** The NTRP ceiling; the open-ended 7.0 band has no quarter-point above it. */
        private val NTRP_MAX = BigDecimal("7.0")

        /**
         * The value to store for an initial/admin rating chosen from band [band] (#206): the band
         * MIDPOINT (floor + 0.25), so the rating sits centered in its 0.5-wide band rather than at
         * the edge — a single loss won't immediately drop the player a band. [band] is snapped to its
         * floor first (a slightly off value still maps correctly); the open-ended 7.0 band clamps to
         * the 7.0 ceiling.
         */
        fun bandMidpoint(band: BigDecimal): BigDecimal {
            val floor = fromValue(value = band.toPlainString()).minRating.toBigDecimal()
            return floor.add(BAND_MIDPOINT_OFFSET).coerceAtMost(maximumValue = NTRP_MAX)
        }

        /**
         * Normalized 0..1 position of [rating] within its NTRP band (#114): band floor = 0.0,
         * band ceiling = 1.0, clamped. Drives the privacy-preserving "speed meter" — it reveals
         * roughly where in the band a player sits without exposing the exact rating. The open-ended
         * 7.0 band treats a 0.5-wide window above 7.0 as full scale.
         */
        fun positionInBand(rating: BigDecimal): Double {
            val floor = fromValue(value = rating.toPlainString()).minRating.toBigDecimal()
            val position = (rating - floor).toDouble() / BAND_WIDTH
            return position.coerceIn(minimumValue = 0.0, maximumValue = 1.0)
        }

        /**
         * Calculate the level from a rating value: round down to the nearest 0.5
         * (3.00–3.49 → 3.0, 3.50–3.99 → 3.5), clamped to the NTRP range [1.0, 7.0].
         */
        fun fromValue(value: String): Level = calculateNtrpLevel(rating = value.toBigDecimal())

        fun fromRating(rating: Rating): Level = fromValue(value = rating.value)

        private fun calculateNtrpLevel(rating: BigDecimal): Level {
            val clampedRating = rating.coerceIn(minimumValue = BigDecimal("1.0"), maximumValue = BigDecimal("7.0"))

            // Round down to nearest 0.5: floor(rating * 2) / 2.
            val levelValue =
                (clampedRating * BigDecimal("2"))
                    .toBigInteger()
                    .toBigDecimal()
                    .divide(BigDecimal("2"))
                    .setScale(1, java.math.RoundingMode.HALF_UP)

            val maxRating =
                if (levelValue >= BigDecimal("7.0")) {
                    null // 7.0 is the maximum, no upper bound
                } else {
                    (levelValue + BigDecimal("0.5")).setScale(1, java.math.RoundingMode.HALF_UP)
                }

            return Level(
                value = levelValue.toPlainString(),
                minRating = levelValue.toPlainString(),
                maxRating = maxRating?.toPlainString(),
            )
        }

        /** Check if a rating falls within a level's boundaries (min inclusive, max exclusive). */
        fun isWithinLevel(
            rating: Rating,
            level: Level,
        ): Boolean {
            val ratingValue = rating.value.toBigDecimal()
            if (ratingValue < level.minRating.toBigDecimal()) {
                return false
            }
            val maxRating = level.maxRating?.toBigDecimal()
            return maxRating == null || ratingValue < maxRating
        }
    }

    /** Human-readable description, e.g., "NTRP 4.0 (Advanced)". */
    fun getDescription(): String = "NTRP $value (${getNtrpSkillLevel(level = value.toDouble())})"

    private fun getNtrpSkillLevel(level: Double): String =
        when {
            level < 2.0 -> "New Player"
            level < 3.0 -> "Beginner"
            level < 4.0 -> "Intermediate"
            level < 5.0 -> "Advanced"
            level < 6.0 -> "Expert/College"
            level < 7.0 -> "Professional"
            else -> "World-Class"
        }
}
