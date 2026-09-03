// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.model

import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The two per-match-type dials and how they line up (#108 rating factor, #459 confidence weight, #840
 * Full Match). Both are pure constants, so they are cheap to pin — and worth pinning, because a wrong
 * value here silently mis-scales every rating or confidence score rather than failing anything.
 */
class MatchTypeFactorTest {
    @Test
    fun `each match type carries its documented rating factor`() {
        MatchType.OPEN_PLAY.factor shouldBe 0.5
        MatchType.FULL_MATCH.factor shouldBe 0.8
        MatchType.TOURNAMENT.factor shouldBe 1.2
    }

    @Test
    fun `the rating factors rank casual below full match below tournament`() {
        // The ordering is the point, not the exact numbers: a full match is firmer evidence of skill than
        // a social set and softer than tournament pressure. Asserting the relation catches a re-tuning
        // that accidentally inverts the ladder, which the value assertions above would not.
        MatchType.FULL_MATCH.factor shouldBeGreaterThan MatchType.OPEN_PLAY.factor
        MatchType.TOURNAMENT.factor shouldBeGreaterThan MatchType.FULL_MATCH.factor
    }

    @Test
    fun `every match type maps to its own weight class`() {
        // Deliberately 1:1 (#840) so a type is never labelled as something it is not, even where two
        // classes currently share a weight.
        MatchType.entries.map { it.weightClass() }.distinct() shouldBe MatchType.entries.map { it.weightClass() }
        MatchType.OPEN_PLAY.weightClass() shouldBe WeightClass.OPEN_PLAY
        MatchType.FULL_MATCH.weightClass() shouldBe WeightClass.FULL_MATCH
        MatchType.TOURNAMENT.weightClass() shouldBe WeightClass.TOURNAMENT
    }
}
