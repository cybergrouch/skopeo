// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.dto.match

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import org.junit.jupiter.api.Test

class MatchDtosTest {
    private fun result(
        vararg sets: SetScoreRequest,
        winnerTeamId: String? = null,
    ) = MatchResultRequest(sets = sets.toList(), winnerTeamId = winnerTeamId)

    @Test
    fun `a set won on games needs at least 4 games (#213)`() {
        // Standard and shortened-but-valid winners are accepted.
        shouldNotThrowAny { result(SetScoreRequest(team1Games = 6, team2Games = 4)) }
        shouldNotThrowAny { result(SetScoreRequest(team1Games = 4, team2Games = 3)) } // the floor, exactly
        shouldNotThrowAny { result(SetScoreRequest(team1Games = 2, team2Games = 6)) } // either side
    }

    @Test
    fun `a set won with fewer than 4 games is rejected (#213)`() {
        shouldThrow<IllegalArgumentException> { result(SetScoreRequest(team1Games = 3, team2Games = 2)) }
        shouldThrow<IllegalArgumentException> { result(SetScoreRequest(team1Games = 1, team2Games = 0)) }
    }

    @Test
    fun `the floor is checked on the result, not the set, so a bare set never throws for it (#911)`() {
        // Moved deliberately: whether the floor applies depends on the MATCH, and a set cannot know
        // whether a winner was designated. Constructing an abandoned set on its own must therefore be
        // legal — it is the result that decides.
        shouldNotThrowAny { SetScoreRequest(team1Games = 3, team2Games = 2) }
        shouldNotThrowAny { SetScoreRequest(team1Games = 1, team2Games = 0) }
    }

    @Test
    fun `a designated match winner lifts the games floor (#911)`() {
        // A retirement at 1-5, a default, or an umpire ending a set early all produce a set below the
        // floor. The designation is what says this did not end normally — the same carve-out #917 made
        // for the sets-tied guard.
        // Note 1-5 was never blocked — the winner has 5 games, which clears the floor. The floor only
        // bites below 4, which is where an early-ended set and an early retirement actually land.
        shouldNotThrowAny { result(SetScoreRequest(team1Games = 1, team2Games = 3), winnerTeamId = "t1") }
        shouldNotThrowAny { result(SetScoreRequest(team1Games = 3, team2Games = 2), winnerTeamId = "t2") }
        // 0-0 would pass anyway via the equal-games exemption; included to show the designation does
        // not change it either way.
        shouldNotThrowAny { result(SetScoreRequest(team1Games = 0, team2Games = 0), winnerTeamId = "t1") }
    }

    @Test
    fun `without a designation the floor still applies, so nothing previously rejected is now accepted`() {
        // The narrowness of the change, asserted: only a result that SAYS it ended abnormally is exempt.
        shouldThrow<IllegalArgumentException> { result(SetScoreRequest(team1Games = 3, team2Games = 2)) }
        shouldThrow<IllegalArgumentException> { result(SetScoreRequest(team1Games = 1, team2Games = 0)) }
        // Note 0-0 is NOT in this list: equal games are exempt via the tiebreak rule (#213), which
        // predates this change and is unaffected by it.
    }

    @Test
    fun `the floor applies to every set, not just the first`() {
        shouldThrow<IllegalArgumentException> {
            result(SetScoreRequest(team1Games = 6, team2Games = 4), SetScoreRequest(team1Games = 3, team2Games = 1))
        }
        // ...and a completed set alongside an abandoned one is fine once designated — the retirement shape.
        shouldNotThrowAny {
            result(
                SetScoreRequest(team1Games = 6, team2Games = 4),
                SetScoreRequest(team1Games = 1, team2Games = 3),
                winnerTeamId = "t2",
            )
        }
    }

    @Test
    fun `a tiebreak-decided set (equal games) is exempt from the games floor (#213)`() {
        // Equal games are decided by the tiebreak, e.g. a match super-tiebreak recorded as 0-0.
        shouldNotThrowAny {
            result(SetScoreRequest(team1Games = 0, team2Games = 0, tiebreakTeam1Points = 10, tiebreakTeam2Points = 8))
        }
        shouldNotThrowAny {
            result(SetScoreRequest(team1Games = 6, team2Games = 6, tiebreakTeam1Points = 7, tiebreakTeam2Points = 5))
        }
    }

    @Test
    fun `negative games are still rejected (#116)`() {
        shouldThrow<IllegalArgumentException> { SetScoreRequest(team1Games = -1, team2Games = 0) }
    }

    private fun fixture(
        team1Handicap: String? = null,
        team2Handicap: String? = null,
    ) = CreateFixtureRequest(
        matchFormat = "SINGLES",
        matchType = "OPEN_PLAY",
        matchDate = "2026-01-01",
        team1 = listOf(element = "u1"),
        team2 = listOf(element = "u2"),
        // A literal id: this suite validates the DTO's own init block, and never reaches a database.
        eventId = "00000000-0000-0000-0000-0000000000e1",
        team1Handicap = team1Handicap,
        team2Handicap = team2Handicap,
    )

    @Test
    fun `a handicap above 0 and up to 1_0 is accepted (#486)`() {
        shouldNotThrowAny { fixture(team1Handicap = "0.001") }
        shouldNotThrowAny { fixture(team2Handicap = "1.0") }
        shouldNotThrowAny { fixture(team1Handicap = "0.3", team2Handicap = "0.5") }
        shouldNotThrowAny { fixture() } // both null = no handicap
    }

    @Test
    fun `a handicap of 0, above 1_0, or non-numeric is rejected (#486)`() {
        shouldThrow<IllegalArgumentException> { fixture(team1Handicap = "0") }
        shouldThrow<IllegalArgumentException> { fixture(team1Handicap = "0.0") }
        shouldThrow<IllegalArgumentException> { fixture(team2Handicap = "1.01") }
        shouldThrow<IllegalArgumentException> { fixture(team2Handicap = "-0.2") }
        shouldThrow<IllegalArgumentException> { fixture(team1Handicap = "abc") }
    }

    @Test
    fun `SetHandicapsRequest validates the same range (#486)`() {
        shouldNotThrowAny { SetHandicapsRequest(team1Handicap = "0.4", team2Handicap = null) }
        shouldThrow<IllegalArgumentException> { SetHandicapsRequest(team1Handicap = "1.5") }
        shouldThrow<IllegalArgumentException> { SetHandicapsRequest(team2Handicap = "0") }
    }
}
