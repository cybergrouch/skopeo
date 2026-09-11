// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.livematch

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.skopeo.common.dto.livematch.LiveScoreEventRequest
import org.skopeo.common.error.ServiceError
import org.skopeo.domain.model.ScoreEvent
import org.skopeo.domain.model.TeamSide
import java.util.UUID

/**
 * The wire → [ScoreEvent] translation (#911 step 3c).
 *
 * A unit suite rather than more integration tests: every kind has to be exercised, and spinning a
 * database and a JWT for each would be slow for what is a pure mapping. The integration suite covers
 * that the *route* reaches this and turns a failure into a 400; this covers that every branch is right.
 */
class ScoreEventParserTest {
    private fun parse(
        kind: String,
        side: String? = null,
        playerId: String? = null,
    ) = ScoreEventParser.parse(request = LiveScoreEventRequest(kind = kind, side = side, playerId = playerId))

    @Test
    fun `every sided kind parses to its event`() {
        parse(kind = "POINT_WON", side = "TEAM1").shouldBeRight() shouldBe ScoreEvent.PointWon(side = TeamSide.TEAM1)
        parse(kind = "GAME_AWARDED", side = "TEAM2").shouldBeRight() shouldBe ScoreEvent.GameAwarded(side = TeamSide.TEAM2)
        parse(kind = "SET_AWARDED", side = "TEAM1").shouldBeRight() shouldBe ScoreEvent.SetAwarded(side = TeamSide.TEAM1)
        parse(kind = "RETIRED", side = "TEAM2").shouldBeRight() shouldBe ScoreEvent.Retired(side = TeamSide.TEAM2)
        parse(kind = "DEFAULTED", side = "TEAM1").shouldBeRight() shouldBe ScoreEvent.Defaulted(side = TeamSide.TEAM1)
        parse(kind = "MATCH_AWARDED", side = "TEAM2").shouldBeRight() shouldBe ScoreEvent.MatchAwarded(side = TeamSide.TEAM2)
    }

    @Test
    fun `every payload-free kind parses to its event`() {
        // SET_STARTED begins the next set after one is awarded (#984) — payload-free, like the rest.
        parse(kind = "SET_STARTED").shouldBeRight() shouldBe ScoreEvent.SetStarted
        parse(kind = "TIEBREAK_STARTED").shouldBeRight() shouldBe ScoreEvent.TiebreakStarted
        parse(kind = "MATCH_STARTED").shouldBeRight() shouldBe ScoreEvent.MatchStarted
        parse(kind = "PAUSED").shouldBeRight() shouldBe ScoreEvent.Paused
        parse(kind = "RESUMED").shouldBeRight() shouldBe ScoreEvent.Resumed
    }

    @Test
    fun `SERVER_ASSIGNED parses its player id`() {
        val player = UUID.randomUUID()
        parse(kind = "SERVER_ASSIGNED", playerId = player.toString())
            .shouldBeRight() shouldBe ScoreEvent.ServerAssigned(playerId = player)
    }

    @Test
    fun `kinds and sides are case-insensitive, since a client should not have to shout`() {
        parse(kind = "point_won", side = "team1").shouldBeRight() shouldBe ScoreEvent.PointWon(side = TeamSide.TEAM1)
    }

    @Test
    fun `a retirement names the side that CONCEDED, not the winner`() {
        // The one place the wire's meaning could be read backwards. The engine turns this into a win for
        // the opponent, so getting it inverted here would silently award every retirement the wrong way.
        val retired = parse(kind = "RETIRED", side = "TEAM2").shouldBeRight()
        retired shouldBe ScoreEvent.Retired(side = TeamSide.TEAM2)
        ScoreEngine
            .apply(state = org.skopeo.domain.model.ScoreState(), event = retired)
            .outcome
            ?.winner shouldBe TeamSide.TEAM1
    }

    @Test
    fun `an unknown kind is a validation error naming the permitted values`() {
        val error = parse(kind = "ACE").shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        // A 400 that only says "invalid" costs the caller a round trip to the docs.
        error.message shouldContain "POINT_WON"
        error.message shouldContain "ACE"
    }

    @Test
    fun `UNDONE is not parseable, because undo is a separate endpoint the server targets`() {
        parse(kind = "UNDONE").shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `an unknown side is rejected before the kind is even considered`() {
        val error = parse(kind = "POINT_WON", side = "LEFT").shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        // "LEFT" is the mistake this rejection exists for: a log in screen positions would be corrupted
        // the moment the players change ends.
        error.message shouldContain "TEAM1"
    }

    @Test
    fun `a sided kind without a side is rejected`() {
        listOf("POINT_WON", "GAME_AWARDED", "SET_AWARDED", "RETIRED", "DEFAULTED", "MATCH_AWARDED").forEach { kind ->
            val error = parse(kind = kind).shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
            error.message shouldContain kind
        }
    }

    @Test
    fun `SERVER_ASSIGNED without a player id, or with a malformed one, is rejected`() {
        parse(kind = "SERVER_ASSIGNED").shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        parse(kind = "SERVER_ASSIGNED", playerId = "not-a-uuid")
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }
}
