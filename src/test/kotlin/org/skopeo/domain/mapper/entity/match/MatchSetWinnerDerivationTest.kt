// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.mapper.entity.match

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.skopeo.repository.persistence.MatchSetEntity
import java.util.UUID

/**
 * The set winner is derived from the set's own games and tiebreak, not read from `winner_team_id`
 * (#917). These are pure-function tests: no database, no fixtures, one rule.
 *
 * Every case deliberately stores a **wrong** `winnerTeamId` on the entity, so a test can only pass if
 * the mapper genuinely ignores the column. Copying it through would fail every assertion here.
 */
class MatchSetWinnerDerivationTest {
    private val team1: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val team2: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")

    /** [winnerTeamId] is set to the *opposite* of the truth so a straight field copy cannot pass. */
    private fun set(
        team1Games: Int,
        team2Games: Int,
        tb1: Int? = null,
        tb2: Int? = null,
        stored: UUID = team1,
    ) = MatchSetEntity(
        setNumber = 1,
        team1Games = team1Games,
        team2Games = team2Games,
        winnerTeamId = stored,
        tiebreakTeam1Points = tb1,
        tiebreakTeam2Points = tb2,
    )

    @Test
    fun `the side with more games wins the set, whatever the stored column says (#917)`() {
        set(team1Games = 6, team2Games = 4, stored = team2)
            .toDomain(team1Id = team1, team2Id = team2)
            .winnerTeamId shouldBe team1

        set(team1Games = 2, team2Games = 6, stored = team1)
            .toDomain(team1Id = team1, team2Id = team2)
            .winnerTeamId shouldBe team2
    }

    @Test
    fun `a set tied on games is decided by the tiebreak points (#917)`() {
        // Production has never recorded one of these — zero tied-games sets and zero tiebreak rows — so
        // this branch is correct by inspection only, which is exactly why it wants a test.
        set(team1Games = 6, team2Games = 6, tb1 = 7, tb2 = 5, stored = team2)
            .toDomain(team1Id = team1, team2Id = team2)
            .winnerTeamId shouldBe team1

        set(team1Games = 6, team2Games = 6, tb1 = 5, tb2 = 7, stored = team1)
            .toDomain(team1Id = team1, team2Id = team2)
            .winnerTeamId shouldBe team2
    }

    @Test
    fun `a partial set still has a leader, which is what a retirement needs (#911)`() {
        // 1-5 when a player retires: the record will award the match to the opponent, but the set — and
        // so the dominance the rating is computed from — belongs to whoever was actually ahead.
        set(team1Games = 1, team2Games = 5, stored = team1)
            .toDomain(team1Id = team1, team2Id = team2)
            .winnerTeamId shouldBe team2
    }

    @Test
    fun `an undecidable set falls back to the stored value rather than throwing (#917)`() {
        // Unreachable for stored data — MatchService.setWinner refuses to record it — but a read of an
        // existing match must not explode if one ever appears.
        set(team1Games = 3, team2Games = 3, stored = team2)
            .toDomain(team1Id = team1, team2Id = team2)
            .winnerTeamId shouldBe team2

        // A tied tiebreak is equally undecidable.
        set(team1Games = 6, team2Games = 6, tb1 = 7, tb2 = 7, stored = team1)
            .toDomain(team1Id = team1, team2Id = team2)
            .winnerTeamId shouldBe team1
    }
}
