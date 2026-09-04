// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.match

import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.common.dto.event.EventResponse
import org.skopeo.common.dto.match.MatchScoreCorrectionRequest
import org.skopeo.common.dto.match.SetScoreRequest
import org.skopeo.common.redaction.asRedactable
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.entity.event.toDomain
import org.skopeo.domain.mapper.entity.match.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.AwardStatus
import org.skopeo.domain.model.CreateCircuitCommand
import org.skopeo.domain.model.CreateFixtureCommand
import org.skopeo.domain.model.Event
import org.skopeo.domain.model.EventType
import org.skopeo.domain.model.Match
import org.skopeo.domain.model.MatchSetResult
import org.skopeo.domain.model.MatchType
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.PlacementBracket
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.TeamType
import org.skopeo.domain.model.User
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.domain.service.event.CreateEventInput
import org.skopeo.domain.service.event.EventService
import org.skopeo.domain.service.rating.RatingAssembler
import org.skopeo.domain.service.rating.RatingCalculationService
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.repository.CircuitRepository
import org.skopeo.repository.EventRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RankingPointRepository
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.TestAppSettings
import org.skopeo.testsupport.seedFixtureClub
import org.skopeo.testsupport.settleAllRatings
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * The ranking-points half of a score correction (#776), for the coupled case: a TOURNAMENT placement match.
 *
 * Placement points are paid BY PLACE, and a player earns only their BEST placement across the event's
 * placement matches (`EventFinalizeAwarder.awardPlacement`'s guard). That guard spans matches, so correcting
 * a placement match recomputes the event's whole placement set — a naive per-match re-issue could leave a
 * player holding two placement awards. These tests pin exactly that.
 */
class MatchScoreCorrectionPointsTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val events = EventRepository()
    private val matchRepo = MatchRepository()
    private val ratings = RatingAssembler()
    private val awards = RankingPointRepository()
    private val eventService = EventService(events = events, users = users)
    private val calc = RatingCalculationService()
    private val service = MatchScoreCorrectionService()

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun provision(
        uid: String,
        roles: Set<Capability> = setOf(element = Capability.PLAYER),
    ): User =
        users.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid.asRedactable(),
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                    capabilities = roles,
                ),
        ).toDomain()

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid.asRedactable())

    private fun EventResponse.domain(): Event = events.findById(id = UUID.fromString(id))!!.toDomain()

    /** Seed a circuit (#525) attributed to [hostUid]; a tournament must reference one. */
    private fun seedCircuit(hostUid: String): UUID {
        val creator = requireNotNull(value = users.findByFirebaseUid(firebaseUid = hostUid)) { "unknown host $hostUid" }.toDomain()
        return CircuitRepository().create(command = CreateCircuitCommand(name = "NORTH", createdBy = creator.id)).id
    }

    /** A COMPLETED singles Super Finals where team1 (p1) beats p2 — so finalize pays 1st to p1, 2nd to p2. */
    private fun seedChampionshipFinal(
        eventId: UUID,
        host: User,
        p1: User,
        p2: User,
    ): Match {
        val match =
            matchRepo.createFixture(
                command =
                    CreateFixtureCommand(
                        matchFormat = TeamType.SINGLES,
                        matchType = MatchType.TOURNAMENT,
                        matchDate = LocalDate.now(),
                        team1UserIds = listOf(element = p1.id),
                        team2UserIds = listOf(element = p2.id),
                        team1Name = "t1",
                        team2Name = "t2",
                        createdBy = host.id,
                        eventId = eventId,
                        isPlacementMatch = true,
                        placementBracket = PlacementBracket.CHAMPIONSHIP_FINALS,
                    ),
            ).toDomain()
        matchRepo.addResult(
            matchId = match.id,
            sets = listOf(element = MatchSetResult(setNumber = 1, team1Games = 6, team2Games = 4, winnerTeamId = match.team1.teamId)),
            winnerTeamId = match.team1.teamId,
            recordedBy = host.id,
            completedAt = LocalDateTime.now(),
        )
        return matchRepo.findById(matchId = match.id).shouldBeRight().toDomain()
    }

    private data class RatedTournament(
        val eventId: UUID,
        val p1: User,
        val p2: User,
        val match: Match,
    )

    /** A finalized + rated TOURNAMENT whose Super Finals p1 won, with placement points already paid. */
    private fun ratedTournament(): RatedTournament {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val admin = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        ratings.setRating(userId = p1.id, rating = BigDecimal("4.0"), level = "4.0")
        ratings.setRating(userId = p2.id, rating = BigDecimal("4.0"), level = "4.0")
        // These fixtures are about re-pricing awards, not about calibration (#881) — and a designation
        // puts a player IN calibration, where they earn nothing.
        settleAllRatings()
        // Awarding is gated by the global flag (#641/#752) and defaults off; these fixtures need a real payout.
        TestAppSettings.setAwardRankingPoints(enabled = true, updatedBy = admin.id)
        val event =
            eventService
                .create(
                    token = token(uid = "host"),
                    input =
                        CreateEventInput(
                            clubId = seedFixtureClub(ownerUids = arrayOf("host")).id,
                            name = "Spring Open",
                            startDate = LocalDate.now(),
                            endDate = LocalDate.now().plusDays(7),
                            participantIds = listOf(p1.id, p2.id),
                            type = EventType.TOURNAMENT.name,
                            circuitId = seedCircuit(hostUid = "host"),
                        ),
                ).shouldBeRight()
                .domain()
        val match = seedChampionshipFinal(eventId = event.id, host = host, p1 = p1, p2 = p2)
        eventService.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()
        calc.calculate(token = token(uid = "admin"), dryRun = false).shouldBeRight()
        return RatedTournament(eventId = event.id, p1 = p1, p2 = p2, match = match)
    }

    private fun activePoints(userId: UUID): List<BigDecimal> =
        awards
            .listByUser(userId = userId)
            .filter { it.status == AwardStatus.ACTIVE.name }
            .map { it.points }

    @Test
    fun `flipping a Super Finals winner swaps the placement awards and leaves one award each (#776)`() {
        val t = ratedTournament()
        // Before: p1 took 1st, so p1 out-scores p2.
        val p1Before = activePoints(userId = t.p1.id).single()
        val p2Before = activePoints(userId = t.p2.id).single()
        (p1Before > p2Before).shouldBeTrue()

        val outcome =
            service
                .correctScore(
                    token = token(uid = "admin"),
                    matchId = t.match.id,
                    request =
                        MatchScoreCorrectionRequest(
                            sets = listOf(element = SetScoreRequest(team1Games = 4, team2Games = 6)),
                            dryRun = false,
                        ),
                ).shouldBeRight()

        outcome.winnerChanged.shouldBeTrue()
        (outcome.awardsRevoked > 0).shouldBeTrue()
        (outcome.awardsReissued > 0).shouldBeTrue()

        // After: the places swap, and the best-placement guard still holds — exactly one ACTIVE award each.
        val p1After = activePoints(userId = t.p1.id)
        val p2After = activePoints(userId = t.p2.id)
        p1After shouldHaveSize 1
        p2After shouldHaveSize 1
        p1After.single() shouldBe p2Before
        p2After.single() shouldBe p1Before
    }

    /** A COMPLETED singles round fixture (no placement bracket) where team1 (p1) beats p2 6-4. */
    private fun seedRoundFixture(
        eventId: UUID,
        host: User,
        p1: User,
        p2: User,
    ): Match {
        val match =
            matchRepo.createFixture(
                command =
                    CreateFixtureCommand(
                        matchFormat = TeamType.SINGLES,
                        matchType = MatchType.TOURNAMENT,
                        matchDate = LocalDate.now(),
                        team1UserIds = listOf(element = p1.id),
                        team2UserIds = listOf(element = p2.id),
                        team1Name = "r1",
                        team2Name = "r2",
                        createdBy = host.id,
                        eventId = eventId,
                    ),
            ).toDomain()
        matchRepo.addResult(
            matchId = match.id,
            sets = listOf(element = MatchSetResult(setNumber = 1, team1Games = 6, team2Games = 4, winnerTeamId = match.team1.teamId)),
            winnerTeamId = match.team1.teamId,
            recordedBy = host.id,
            completedAt = LocalDateTime.now(),
        )
        return matchRepo.findById(matchId = match.id).shouldBeRight().toDomain()
    }

    @Test
    fun `correcting a non-placement tournament fixture re-prices only it and never duplicates placements (#836)`() {
        // A tournament now pays two halves (#836), and the correction path revokes by MATCH for a
        // non-placement fixture but by EVENT for a placement one (MatchScoreCorrectionService). The
        // re-award has to mirror that: recomputing placement here would insert a SECOND placement row for
        // every player, because those rows were never revoked and award() does not dedupe.
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val admin = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        ratings.setRating(userId = p1.id, rating = BigDecimal("4.0"), level = "4.0")
        ratings.setRating(userId = p2.id, rating = BigDecimal("4.0"), level = "4.0")
        // These fixtures are about re-pricing awards, not about calibration (#881) — and a designation
        // puts a player IN calibration, where they earn nothing.
        settleAllRatings()
        TestAppSettings.setAwardRankingPoints(enabled = true, updatedBy = admin.id)
        val event =
            eventService
                .create(
                    token = token(uid = "host"),
                    input =
                        CreateEventInput(
                            clubId = seedFixtureClub(ownerUids = arrayOf("host")).id,
                            name = "Spring Open",
                            startDate = LocalDate.now(),
                            endDate = LocalDate.now().plusDays(7),
                            participantIds = listOf(p1.id, p2.id),
                            type = EventType.TOURNAMENT.name,
                            circuitId = seedCircuit(hostUid = "host"),
                        ),
                ).shouldBeRight()
                .domain()
        seedChampionshipFinal(eventId = event.id, host = host, p1 = p1, p2 = p2)
        val round = seedRoundFixture(eventId = event.id, host = host, p1 = p1, p2 = p2)
        eventService.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()
        calc.calculate(token = token(uid = "admin"), dryRun = false).shouldBeRight()

        // Two rows each: a placement from the final, and a per-set row from the round.
        val p1Before = activePoints(userId = p1.id)
        val p2Before = activePoints(userId = p2.id)
        p1Before shouldHaveSize 2
        p2Before shouldHaveSize 2
        val p1Placement = p1Before.max()
        val p2Placement = p2Before.max()

        // Flip the round fixture's winner. The final is untouched.
        service
            .correctScore(
                token = token(uid = "admin"),
                matchId = round.id,
                request =
                    MatchScoreCorrectionRequest(
                        sets = listOf(element = SetScoreRequest(team1Games = 4, team2Games = 6)),
                        dryRun = false,
                    ),
            ).shouldBeRight()

        // Still exactly two rows each — no duplicated placement — and the placement values are unchanged.
        val p1After = activePoints(userId = p1.id)
        val p2After = activePoints(userId = p2.id)
        p1After shouldHaveSize 2
        p2After shouldHaveSize 2
        p1After.max() shouldBe p1Placement
        p2After.max() shouldBe p2Placement
        // The per-set half flipped: p2 now holds the round win, p1 the zero row.
        (p2After.min() > p1After.min()).shouldBeTrue()
    }

    /**
     * A finalized + rated OPEN_PLAY event with one ordinary (non-placement) fixture. Open play pays per
     * match from band difference, so a correction here revokes and re-issues **only that match's** awards —
     * the [RankingPointRepository.listActiveByMatch] path, distinct from the event-wide placement path.
     */
    private fun ratedOpenPlay(): RatedTournament {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val admin = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        // A band gap gives open-play points something to compute from.
        ratings.setRating(userId = p1.id, rating = BigDecimal("4.5"), level = "4.5")
        ratings.setRating(userId = p2.id, rating = BigDecimal("3.5"), level = "3.5")
        // Settled: this fixture pays open-play points, and a calibrating player earns nothing (#881).
        settleAllRatings()
        TestAppSettings.setAwardRankingPoints(enabled = true, updatedBy = admin.id)
        val event =
            eventService
                .create(
                    token = token(uid = "host"),
                    input =
                        CreateEventInput(
                            clubId = seedFixtureClub(ownerUids = arrayOf("host")).id,
                            name = "Weekend Social",
                            startDate = LocalDate.now(),
                            endDate = LocalDate.now().plusDays(1),
                            participantIds = listOf(p1.id, p2.id),
                            type = EventType.OPEN_PLAY.name,
                        ),
                ).shouldBeRight()
                .domain()
        val match =
            matchRepo
                .createFixture(
                    command =
                        CreateFixtureCommand(
                            matchFormat = TeamType.SINGLES,
                            matchType = MatchType.OPEN_PLAY,
                            matchDate = LocalDate.now(),
                            team1UserIds = listOf(element = p1.id),
                            team2UserIds = listOf(element = p2.id),
                            team1Name = "t1",
                            team2Name = "t2",
                            createdBy = host.id,
                            eventId = event.id,
                        ),
                ).toDomain()
        matchRepo.addResult(
            matchId = match.id,
            sets = listOf(element = MatchSetResult(setNumber = 1, team1Games = 6, team2Games = 4, winnerTeamId = match.team1.teamId)),
            winnerTeamId = match.team1.teamId,
            recordedBy = host.id,
            completedAt = LocalDateTime.now(),
        )
        eventService.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()
        calc.calculate(token = token(uid = "admin"), dryRun = false).shouldBeRight()
        return RatedTournament(
            eventId = event.id,
            p1 = p1,
            p2 = p2,
            match = matchRepo.findById(matchId = match.id).shouldBeRight().toDomain(),
        )
    }

    @Test
    fun `correcting an ordinary open-play match re-issues only that match's awards (#776)`() {
        val t = ratedOpenPlay()
        val revokedBefore =
            awards.listByUser(userId = t.p1.id).count { it.status == AwardStatus.REVOKED.name }

        val outcome =
            service
                .correctScore(
                    token = token(uid = "admin"),
                    matchId = t.match.id,
                    request =
                        MatchScoreCorrectionRequest(
                            sets = listOf(element = SetScoreRequest(team1Games = 6, team2Games = 0)),
                            dryRun = false,
                        ),
                ).shouldBeRight()

        // Open play pays both sides per match, so the match's own awards are revoked and re-issued.
        (outcome.awardsRevoked > 0).shouldBeTrue()
        (outcome.awardsReissued > 0).shouldBeTrue()
        outcome.pointsSuppressedByGlobalFlag shouldBe false
        // Revocation is append-only: the old award survives as a REVOKED marker rather than vanishing.
        val revokedAfter = awards.listByUser(userId = t.p1.id).count { it.status == AwardStatus.REVOKED.name }
        (revokedAfter > revokedBefore).shouldBeTrue()
        // And exactly one ACTIVE award remains for the corrected fixture.
        activePoints(userId = t.p1.id) shouldHaveSize 1
    }

    @Test
    fun `a dry-run correction of a placement match touches no awards (#776)`() {
        val t = ratedTournament()
        val p1Before = activePoints(userId = t.p1.id)

        val preview =
            service
                .correctScore(
                    token = token(uid = "admin"),
                    matchId = t.match.id,
                    request = MatchScoreCorrectionRequest(sets = listOf(element = SetScoreRequest(team1Games = 4, team2Games = 6))),
                ).shouldBeRight()

        preview.awardsRevoked shouldBe 0
        preview.awardsReissued shouldBe 0
        activePoints(userId = t.p1.id) shouldBe p1Before
    }
}
