// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.mapper.entity.match

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.skopeo.repository.persistence.MatchSetEntity
import java.util.UUID

/**
 * The set winner is derived from the set's own games and tiebreak, not read from `winner_team_id`
 * (#917). These are pure-function tests: no database, no fixtures, one rule.
 *
 * As of #917 the entity carries no winner at all — V53 dropped the column — so these assert the rule
 * itself rather than that a stored value is ignored.
 */
class MatchSetWinnerDerivationTest {
    private val team1: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val team2: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")

    private fun set(
        team1Games: Int,
        team2Games: Int,
        tb1: Int? = null,
        tb2: Int? = null,
    ) = MatchSetEntity(
        setNumber = 1,
        team1Games = team1Games,
        team2Games = team2Games,
        tiebreakTeam1Points = tb1,
        tiebreakTeam2Points = tb2,
    )

    @Test
    fun `the side with more games wins the set (#917)`() {
        set(team1Games = 6, team2Games = 4)
            .toDomain(team1Id = team1, team2Id = team2)
            .winnerTeamId shouldBe team1

        set(team1Games = 2, team2Games = 6)
            .toDomain(team1Id = team1, team2Id = team2)
            .winnerTeamId shouldBe team2
    }

    @Test
    fun `a set tied on games is decided by the tiebreak points (#917)`() {
        // Production has never recorded one of these — zero tied-games sets and zero tiebreak rows — so
        // this branch is correct by inspection only, which is exactly why it wants a test.
        set(team1Games = 6, team2Games = 6, tb1 = 7, tb2 = 5)
            .toDomain(team1Id = team1, team2Id = team2)
            .winnerTeamId shouldBe team1

        set(team1Games = 6, team2Games = 6, tb1 = 5, tb2 = 7)
            .toDomain(team1Id = team1, team2Id = team2)
            .winnerTeamId shouldBe team2
    }

    @Test
    fun `a partial set still has a leader, which is what a retirement needs (#911)`() {
        // 1-5 when a player retires: the record will award the match to the opponent, but the set — and
        // so the dominance the rating is computed from — belongs to whoever was actually ahead.
        set(team1Games = 1, team2Games = 5)
            .toDomain(team1Id = team1, team2Id = team2)
            .winnerTeamId shouldBe team2
    }

    @Test
    fun `an undecidable set fails loudly rather than fabricating a winner (#917)`() {
        // Unreachable for stored data — MatchService.setWinner refuses to record it, and V53 dropped the
        // stored winner only after confirming production held none. With no column to fall back to,
        // silently picking a side would feed a fabricated result into the rating pipeline.
        shouldThrow<IllegalStateException> {
            set(team1Games = 3, team2Games = 3).toDomain(team1Id = team1, team2Id = team2)
        }.message shouldContain "no deciding tiebreak"

        // A tied tiebreak is equally undecidable.
        shouldThrow<IllegalStateException> {
            set(team1Games = 6, team2Games = 6, tb1 = 7, tb2 = 7).toDomain(team1Id = team1, team2Id = team2)
        }
    }
}
