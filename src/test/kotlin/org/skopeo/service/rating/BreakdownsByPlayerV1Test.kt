// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.rating

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.skopeo.service.calculator.AuditEntry
import io.kotest.matchers.maps.shouldBeEmpty as mapShouldBeEmpty

/**
 * Pure-function coverage for [RatingCalculationService.breakdownsByPlayer]'s v1 `netByPlayer` arm.
 *
 * The wired calculator is v2 (it emits one per-set entry per player, always populating `setIndex`),
 * so the v1 `net` partition is never exercised end-to-end. `breakdownsByPlayer` is a pure function,
 * though, so this feeds it a synthetic v1-style audit list — net entries keyed by `playerId` with a
 * `dominance` key and NO `setIndex` — and asserts it produces the net-field breakdown with no sets.
 */
class BreakdownsByPlayerV1Test {
    private val service = RatingCalculationService()

    /**
     * A synthetic v1 net audit entry: it carries `dominance` (so the filter keeps it) and no
     * `setIndex` (so the partition routes it to the `netByPlayer` arm). Only [playerId], [dominance]
     * and [isUpset] vary across cases; the remaining factors are fixed and asserted verbatim.
     */
    private fun v1Entry(
        playerId: String,
        dominance: String,
        isUpset: String,
    ): AuditEntry =
        AuditEntry(
            message = "v1 net adjustment for $playerId",
            context =
                mapOf<String, Any>(
                    "playerId" to playerId,
                    "dominance" to dominance,
                    "scale" to "1.10",
                    "ratingGap" to "0.30",
                    "normalizedGap" to "0.05",
                    "competitiveThresholdPct" to "0.083",
                    "isUpset" to isUpset,
                    "upsetMultiplier" to "2.0",
                    "kFactor" to "0.16",
                ),
        )

    @Test
    fun `maps a v1 net entry to the net-field breakdown with no sets`() {
        val playerId = "11111111-1111-1111-1111-111111111111"
        val audit =
            listOf(
                // A match-level entry carrying neither playerId nor dominance is dropped by the filter.
                AuditEntry(message = "match started", context = mapOf<String, Any>("matchId" to "m-1", "phase" to "start")),
                v1Entry(playerId = playerId, dominance = "0.75", isUpset = "true"),
            )

        val result = service.breakdownsByPlayer(audit = audit)

        result shouldHaveSize 1
        result shouldContainKey playerId
        val breakdown = result.getValue(key = playerId)
        breakdown.dominance shouldBe "0.75"
        breakdown.scale shouldBe "1.10"
        breakdown.ratingGap shouldBe "0.30"
        breakdown.normalizedGap shouldBe "0.05"
        breakdown.competitiveThresholdPct shouldBe "0.083"
        breakdown.isUpset shouldBe true
        breakdown.upsetMultiplier shouldBe "2.0"
        breakdown.kFactor shouldBe "0.16"
        breakdown.sets.shouldBeEmpty()
    }

    @Test
    fun `keys each v1 net entry by its own player id`() {
        val alice = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        val bob = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        val audit =
            listOf(
                v1Entry(playerId = alice, dominance = "0.60", isUpset = "false"),
                v1Entry(playerId = bob, dominance = "0.40", isUpset = "true"),
            )

        val result = service.breakdownsByPlayer(audit = audit)

        result shouldHaveSize 2
        result shouldContainKey alice
        result shouldContainKey bob
        result.getValue(key = alice).dominance shouldBe "0.60"
        result.getValue(key = alice).isUpset shouldBe false
        result.getValue(key = bob).dominance shouldBe "0.40"
        result.getValue(key = bob).isUpset shouldBe true
        // The v1 arm never populates the per-set list for either player.
        result.getValue(key = alice).sets.shouldBeEmpty()
        result.getValue(key = bob).sets.shouldBeEmpty()
    }

    @Test
    fun `ignores entries without a dominance key`() {
        val audit =
            listOf(
                AuditEntry(
                    message = "player id but no dominance",
                    context = mapOf<String, Any>("playerId" to "cccccccc-cccc-cccc-cccc-cccccccccccc", "phase" to "warmup"),
                ),
                AuditEntry(message = "no context at all"),
            )

        service.breakdownsByPlayer(audit = audit).mapShouldBeEmpty()
    }

    @Test
    fun `parses a false upset flag on a v1 net entry`() {
        val playerId = "dddddddd-dddd-dddd-dddd-dddddddddddd"
        val audit =
            listOf(
                v1Entry(playerId = playerId, dominance = "0.50", isUpset = "false"),
                AuditEntry(message = "trailing match-level note", context = mapOf<String, Any>("matchId" to "m-2", "phase" to "end")),
            )

        val breakdown = service.breakdownsByPlayer(audit = audit).getValue(key = playerId)

        breakdown.isUpset shouldBe false
        breakdown.dominance shouldBe "0.50"
        breakdown.upsetMultiplier shouldBe "2.0"
        // The net arm never produces a v2 set breakdown.
        breakdown.sets.shouldBeEmpty()
    }
}
