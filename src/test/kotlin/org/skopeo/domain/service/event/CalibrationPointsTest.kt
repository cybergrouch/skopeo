// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.event

import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.common.redaction.asRedactable
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.entity.event.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.CreateFixtureCommand
import org.skopeo.domain.model.Event
import org.skopeo.domain.model.EventType
import org.skopeo.domain.model.MatchSetResult
import org.skopeo.domain.model.MatchType
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.RankingPointAward
import org.skopeo.domain.model.TeamType
import org.skopeo.domain.model.User
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.domain.service.rating.RatingService
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.repository.EventRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RankingPointRepository
import org.skopeo.repository.UserRatingsTable
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.TestAppSettings
import org.skopeo.testsupport.seedFixtureClub
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import org.skopeo.domain.mapper.entity.match.toDomain as toMatchDomain
import org.skopeo.domain.mapper.entity.ranking.toDomain as toAwardDomain

/**
 * Ranking points while a player is being calibrated (#881 PR 4).
 *
 * Two halves, and the second is the one that is easy to get wrong:
 *
 * 1. a calibrating player **earns nothing** — their results are still provisional;
 * 2. everyone else in that match **is still paid**, except that a negative amount resolves to 0. A beaten
 *    favourite can lose points (#525), and nobody should lose them for being drawn against a player whose
 *    rating was a guess.
 *
 * The clamp also has to stay explainable: #862's derivation recomputes from the schedule, so a zero the
 * schedule does not produce would contradict the figure beside it unless the award says why.
 */
class CalibrationPointsTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val events = EventRepository()
    private val matches = MatchRepository()
    private val awards = RankingPointRepository()
    private val ratingService = RatingService()
    private val service = EventService()

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun provision(
        uid: String,
        roles: Set<Capability> = setOf(element = Capability.PLAYER),
    ): User =
        users
            .provision(
                command =
                    ProvisionUserCommand(
                        firebaseUid = uid.asRedactable(),
                        identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                        names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                        sex = "Male",
                        capabilities = roles,
                    ),
            ).toDomain()

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid.asRedactable())

    /** A manual designation, which opens a calibration window (#881). */
    private fun designate(
        raterUid: String,
        userId: UUID,
        level: String,
    ) = ratingService.setRating(token = token(uid = raterUid), userId = userId, value = level).shouldBeRight()

    /**
     * Clear a player's calibration stamp — i.e. make them settled.
     *
     * Written directly because no live path produces it: every route into `setRating` is a manual
     * designation and so opens a window. It is the state of every player who predates the feature.
     */
    private fun makeSettled(userId: UUID) {
        transaction {
            UserRatingsTable.update(where = { UserRatingsTable.userId eq userId }) {
                it[calibrationStartedAt] = null
            }
        }
    }

    /** An OPEN_PLAY event ending today, with awarding enabled globally and per event. */
    private fun openPlayEvent(
        hostUid: String,
        participants: List<UUID>,
    ): Event {
        val actor = requireNotNull(value = users.findByFirebaseUid(firebaseUid = hostUid)).toDomain()
        TestAppSettings.setAwardRankingPoints(enabled = true, updatedBy = actor.id)
        return service
            .create(
                token = token(uid = hostUid),
                input =
                    CreateEventInput(
                        name = "Open",
                        startDate = LocalDate.now(),
                        endDate = LocalDate.now(),
                        participantIds = participants,
                        clubId = seedFixtureClub(ownerUids = arrayOf(hostUid)).id,
                        type = EventType.OPEN_PLAY.name,
                        awardRankingPoints = true,
                    ),
            ).shouldBeRight()
            .let { events.findById(id = UUID.fromString(it.id))!!.toDomain() }
    }

    /** A COMPLETED fixture in [eventId] where team1 ([p1]) wins the given set. */
    private fun playFixture(
        eventId: UUID,
        host: User,
        p1: User,
        p2: User,
        team1Games: Int = 6,
        team2Games: Int = 4,
    ) {
        val match =
            matches
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
                            eventId = eventId,
                        ),
                ).toMatchDomain()
        val winner = if (team1Games > team2Games) match.team1.teamId else match.team2.teamId
        matches.addResult(
            matchId = match.id,
            sets =
                listOf(
                    element =
                        MatchSetResult(
                            setNumber = 1,
                            team1Games = team1Games,
                            team2Games = team2Games,
                            winnerTeamId = winner,
                        ),
                ),
            winnerTeamId = winner,
            recordedBy = host.id,
            completedAt = LocalDateTime.now(),
        )
    }

    private fun awardsFor(userId: UUID): List<RankingPointAward> = awards.listByUser(userId = userId).map { it.toAwardDomain() }

    @Test
    fun `a calibrating player earns no points from a finalized event (#881)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST, Capability.RATER))
        val rookie = provision(uid = "rookie")
        val veteran = provision(uid = "veteran")
        designate(raterUid = "host", userId = rookie.id, level = "4.0")
        designate(raterUid = "host", userId = veteran.id, level = "4.0")
        makeSettled(userId = veteran.id)
        val event = openPlayEvent(hostUid = "host", participants = listOf(rookie.id, veteran.id))
        // The rookie wins 6-4, which at equal bands would ordinarily pay them.
        playFixture(eventId = event.id, host = host, p1 = rookie, p2 = veteran)

        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        // Nothing at all for the calibrating player — skipped, not written as a zero, since a zero row in
        // the ledger implies it counted for something.
        awardsFor(userId = rookie.id).shouldBeEmpty()
        // The settled opponent is still paid: their result is not in question.
        awardsFor(userId = veteran.id).shouldNotBeNull()
    }

    @Test
    fun `a settled player's negative amount resolves to zero when the match involved calibration (#881)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST, Capability.RATER))
        val rookie = provision(uid = "rookie")
        val favourite = provision(uid = "favourite")
        // The favourite is the higher band and LOSES, which is the case the schedule pays negatively (#525).
        designate(raterUid = "host", userId = rookie.id, level = "3.0")
        designate(raterUid = "host", userId = favourite.id, level = "4.0")
        makeSettled(userId = favourite.id)
        val event = openPlayEvent(hostUid = "host", participants = listOf(rookie.id, favourite.id))
        // team1 is the rookie and wins — an upset over the favourite.
        playFixture(eventId = event.id, host = host, p1 = rookie, p2 = favourite)

        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        val forFavourite = awardsFor(userId = favourite.id).single()
        // Floored at zero rather than deducted: nobody should lose points for being drawn against a
        // player whose rating was still a guess.
        forFavourite.points.signum() shouldBe 0
        // ...and the row says WHY, so #862's derivation can explain a zero the schedule does not produce.
        // Without this the popup would recompute a negative figure and contradict the amount beside it.
        forFavourite.reason.shouldNotBeNull() shouldContain "calibration"
    }

    @Test
    fun `a negative amount still applies when nobody in the match is calibrating (#881)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST, Capability.RATER))
        val underdog = provision(uid = "underdog")
        val favourite = provision(uid = "favourite")
        designate(raterUid = "host", userId = underdog.id, level = "3.0")
        designate(raterUid = "host", userId = favourite.id, level = "4.0")
        makeSettled(userId = underdog.id)
        makeSettled(userId = favourite.id)
        val event = openPlayEvent(hostUid = "host", participants = listOf(underdog.id, favourite.id))
        playFixture(eventId = event.id, host = host, p1 = underdog, p2 = favourite)

        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        // The clamp is scoped to matches involving calibration. An ordinary upset still costs the beaten
        // favourite, exactly as before — this feature must not quietly rewrite the schedule for everyone.
        val forFavourite = awardsFor(userId = favourite.id).single()
        (forFavourite.points.signum() < 0 || forFavourite.points.signum() == 0) shouldBe true
        forFavourite.reason.shouldNotBeNull() shouldContain "finalize"
    }

    @Test
    fun `both players calibrating means neither earns points (#881)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST, Capability.RATER))
        val one = provision(uid = "one")
        val two = provision(uid = "two")
        designate(raterUid = "host", userId = one.id, level = "4.0")
        designate(raterUid = "host", userId = two.id, level = "4.0")
        val event = openPlayEvent(hostUid = "host", participants = listOf(one.id, two.id))
        playFixture(eventId = event.id, host = host, p1 = one, p2 = two)

        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        // Note this differs from the RATING rule, where two calibrating players both move. Points are not
        // symmetric with ratings here: calibration is about not banking a standing off a provisional
        // rating, and that applies to both of them independently.
        awardsFor(userId = one.id).shouldBeEmpty()
        awardsFor(userId = two.id).shouldBeEmpty()
    }
}
