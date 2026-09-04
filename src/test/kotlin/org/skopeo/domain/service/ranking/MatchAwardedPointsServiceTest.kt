// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.ranking

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.common.error.ServiceError
import org.skopeo.common.redaction.asRedactable
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.AwardStatus
import org.skopeo.domain.model.CreateEventCommand
import org.skopeo.domain.model.CreateFixtureCommand
import org.skopeo.domain.model.Match
import org.skopeo.domain.model.MatchSetResult
import org.skopeo.domain.model.MatchType
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.PointClass
import org.skopeo.domain.model.PointSourceType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.RankingPointAwardWrite
import org.skopeo.domain.model.TeamType
import org.skopeo.domain.model.User
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.domain.service.settings.SettingsService
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.repository.EventRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RankingPointRepository
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.seedFixtureClub
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import org.skopeo.domain.mapper.entity.event.toDomain as toEventDomain
import org.skopeo.domain.mapper.entity.match.toDomain as toMatchDomain

/**
 * The public match page's points card (#858).
 *
 * Two claims are load-bearing and everything here pins one of them:
 *
 * 1. **Amounts are public, derivation is not.** The derivation carries the band relation, which is
 *    rating-adjacent (#583/#654), so it must be *absent from the payload* for a caller not entitled to it
 *    rather than merely unrendered — and the scope is per-club (#789), not per-capability.
 * 2. **This read is about history.** A revoked award drops out (it paid nothing), an expired one does not
 *    (it still records what this match paid). Every other read of this table applies validity; this one
 *    deliberately does not.
 */
class MatchAwardedPointsServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val matches = MatchRepository()
    private val events = EventRepository()
    private val awards = RankingPointRepository()
    private val service = MatchAwardedPointsService()

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

    /** A completed singles fixture, optionally filed under an event owned by [clubOwnerUid]. */
    private fun completedMatch(
        one: User,
        two: User,
        clubOwnerUid: String? = null,
        creator: User = one,
    ): Match {
        val eventId =
            clubOwnerUid?.let { uid ->
                events
                    .create(
                        command =
                            CreateEventCommand(
                                name = "Cup",
                                startDate = LocalDate.of(2026, 1, 1),
                                endDate = LocalDate.of(2026, 1, 7),
                                participantIds = listOf(one.id, two.id),
                                createdBy = creator.id,
                                clubId = seedFixtureClub(ownerUids = arrayOf(uid)).id,
                            ),
                    ).toEventDomain()
                    .id
            }
        val fixture =
            matches
                .createFixture(
                    command =
                        CreateFixtureCommand(
                            matchFormat = TeamType.SINGLES,
                            matchType = MatchType.OPEN_PLAY,
                            matchDate = LocalDate.of(2026, 1, 5),
                            team1UserIds = listOf(element = one.id),
                            team2UserIds = listOf(element = two.id),
                            team1Name = "T1",
                            team2Name = "T2",
                            createdBy = one.id,
                            eventId = eventId,
                        ),
                ).toMatchDomain()
        matches.addResult(
            matchId = fixture.id,
            sets =
                listOf(
                    element =
                        MatchSetResult(setNumber = 1, team1Games = 6, team2Games = 4, winnerTeamId = fixture.team1.teamId),
                ),
            winnerTeamId = fixture.team1.teamId,
            recordedBy = one.id,
            completedAt = LocalDateTime.now(),
        )
        return matches.findById(matchId = fixture.id).getOrNull()!!.toMatchDomain()
    }

    /** Write one award for [userId] attributed to [match]. */
    private fun matchAward(
        userId: UUID,
        match: Match,
        points: String,
        pointClass: PointClass = PointClass.OPEN_PLAY,
        status: AwardStatus = AwardStatus.ACTIVE,
        validUntil: LocalDateTime = LocalDateTime.now().plusMonths(6),
        teamBand: String? = "4.0",
        opponentBand: String? = "4.0",
    ) = awards.award(
        write =
            RankingPointAwardWrite(
                userId = userId,
                points = BigDecimal(points),
                pointClass = pointClass,
                sourceType = PointSourceType.INTERNAL,
                sourceId = match.id.toString(),
                band = "4.0",
                sex = "Male",
                reason = null,
                validFrom = LocalDateTime.now().minusDays(1),
                validUntil = validUntil,
                status = status,
                revokesAwardId = null,
                grantedBy = null,
                awardedAt = LocalDateTime.now(),
                eventId = match.eventId,
                matchId = match.id,
                pointsScheduleVersion = 1,
                teamBand = teamBand,
                opponentBand = opponentBand,
            ),
    )

    @Test
    fun `one row per award, highest first, with the point class named (#858)`() {
        val ana = provision(uid = "ana")
        val ben = provision(uid = "ben")
        val match = completedMatch(one = ana, two = ben)
        matchAward(userId = ana.id, match = match, points = "8")
        matchAward(userId = ben.id, match = match, points = "1")

        val summary = service.forMatch(code = match.publicCode).shouldBeRight()

        // A row per award, not per player: that is what lets a row name its own point class.
        summary.rows.map { it.displayName to it.points } shouldBe listOf("ana" to "8.0000", "ben" to "1.0000")
        summary.rows.map { it.pointClass } shouldBe listOf("OPEN_PLAY", "OPEN_PLAY")
        summary.rows.forEach { it.awardId.shouldNotBeNull() }
        summary.totalPoints shouldBe "9.0000"
    }

    @Test
    fun `a placement award is labelled, so 1000 beside 8 does not read as a bug (#858)`() {
        val ana = provision(uid = "ana")
        val ben = provision(uid = "ben")
        val match = completedMatch(one = ana, two = ben)
        matchAward(userId = ana.id, match = match, points = "1000", pointClass = PointClass.ANNUAL_TOURNAMENT)
        matchAward(userId = ben.id, match = match, points = "8")

        val summary = service.forMatch(code = match.publicCode).shouldBeRight()

        // One tournament fixture can pay either a placement amount or the per-set schedule (#836/#837);
        // the two differ by an order of magnitude, so the class has to be visible per row.
        summary.rows.map { it.pointClass } shouldBe listOf("ANNUAL_TOURNAMENT", "OPEN_PLAY")
    }

    @Test
    fun `a revoked award is excluded, an expired one is not (#858)`() {
        val ana = provision(uid = "ana")
        val ben = provision(uid = "ben")
        val match = completedMatch(one = ana, two = ben)
        matchAward(userId = ana.id, match = match, points = "8", status = AwardStatus.REVOKED)
        // Expired months ago — and still shown, because this card records what the match PAID.
        matchAward(userId = ben.id, match = match, points = "6", validUntil = LocalDateTime.now().minusMonths(2))

        val summary = service.forMatch(code = match.publicCode).shouldBeRight()

        // The one read of this table that ignores validity. A revoked award paid nothing, so it goes; an
        // expired one paid something, so it stays.
        summary.rows.single().points shouldBe "6.0000"
    }

    @Test
    fun `a match with no awards returns an empty list rather than an error (#858)`() {
        val ana = provision(uid = "ana")
        val ben = provision(uid = "ben")
        val match = completedMatch(one = ana, two = ben)

        val summary = service.forMatch(code = match.publicCode).shouldBeRight()

        // The client renders no card at all — the event may be unfinalized, or have awarding off (#831).
        summary.rows.shouldBeEmpty()
        summary.totalPoints shouldBe "0"
    }

    @Test
    fun `an unknown match code is a NotFound (#858)`() {
        service.forMatch(code = "NOPE12").shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `an anonymous or plain-player reader sees amounts but no derivation (#858)`() {
        val ana = provision(uid = "ana")
        val ben = provision(uid = "ben")
        val match = completedMatch(one = ana, two = ben)
        matchAward(userId = ana.id, match = match, points = "8")

        val anonymous = service.forMatch(code = match.publicCode).shouldBeRight()
        val player = service.forMatch(code = match.publicCode, token = token(uid = "ben")).shouldBeRight()

        // Omitted from the payload, not hidden client-side: the band relation is rating-adjacent
        // (#583/#654), so a curious reader must not be able to read it out of the network response.
        anonymous.rows.single().points shouldBe "8.0000"
        anonymous.rows.single().derivation.shouldBeNull()
        player.rows.single().derivation.shouldBeNull()
    }

    @Test
    fun `an administrator and a rater see the derivation on any match (#858)`() {
        val ana = provision(uid = "ana")
        val ben = provision(uid = "ben")
        provision(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        provision(uid = "rater", roles = setOf(Capability.PLAYER, Capability.RATER))
        val match = completedMatch(one = ana, two = ben)
        matchAward(userId = ana.id, match = match, points = "8")

        listOf("root", "rater").forEach { uid ->
            withClue(clue = "$uid should see the derivation") {
                val row = service.forMatch(code = match.publicCode, token = token(uid = uid)).shouldBeRight().rows.single()
                val derivation = row.derivation.shouldNotBeNull()
                // And it is the real thing, not a stub: 6-4 at equal bands pays 8 at margin 2 (v1).
                derivation.recorded shouldBe true
                derivation.sets.single().margin shouldBe 2
                derivation.sets.single().pointsForThisPlayer shouldBe 8
            }
        }
    }

    @Test
    fun `a club owner sees the derivation on their own club's match only (#789)`() {
        val ana = provision(uid = "ana")
        val ben = provision(uid = "ben")
        // Both hold CLUB_OWNER; only one of them owns the club this match's event is filed under.
        provision(uid = "owner", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        provision(uid = "stranger", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        val match = completedMatch(one = ana, two = ben, clubOwnerUid = "owner")
        matchAward(userId = ana.id, match = match, points = "8")

        val theirs = service.forMatch(code = match.publicCode, token = token(uid = "owner")).shouldBeRight()
        val notTheirs = service.forMatch(code = match.publicCode, token = token(uid = "stranger")).shouldBeRight()

        // Reach over an event is per-club since #789 — the capability alone is not a global licence, and
        // a rating-adjacent field is exactly where that has to hold.
        theirs.rows.single().derivation.shouldNotBeNull()
        notTheirs.rows.single().derivation.shouldBeNull()
        withClue(clue = "both still see the amount") {
            notTheirs.rows.single().points shouldBe "8.0000"
        }
    }

    @Test
    fun `the event's creator sees the derivation even without owning the club (#789)`() {
        val ana = provision(uid = "ana")
        val ben = provision(uid = "ben")
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        // "owner" owns the club; "host" merely filed the event under it — grandfathered permanently.
        provision(uid = "owner", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        val match = completedMatch(one = ana, two = ben, clubOwnerUid = "owner", creator = host)
        matchAward(userId = ana.id, match = match, points = "8")

        service.forMatch(code = match.publicCode, token = token(uid = "host")).shouldBeRight()
            .rows.single().derivation.shouldNotBeNull()
    }

    @Test
    fun `a plain HOST gets no derivation from the capability alone (#858)`() {
        val ana = provision(uid = "ana")
        val ben = provision(uid = "ben")
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "owner", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        // The event belongs to owner's club and was created by owner, so host has no claim on it.
        val match = completedMatch(one = ana, two = ben, clubOwnerUid = "owner", creator = ana)
        matchAward(userId = ana.id, match = match, points = "8")

        service.forMatch(code = match.publicCode, token = token(uid = "host")).shouldBeRight()
            .rows.single().derivation.shouldBeNull()
    }

    @Test
    fun `an eventless match is ADMINISTRATOR and RATER only, with no club to scope against (#858)`() {
        val ana = provision(uid = "ana")
        val ben = provision(uid = "ben")
        provision(uid = "owner", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        provision(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        // No event, so no club — mayOrganize has nothing to answer. Handled explicitly rather than
        // discovered as a null-club crash.
        val match = completedMatch(one = ana, two = ben)
        matchAward(userId = ana.id, match = match, points = "8")

        service.forMatch(code = match.publicCode, token = token(uid = "owner")).shouldBeRight()
            .rows.single().derivation.shouldBeNull()
        service.forMatch(code = match.publicCode, token = token(uid = "root")).shouldBeRight()
            .rows.single().derivation.shouldNotBeNull()
    }

    @Test
    fun `an award predating the recorded inputs says so rather than being omitted (#862)`() {
        val ana = provision(uid = "ana")
        val ben = provision(uid = "ben")
        provision(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val match = completedMatch(one = ana, two = ben)
        matchAward(userId = ana.id, match = match, points = "8", teamBand = null, opponentBand = null)

        val row = service.forMatch(code = match.publicCode, token = token(uid = "root")).shouldBeRight().rows.single()

        // The derivation is present but honest: the amount is real, only its explanation is missing.
        val derivation = row.derivation.shouldNotBeNull()
        derivation.recorded shouldBe false
        derivation.unavailableReason.shouldNotBeNull()
        row.points shouldBe "8.0000"
    }

    @Test
    fun `the card is suppressed entirely for an unprivileged viewer while the flag is on (#865)`() {
        val ana = provision(uid = "ana")
        val ben = provision(uid = "ben")
        provision(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val match = completedMatch(one = ana, two = ben)
        matchAward(userId = ana.id, match = match, points = "8")

        service.forMatch(code = match.publicCode).shouldBeRight().rows.shouldHaveSize(size = 1)

        SettingsService().setHideRankingPoints(token = token(uid = "root"), hidden = true).shouldBeRight()

        // Same shape as the event card: an empty summary, not a Forbidden — the endpoint is public and
        // the match is real, there is simply nothing this viewer may see.
        service.forMatch(code = match.publicCode).shouldBeRight().rows.shouldBeEmpty()
        service.forMatch(code = match.publicCode, token = token(uid = "ben")).shouldBeRight().rows.shouldBeEmpty()
        service.forMatch(code = match.publicCode, token = token(uid = "root")).shouldBeRight().rows.shouldHaveSize(size = 1)
    }
}
