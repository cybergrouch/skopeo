// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.rating

import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.skopeo.domain.model.CalculationBreakdown
import org.skopeo.domain.model.CalibrationStatus
import org.skopeo.domain.model.PlayerChange
import java.math.BigDecimal
import java.util.UUID

/**
 * The calibration asymmetry (#881 PR 2): whose rating actually moves.
 *
 * Tested against the pure rule rather than through the database, because the rule is the part that can be
 * wrong in a way nothing else catches — and keeping it a free function is what let the calculator stay
 * untouched and zero-sum.
 *
 * The four rows of the decision table are asserted individually. The doubles row is the one worth
 * reading twice: it is not special-cased anywhere, it falls out of the same single predicate.
 */
class CalibrationAsymmetryTest {
    private fun change(
        userId: UUID,
        from: String,
        to: String,
    ) = PlayerChange(
        userId = userId,
        previousRating = BigDecimal(from),
        newRating = BigDecimal(to),
        change = BigDecimal(to).subtract(BigDecimal(from)),
        percentChange = BigDecimal("1.5"),
        previousLevel = "4.0",
        newLevel = "4.5",
        levelChanged = true,
        breakdown =
            CalculationBreakdown(
                dominance = "0.5",
                scale = "1.0",
                ratingGap = "0.0",
                normalizedGap = "0.0",
                competitiveThresholdPct = "8.3",
                isUpset = false,
                upsetMultiplier = "1.0",
                kFactor = "0.16",
            ),
    )

    private fun calibrating(vararg userIds: UUID) =
        userIds.associateWith { CalibrationStatus(inCalibration = true, matchesRated = 1, matchesRequired = 10) }

    private fun settled(vararg userIds: UUID) =
        userIds.associateWith { CalibrationStatus(inCalibration = false, matchesRated = 10, matchesRequired = 10) }

    @Test
    fun `calibrating versus settled moves only the calibrating player (#881)`() {
        val rookie = UUID.randomUUID()
        val veteran = UUID.randomUUID()
        val changes = listOf(change(userId = rookie, from = "4.0", to = "4.08"), change(userId = veteran, from = "4.0", to = "3.92"))

        val applied = withCalibrationApplied(changes = changes, calibration = calibrating(rookie) + settled(veteran))

        val forRookie = applied.single { it.userId == rookie }
        val forVeteran = applied.single { it.userId == veteran }
        // The whole point: the guess moves, the settled rating does not.
        forRookie.suppressed.shouldBeFalse()
        forRookie.newRating shouldBe BigDecimal("4.08")
        forVeteran.suppressed.shouldBeTrue()
        // Truthful, not counterfactual — the rating genuinely did not move, so that is what is reported.
        forVeteran.newRating shouldBe forVeteran.previousRating
        forVeteran.change shouldBe BigDecimal.ZERO
        forVeteran.levelChanged.shouldBeFalse()
        forVeteran.newLevel shouldBe forVeteran.previousLevel
        // ...but what WOULD have happened is still inspectable.
        forVeteran.breakdown shouldBe changes.single { it.userId == veteran }.breakdown
    }

    @Test
    fun `two calibrating players both move, since neither rating is worth protecting (#881)`() {
        val one = UUID.randomUUID()
        val two = UUID.randomUUID()
        val changes = listOf(change(userId = one, from = "4.0", to = "4.08"), change(userId = two, from = "4.0", to = "3.92"))

        val applied = withCalibrationApplied(changes = changes, calibration = calibrating(one, two))

        applied.forEach { withClue(clue = "${it.userId}") { it.suppressed.shouldBeFalse() } }
        // Zero-sum survives in this case, incidentally: nothing was withheld.
        applied.fold(initial = BigDecimal.ZERO) { sum, c -> sum.add(c.change) } shouldBe BigDecimal("0.00")
    }

    @Test
    fun `nobody calibrating leaves every change exactly as computed (#881)`() {
        val one = UUID.randomUUID()
        val two = UUID.randomUUID()
        val changes = listOf(change(userId = one, from = "4.0", to = "4.08"), change(userId = two, from = "4.0", to = "3.92"))

        val applied = withCalibrationApplied(changes = changes, calibration = settled(one, two))

        // Identity, not merely equivalence: an ordinary match must be untouched by this code path.
        applied shouldBe changes
    }

    @Test
    fun `an unknown calibration status is treated as settled (#881)`() {
        val one = UUID.randomUUID()
        val two = UUID.randomUUID()
        val changes = listOf(change(userId = one, from = "4.0", to = "4.08"), change(userId = two, from = "4.0", to = "3.92"))

        // A missing entry must not read as "calibrating": that would suppress the other side of an
        // ordinary match on the strength of a lookup that simply failed.
        val applied = withCalibrationApplied(changes = changes, calibration = emptyMap())

        applied shouldBe changes
    }

    @Test
    fun `in doubles only the calibrating partner's share is applied (#881)`() {
        val rookie = UUID.randomUUID()
        val partner = UUID.randomUUID()
        val opponentA = UUID.randomUUID()
        val opponentB = UUID.randomUUID()
        val changes =
            listOf(
                change(userId = rookie, from = "4.0", to = "4.06"),
                change(userId = partner, from = "4.4", to = "4.46"),
                change(userId = opponentA, from = "4.2", to = "4.14"),
                change(userId = opponentB, from = "4.2", to = "4.14"),
            )

        val applied =
            withCalibrationApplied(
                changes = changes,
                calibration = calibrating(rookie) + settled(partner, opponentA, opponentB),
            )

        // The agreed exception to §7.1 of the algorithm doc: the team delta and the mean-normalized split
        // are computed exactly as usual, but only the calibrating partner's share lands — so the team mean
        // does NOT move by Δ_team. Note this needs no doubles-specific code; it is the same predicate.
        applied.single { it.userId == rookie }.suppressed.shouldBeFalse()
        listOf(partner, opponentA, opponentB).forEach { id ->
            withClue(clue = "$id should be suppressed") { applied.single { it.userId == id }.suppressed.shouldBeTrue() }
        }
        // And the pool is no longer conserved — rating is created here, deliberately.
        applied.fold(initial = BigDecimal.ZERO) { sum, c -> sum.add(c.change) } shouldBe BigDecimal("0.06")
    }

    @Test
    fun `a suppressed player stays in the result rather than disappearing from the preview (#881)`() {
        val rookie = UUID.randomUUID()
        val veteran = UUID.randomUUID()
        val changes = listOf(change(userId = rookie, from = "4.0", to = "4.08"), change(userId = veteran, from = "4.0", to = "3.92"))

        val applied = withCalibrationApplied(changes = changes, calibration = calibrating(rookie) + settled(veteran))

        // Dropping the row would make a dry-run preview silently account for fewer players than played,
        // which reads as a bug in the calculation rather than as a deliberate suppression.
        applied.map { it.userId }.toSet() shouldBe setOf(rookie, veteran)
        applied.size shouldBe 2
    }
}
