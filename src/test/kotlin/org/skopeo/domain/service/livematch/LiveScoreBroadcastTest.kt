// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.livematch

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.skopeo.common.dto.livematch.LiveMatchResponse
import org.skopeo.common.dto.livematch.LiveOutcomeResponse
import org.skopeo.common.dto.livematch.LiveSetResponse

/**
 * The spectator projection (#911 §6), as a pure function — no Firestore, no network.
 *
 * The thing most worth pinning here is what is **left out**. Spectators receive state, never keystrokes,
 * and never the umpire's business: no action log and no scorer id. A projection that quietly grew one
 * would put internal detail on a public, world-readable document.
 */
class LiveScoreBroadcastTest {
    private fun view(
        sequence: Long = 7,
        scorerId: String? = "ump-1",
        outcome: LiveOutcomeResponse? = null,
        sets: List<LiveSetResponse> = emptyList(),
    ) = LiveMatchResponse(
        matchId = "m-1",
        sequence = sequence,
        scorerId = scorerId,
        hasStarted = true,
        isPaused = false,
        isTiebreak = false,
        serverId = "p-1",
        pointsTeam1 = "40",
        pointsTeam2 = "30",
        gamesTeam1 = 4,
        gamesTeam2 = 3,
        sets = sets,
        elapsedSeconds = 125,
        isRunning = true,
        outcome = outcome,
    )

    @Test
    fun `the projection carries the scoreboard`() {
        val payload = view().toBroadcast(publicCode = "MTCH01")

        payload.publicCode shouldBe "MTCH01"
        payload.sequence shouldBe 7L
        payload.pointsTeam1 shouldBe "40"
        payload.pointsTeam2 shouldBe "30"
        payload.gamesTeam1 shouldBe 4
        payload.gamesTeam2 shouldBe 3
        payload.hasStarted shouldBe true
        payload.serverId shouldBe "p-1"
    }

    @Test
    fun `the document carries the public code and NOT the internal id`() {
        // The collection is world-readable, and the internal id is withheld from ordinary viewers
        // precisely so it is not an alternative public identifier (#776/#911). Publishing it here would
        // hand it to everyone through the back door — and a spectator could not use it anyway, since the
        // page they arrived from is addressed by code.
        val document = view().toBroadcast(publicCode = "MTCH01").asDocument()

        document["publicCode"] shouldBe "MTCH01"
        document.keys.contains(element = "matchId") shouldBe false
        document.values.none { it == "m-1" } shouldBe true
    }

    @Test
    fun `the clock rides along, so a spectator sees how long the match has been going`() {
        // Two scalars, included deliberately: it is exactly what a spectator wants and reveals nothing
        // the finished match page would not, so it does not meaningfully widen the small public surface.
        val document = view().toBroadcast(publicCode = "MTCH01").asDocument()
        document["elapsedSeconds"] shouldBe 125L
        document["isRunning"] shouldBe true
    }

    @Test
    fun `the projection does NOT carry the scorer, because that is the umpire's business`() {
        // The document is world-readable. Who is holding the phone is not a spectator's concern, and
        // once it is in the payload it is on a public surface.
        val payload = view(scorerId = "ump-1").toBroadcast(publicCode = "MTCH01")
        payload.asDocument().keys.contains(element = "scorerId") shouldBe false
    }

    @Test
    fun `the document has no action log, so undo reaches spectators as a changed score`() {
        // §6: the broadcast is state, not keystrokes. A spectator should see the score go back, not an
        // operation they have to interpret — and the append-only log never has to be public.
        val keys = view().toBroadcast(publicCode = "MTCH01").asDocument().keys
        keys.none { it.contains(other = "log", ignoreCase = true) } shouldBe true
        keys.none { it.contains(other = "event", ignoreCase = true) } shouldBe true
    }

    @Test
    fun `the sequence rides along so a stale client can tell it is behind`() {
        val document = view(sequence = 42).toBroadcast(publicCode = "MTCH01").asDocument()
        document.keys shouldContain "sequence"
        document["sequence"] shouldBe 42L
    }

    @Test
    fun `banked sets carry their tiebreak points`() {
        val payload =
            view(
                sets =
                    listOf(
                        element =
                            LiveSetResponse(
                                gamesTeam1 = 6,
                                gamesTeam2 = 7,
                                winner = "TEAM2",
                                tiebreakTeam1Points = 5,
                                tiebreakTeam2Points = 7,
                            ),
                    ),
            ).toBroadcast(publicCode = "MTCH01")

        payload.sets.shouldHaveSize(size = 1)
        payload.sets.single().tiebreakTeam1Points shouldBe 5
        payload.sets.single().tiebreakTeam2Points shouldBe 7
    }

    @Test
    fun `the emitted document names its fields exactly as the client reads them`() {
        // Asserting the DOCUMENT, not just the payload object. These strings are the wire contract with
        // the spectator client: nothing on the Kotlin side breaks if one is renamed, and the scoreboard
        // silently shows blanks. The previous test checked the object and left that untested.
        val document =
            view(
                sets =
                    listOf(
                        element =
                            LiveSetResponse(
                                gamesTeam1 = 6,
                                gamesTeam2 = 7,
                                winner = "TEAM2",
                                tiebreakTeam1Points = 5,
                                tiebreakTeam2Points = 7,
                            ),
                    ),
            ).toBroadcast(publicCode = "MTCH01").asDocument()

        @Suppress("UNCHECKED_CAST")
        val sets = document["sets"] as List<Map<String, Any?>>
        sets.shouldHaveSize(size = 1)
        sets.single() shouldBe
            mapOf(
                "gamesTeam1" to 6,
                "gamesTeam2" to 7,
                "tiebreakTeam1Points" to 5,
                "tiebreakTeam2Points" to 7,
            )
    }

    @Test
    fun `a set with no tiebreak emits null rather than omitting the keys`() {
        // Same reason the top-level nulls are kept: the document is overwritten wholesale, so a key that
        // disappears would leave a previous set's tiebreak behind on the client.
        val document =
            view(
                sets =
                    listOf(
                        element =
                            LiveSetResponse(
                                gamesTeam1 = 6,
                                gamesTeam2 = 4,
                                winner = "TEAM1",
                                tiebreakTeam1Points = null,
                                tiebreakTeam2Points = null,
                            ),
                    ),
            ).toBroadcast(publicCode = "MTCH01").asDocument()

        @Suppress("UNCHECKED_CAST")
        val sets = document["sets"] as List<Map<String, Any?>>
        sets.single().keys shouldContain "tiebreakTeam1Points"
        sets.single()["tiebreakTeam1Points"] shouldBe null
    }

    @Test
    fun `the outcome is flattened, so a spectator sees the winner once the match ends`() {
        val payload =
            view(outcome = LiveOutcomeResponse(kind = "RETIRED", winner = "TEAM1", concededBy = "TEAM2"))
                .toBroadcast(publicCode = "MTCH01")

        payload.outcomeKind shouldBe "RETIRED"
        payload.outcomeWinner shouldBe "TEAM1"
    }

    @Test
    fun `nulls are kept in the document, so a field that became null is cleared on overwrite`() {
        // The document is overwritten wholesale rather than merged. Dropping null keys would leave a
        // stale outcome or server id behind on a match that was un-finalized or had its server cleared.
        val document = view(outcome = null).toBroadcast(publicCode = "MTCH01").asDocument()
        document.keys shouldContain "outcomeKind"
        document["outcomeKind"] shouldBe null
    }

    @Test
    fun `the no-op broadcaster is inert, because most environments have no Firestore`() {
        // Not a test double: it is the DEFAULT. Local development and CI have no Firestore project and
        // live scoring must work fully without one.
        NoOpLiveScoreBroadcaster.publish(payload = view().toBroadcast(publicCode = "MTCH01"))
    }
}
