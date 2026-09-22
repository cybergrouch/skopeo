// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.livematch

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.skopeo.common.error.ServiceError
import org.skopeo.domain.model.ScoreEvent
import org.skopeo.domain.model.ScoreState
import org.skopeo.domain.model.TeamSide

/**
 * The game-level rules (#1083): which of the scoring actions applies inside a game, and which between.
 *
 * Unit-level against `scoringAllowed`, not through the API. These are pure functions of a `ScoreState`,
 * and stating them here means each rule is one case rather than a sequence of HTTP posts whose failure
 * could be any step in the ladder. `LiveMatchApiIntegrationTest` walks that ladder end to end; this
 * says what each rung IS.
 *
 * Every refusal is checked for its *reason*, not merely for being a refusal. #1070's finding was that a
 * control which does nothing without saying why reads as a bug, and the server's sentence is what the
 * view now shows — so a message that stopped naming the next action would be a regression these tests
 * are meant to catch.
 */
class GameLevelRulesTest {
    private val inGame = ScoreState(hasStarted = true, isInGame = true, servingSide = TeamSide.TEAM1)
    private val betweenGames = ScoreState(hasStarted = true, servingSide = TeamSide.TEAM1)
    private val inTiebreak = ScoreState(hasStarted = true, isTiebreak = true, servingSide = TeamSide.TEAM1)

    private fun refusal(
        state: ScoreState,
        event: ScoreEvent,
    ): String = (scoringAllowed(state = state, event = event).shouldBeLeft() as ServiceError.Conflict).message

    private val point = ScoreEvent.PointWon(side = TeamSide.TEAM1)

    @Test
    fun `a point needs a game under way`() {
        scoringAllowed(state = inGame, event = point).shouldBeRight()
        // The quiet wrongness this state exists to prevent: banking a between-games point into
        // whatever game happens to come next.
        refusal(state = betweenGames, event = point) shouldContain "No game is under way"
    }

    @Test
    fun `a tiebreak is scored by points even though it is not a game`() {
        // isInGame stays false throughout a tiebreak, so the point rule cannot be "isInGame" alone.
        scoringAllowed(state = inTiebreak, event = point).shouldBeRight()
    }

    @Test
    fun `a game cannot be awarded before it starts`() {
        scoringAllowed(state = inGame, event = ScoreEvent.GameAwarded(side = TeamSide.TEAM1)).shouldBeRight()
        refusal(state = betweenGames, event = ScoreEvent.GameAwarded(side = TeamSide.TEAM2)) shouldContain
            "Press Start game before awarding a game"
    }

    @Test
    fun `a set cannot be awarded from inside a game`() {
        // The mis-tap that used to end a set mid-rally, and the game in progress went unbanked with it.
        refusal(state = inGame, event = ScoreEvent.SetAwarded(side = TeamSide.TEAM1)) shouldContain
            "Award that game before ending the set"
        scoringAllowed(state = betweenGames, event = ScoreEvent.SetAwarded(side = TeamSide.TEAM1)).shouldBeRight()
        // And a tiebreak ends this way, which is the whole reason Set is the control shown there.
        scoringAllowed(state = inTiebreak, event = ScoreEvent.SetAwarded(side = TeamSide.TEAM1)).shouldBeRight()
    }

    @Test
    fun `a tiebreak needs the games level, and level means equal`() {
        val sixAll = betweenGames.copy(gamesTeam1 = 6, gamesTeam2 = 6)
        val fiveAll = betweenGames.copy(gamesTeam1 = 5, gamesTeam2 = 5)
        val sixFour = betweenGames.copy(gamesTeam1 = 6, gamesTeam2 = 4)

        scoringAllowed(state = sixAll, event = ScoreEvent.TiebreakStarted).shouldBeRight()
        // Five-all is level, so a shortened format works. "Even" would have admitted 6-4 and refused
        // this, which is the reading the rule is NOT.
        scoringAllowed(state = fiveAll, event = ScoreEvent.TiebreakStarted).shouldBeRight()
        // 0-0: a deciding-set match tiebreak, played with no games at all.
        scoringAllowed(state = betweenGames, event = ScoreEvent.TiebreakStarted).shouldBeRight()

        refusal(state = sixFour, event = ScoreEvent.TiebreakStarted) shouldContain "they are 6-4"
    }

    @Test
    fun `a tiebreak cannot start from inside a game, nor a game from inside a tiebreak`() {
        refusal(state = inGame, event = ScoreEvent.TiebreakStarted) shouldContain
            "Award that game before starting a tiebreak"
        refusal(state = inTiebreak, event = ScoreEvent.GameStarted) shouldContain "A tiebreak is under way"
        refusal(state = inGame, event = ScoreEvent.GameStarted) shouldContain "already under way"
        scoringAllowed(state = betweenGames, event = ScoreEvent.GameStarted).shouldBeRight()
    }

    @Test
    fun `starting a game is refused before the match and between sets`() {
        // It inherits the outer gates rather than restating them: a game inside no set is as meaningless
        // as a point inside no game.
        refusal(state = ScoreState(), event = ScoreEvent.GameStarted) shouldContain "has not been started"
        refusal(
            state = ScoreState(hasStarted = true, isBetweenSets = true),
            event = ScoreEvent.GameStarted,
        ) shouldContain "That set has ended"
    }

    @Test
    fun `only a point needs a server`() {
        // #985 applies to points alone: a game, a set and a tiebreak are umpire declarations.
        val noServer = ScoreState(hasStarted = true, isInGame = true)
        refusal(state = noServer, event = point) shouldContain "Nobody is serving yet"
        scoringAllowed(state = noServer, event = ScoreEvent.GameAwarded(side = TeamSide.TEAM1)).shouldBeRight()
    }
}
