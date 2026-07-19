// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto.match

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import org.junit.jupiter.api.Test

class MatchDtosTest {
    @Test
    fun `a set won on games needs at least 4 games (#213)`() {
        // Standard and shortened-but-valid winners are accepted.
        shouldNotThrowAny { SetScoreRequest(team1Games = 6, team2Games = 4) }
        shouldNotThrowAny { SetScoreRequest(team1Games = 4, team2Games = 3) } // the 4-game floor, exactly
        shouldNotThrowAny { SetScoreRequest(team1Games = 2, team2Games = 6) } // floor applies to the winner either side
    }

    @Test
    fun `a set won with fewer than 4 games is rejected (#213)`() {
        shouldThrow<IllegalArgumentException> { SetScoreRequest(team1Games = 3, team2Games = 2) }
        shouldThrow<IllegalArgumentException> { SetScoreRequest(team1Games = 1, team2Games = 0) }
    }

    @Test
    fun `a tiebreak-decided set (equal games) is exempt from the games floor (#213)`() {
        // Equal games are decided by the tiebreak, e.g. a match super-tiebreak recorded as 0-0.
        shouldNotThrowAny { SetScoreRequest(team1Games = 0, team2Games = 0, tiebreakTeam1Points = 10, tiebreakTeam2Points = 8) }
        shouldNotThrowAny { SetScoreRequest(team1Games = 6, team2Games = 6, tiebreakTeam1Points = 7, tiebreakTeam2Points = 5) }
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
