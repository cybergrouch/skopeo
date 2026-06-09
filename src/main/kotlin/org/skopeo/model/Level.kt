package org.skopeo.model

import kotlinx.serialization.Serializable
import java.math.BigDecimal

/**
 * Represents a discrete rating level (e.g., NTRP 3.0, 3.5, 4.0, etc.).
 *
 * ## Level Boundaries
 *
 * Levels are defined by rating ranges:
 * - **3.0**: [3.00, 3.50)
 * - **3.5**: [3.50, 4.00)
 * - **4.0**: [4.00, 4.50)
 * - etc.
 *
 * ## Dynamic vs Static Levels
 *
 * **v1 (Current - Stateless)**:
 * - Level calculated dynamically from current rating
 * - No persistence, purely derived from rating value
 * - Level changes immediately when rating crosses boundary
 *
 * **v2 (Future - Database-backed)**:
 * - Separate "dynamic rating" (continuous) from "published level" (discrete)
 * - Published level only updates at specific periods (e.g., nightly, weekly)
 * - Provides stability for league registration and competition
 * - Matches USTA NTRP Dynamic behavior more closely
 *
 * ## NTRP Level Scale
 *
 * USTA NTRP levels range from 1.0 to 7.0 in 0.5 increments:
 * - **1.0-1.5**: New player
 * - **2.0-2.5**: Beginner
 * - **3.0-3.5**: Intermediate
 * - **4.0-4.5**: Advanced
 * - **5.0-5.5**: Expert/College level
 * - **6.0-6.5**: Professional level
 * - **7.0**: World-class professional
 *
 * ## UTR Level Mapping
 *
 * UTR doesn't have official "levels", but for organizational purposes we use:
 * - **1.0-2.9**: Beginner
 * - **3.0-4.9**: Intermediate
 * - **5.0-6.9**: Advanced
 * - **7.0-8.9**: College level
 * - **9.0-10.9**: Pro level
 * - **11.0+**: Elite professional
 *
 * @property value The level designation (e.g., "3.0", "4.5", "10.0")
 * @property minRating Minimum rating (inclusive) for this level
 * @property maxRating Maximum rating (exclusive) for this level
 * @property system The rating system (NTRP or UTR)
 */
@Serializable
data class Level(
    val value: String,
    val minRating: String,
    val maxRating: String?,
    val system: RatingSystem,
) {
    companion object {
        /**
         * Calculate level from a rating value and system.
         *
         * For NTRP:
         * - Rounds down to nearest 0.5 increment
         * - 3.00-3.49 → 3.0
         * - 3.50-3.99 → 3.5
         * - 4.00-4.49 → 4.0
         *
         * For UTR:
         * - Rounds down to nearest 1.0 increment
         * - 9.00-9.99 → 9.0
         * - 10.00-10.99 → 10.0
         *
         * @param value The rating value as a string
         * @param system The rating system (NTRP or UTR)
         * @return The calculated level
         */
        fun fromValue(
            value: String,
            system: RatingSystem,
        ): Level {
            val ratingValue = value.toBigDecimal()

            return when (system) {
                RatingSystem.NTRP -> calculateNTRPLevel(ratingValue)
                RatingSystem.UTR -> calculateUTRLevel(ratingValue)
            }
        }

        /**
         * Calculate level from a rating value.
         *
         * For NTRP:
         * - Rounds down to nearest 0.5 increment
         * - 3.00-3.49 → 3.0
         * - 3.50-3.99 → 3.5
         * - 4.00-4.49 → 4.0
         *
         * For UTR:
         * - Rounds down to nearest 1.0 increment
         * - 9.00-9.99 → 9.0
         * - 10.00-10.99 → 10.0
         *
         * @param rating The rating to calculate level from
         * @return The calculated level
         */
        fun fromRating(rating: Rating): Level {
            return fromValue(rating.value, rating.system)
        }

        private fun calculateNTRPLevel(rating: BigDecimal): Level {
            // NTRP levels are in 0.5 increments: 1.0, 1.5, 2.0, 2.5, ..., 7.0
            // Round down to nearest 0.5

            // Clamp to valid NTRP range [1.0, 7.0]
            val clampedRating =
                rating.coerceIn(
                    BigDecimal("1.0"),
                    BigDecimal("7.0"),
                )

            // Calculate level by rounding down to nearest 0.5
            // Formula: floor(rating * 2) / 2
            val levelValue =
                (clampedRating * BigDecimal("2"))
                    .toBigInteger()
                    .toBigDecimal()
                    .divide(BigDecimal("2"))
                    .setScale(1, java.math.RoundingMode.HALF_UP)

            val levelStr = levelValue.toPlainString()

            // Calculate boundaries
            val minRating = levelValue
            val maxRating =
                if (levelValue >= BigDecimal("7.0")) {
                    null // 7.0 is the maximum, no upper bound
                } else {
                    (levelValue + BigDecimal("0.5")).setScale(1, java.math.RoundingMode.HALF_UP)
                }

            return Level(
                value = levelStr,
                minRating = minRating.toPlainString(),
                maxRating = maxRating?.toPlainString(),
                system = RatingSystem.NTRP,
            )
        }

        private fun calculateUTRLevel(rating: BigDecimal): Level {
            // UTR levels are in 1.0 increments: 1.0, 2.0, 3.0, ..., 16.0+
            // Round down to nearest 1.0

            // Clamp minimum to 1.0 (no maximum for UTR)
            val clampedRating = rating.coerceAtLeast(BigDecimal("1.0"))

            // Calculate level by rounding down to nearest integer
            val levelValue =
                clampedRating.toBigInteger().toBigDecimal()
                    .setScale(1, java.math.RoundingMode.HALF_UP)

            val levelStr = levelValue.toPlainString()

            // Calculate boundaries
            val minRating = levelValue
            val maxRating =
                if (levelValue >= BigDecimal("16.0")) {
                    null // 16.0+ is open-ended
                } else {
                    (levelValue + BigDecimal("1.0")).setScale(1, java.math.RoundingMode.HALF_UP)
                }

            return Level(
                value = levelStr,
                minRating = minRating.toPlainString(),
                maxRating = maxRating?.toPlainString(),
                system = RatingSystem.UTR,
            )
        }

        /**
         * Check if a rating is within a specific level's boundaries.
         *
         * @param rating The rating to check
         * @param level The level to check against
         * @return true if rating is within level boundaries
         */
        fun isWithinLevel(
            rating: Rating,
            level: Level,
        ): Boolean {
            require(rating.system == level.system) {
                "Rating system ${rating.system} does not match level system ${level.system}"
            }

            val ratingValue = rating.value.toBigDecimal()
            val minRating = level.minRating.toBigDecimal()

            // Check minimum bound (inclusive)
            if (ratingValue < minRating) {
                return false
            }

            // Check maximum bound (exclusive), if it exists
            val maxRating = level.maxRating?.toBigDecimal()
            if (maxRating != null && ratingValue >= maxRating) {
                return false
            }

            // If maxRating is null (open-ended), rating is within level
            return true
        }
    }

    /**
     * Get a human-readable description of this level.
     *
     * @return Description string (e.g., "NTRP 4.0 (Advanced)")
     */
    fun getDescription(): String {
        val skillLevel =
            when (system) {
                RatingSystem.NTRP -> getNTRPSkillLevel(value.toDouble())
                RatingSystem.UTR -> getUTRSkillLevel(value.toDouble())
            }

        return "$system $value ($skillLevel)"
    }

    private fun getNTRPSkillLevel(level: Double): String =
        when {
            level < 2.0 -> "New Player"
            level < 3.0 -> "Beginner"
            level < 4.0 -> "Intermediate"
            level < 5.0 -> "Advanced"
            level < 6.0 -> "Expert/College"
            level < 7.0 -> "Professional"
            else -> "World-Class"
        }

    private fun getUTRSkillLevel(level: Double): String =
        when {
            level < 3.0 -> "Beginner"
            level < 5.0 -> "Intermediate"
            level < 7.0 -> "Advanced"
            level < 9.0 -> "College"
            level < 11.0 -> "Professional"
            else -> "Elite Professional"
        }
}
