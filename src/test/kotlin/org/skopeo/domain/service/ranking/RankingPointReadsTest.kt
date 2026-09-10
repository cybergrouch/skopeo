// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.ranking

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
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
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.PointClass
import org.skopeo.domain.model.PointSourceType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.RankingPointAwardWrite
import org.skopeo.domain.model.User
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.domain.service.rating.RatingAssembler
import org.skopeo.domain.service.settings.SettingsService
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.repository.EventRepository
import org.skopeo.repository.RankingPointRepository
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.seedClub
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import org.skopeo.domain.mapper.entity.event.toDomain as toEventDomain

/**
 * The **read** side of [RankingPointService]: the event points card (#857, #865) and award derivation
 * (#862).
 *
 * Split out of `RankingPointServiceTest` in #921, which tipped that class over detekt's `LargeClass`.
 * The seam is the one the suite already had: everything here is a public/privileged *query* over
 * awards that already exist, so these tests seed rows directly through the repository and never call
 * `grant`. The write operations — grant, revoke, adjust, and their authorization — stay next door.
 */
class RankingPointReadsTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val ratings = RatingAssembler()
    private val awards = RankingPointRepository()
    private val service = RankingPointService(awards = awards, users = users, ratings = ratings)

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun provision(
        uid: String,
        roles: Set<Capability> = setOf(element = Capability.PLAYER),
        sex: String? = "Male",
    ): User =
        users.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid.asRedactable(),
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                    sex = sex,
                    capabilities = roles,
                ),
        ).toDomain()

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid.asRedactable())

    /** An event to hang awards off; every event needs a club (#794). */
    private fun seedEvent(createdBy: UUID) =
        EventRepository()
            .create(
                command =
                    CreateEventCommand(
                        name = "Cup",
                        startDate = LocalDate.now(),
                        endDate = LocalDate.now().plusDays(7),
                        participantIds = emptyList(),
                        createdBy = createdBy,
                        clubId = seedClub().id,
                    ),
            ).toEventDomain()

    /** Write one ACTIVE award for [userId] attributed to [eventId]. */
    private fun eventAward(
        userId: UUID,
        eventId: UUID,
        points: String,
        status: AwardStatus = AwardStatus.ACTIVE,
        validUntil: LocalDateTime = LocalDateTime.now().plusMonths(6),
    ) = awards.award(
        write =
            RankingPointAwardWrite(
                userId = userId,
                points = BigDecimal(points),
                pointClass = PointClass.OPEN_PLAY,
                sourceType = PointSourceType.INTERNAL,
                sourceId = eventId.toString(),
                band = "4.0",
                sex = "Male",
                reason = null,
                validFrom = LocalDateTime.now().minusDays(1),
                validUntil = validUntil,
                status = status,
                revokesAwardId = null,
                grantedBy = null,
                awardedAt = LocalDateTime.now(),
                eventId = eventId,
                // v1 is what a freshly migrated database seeds (#862).
                pointsScheduleVersion = 1,
            ),
    )

    @Test
    fun `an event's awarded points are summed per player, highest first (#857)`() {
        val host = provision(uid = "host")
        val winner = provision(uid = "winner")
        val runnerUp = provision(uid = "runner")
        val event = seedEvent(createdBy = host.id)
        // Two awards for the same player must fold into one row — a player earns per set (#836).
        eventAward(userId = winner.id, eventId = event.id, points = "7")
        eventAward(userId = winner.id, eventId = event.id, points = "5")
        eventAward(userId = runnerUp.id, eventId = event.id, points = "2")

        val summary = service.awardedForEvent(code = event.publicCode).shouldBeRight()

        // Points keep the column's scale (NUMERIC(_,4)), as the ledger DTO already does — the client
        // formats them via formatPoints rather than the server pre-rounding.
        summary.rows.map { it.displayName to it.points } shouldBe
            listOf("winner" to "12.0000", "runner" to "2.0000")
        summary.totalPoints shouldBe "14.0000"
    }

    @Test
    fun `a revoked award is excluded, since it paid nothing (#857)`() {
        val host = provision(uid = "host")
        val player = provision(uid = "player")
        val event = seedEvent(createdBy = host.id)
        eventAward(userId = player.id, eventId = event.id, points = "5")
        eventAward(userId = player.id, eventId = event.id, points = "9", status = AwardStatus.REVOKED)

        val summary = service.awardedForEvent(code = event.publicCode).shouldBeRight()

        // Only the live row counts, matching every standings query.
        summary.rows.single().points shouldBe "5.0000"
        summary.totalPoints shouldBe "5.0000"
    }

    @Test
    fun `an expired award is still listed, because the event did award it (#857)`() {
        val host = provision(uid = "host")
        val player = provision(uid = "player")
        val event = seedEvent(createdBy = host.id)
        eventAward(userId = player.id, eventId = event.id, points = "6", validUntil = LocalDateTime.now().minusDays(1))

        val summary = service.awardedForEvent(code = event.publicCode).shouldBeRight()

        // This is the one read of this table that deliberately ignores validity: "what did this event
        // award" does not stop being true when the points expire.
        summary.rows.single().points shouldBe "6.0000"
    }

    @Test
    fun `an event with no awards returns an empty list rather than an error (#857)`() {
        val host = provision(uid = "host")
        val event = seedEvent(createdBy = host.id)

        val summary = service.awardedForEvent(code = event.publicCode).shouldBeRight()

        // The client renders no card at all — an event may be unfinalized, or have awarding off (#831).
        summary.rows.shouldBeEmpty()
        summary.totalPoints shouldBe "0"
    }

    @Test
    fun `an unknown event code is a NotFound (#857)`() {
        service.awardedForEvent(code = "NOPE12").shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `the event points card is suppressed for an unprivileged viewer while the flag is on (#865)`() {
        val host = provision(uid = "host")
        val player = provision(uid = "player")
        val event = seedEvent(createdBy = host.id)
        eventAward(userId = player.id, eventId = event.id, points = "7")
        provision(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val settings = SettingsService()

        // Visible to everyone while the flag is off — including anonymously, which is the default.
        service.awardedForEvent(code = event.publicCode).shouldBeRight().rows.shouldHaveSize(size = 1)

        settings.setHideRankingPoints(token = token(uid = "root"), hidden = true).shouldBeRight()

        // Anonymous and plain-player viewers get an empty summary. Not a Forbidden: the endpoint is public
        // and the event is real — there is simply nothing this viewer may see, and the client renders no
        // card for an empty list exactly as it does for an event that awarded nothing.
        service.awardedForEvent(code = event.publicCode).shouldBeRight().rows.shouldBeEmpty()
        service.awardedForEvent(code = event.publicCode, token = token(uid = "player"))
            .shouldBeRight().rows.shouldBeEmpty()
        // ...while an administrator still sees it.
        service.awardedForEvent(code = event.publicCode, token = token(uid = "root"))
            .shouldBeRight().rows.shouldHaveSize(size = 1)
    }

    @Test
    fun `each exempt role still sees the event points card while the flag is on (#865)`() {
        val host = provision(uid = "host")
        val player = provision(uid = "player")
        val event = seedEvent(createdBy = host.id)
        eventAward(userId = player.id, eventId = event.id, points = "7")
        provision(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        SettingsService().setHideRankingPoints(token = token(uid = "root"), hidden = true).shouldBeRight()

        // All five of PLAYER_POINTS_VIEW_ROLES, so the flag's exemption cannot silently narrow.
        listOf(
            Capability.HOST,
            Capability.CLUB_OWNER,
            Capability.RATER,
            Capability.POINTS_MANAGER,
            Capability.ADMINISTRATOR,
        ).forEach { role ->
            provision(uid = "sees-$role", roles = setOf(Capability.PLAYER, role))
            withClue(clue = "$role should still see the card") {
                service.awardedForEvent(code = event.publicCode, token = token(uid = "sees-$role"))
                    .shouldBeRight().rows.shouldHaveSize(size = 1)
            }
        }
    }

    @Test
    fun `a points manager and an administrator can read an award's derivation (#862)`() {
        val player = provision(uid = "player")
        val host = provision(uid = "host")
        val event = seedEvent(createdBy = host.id)
        val awardId = eventAward(userId = player.id, eventId = event.id, points = "7").id
        provision(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        provision(uid = "pm", roles = setOf(Capability.PLAYER, Capability.POINTS_MANAGER))

        listOf("root", "pm").forEach { uid ->
            withClue(clue = "$uid runs the Points Management tab, so the derivation is theirs to read") {
                val derivation = service.derivation(token = token(uid = uid), awardId = awardId).shouldBeRight()
                derivation.awardId shouldBe awardId.toString()
                derivation.points shouldBe "7.0000"
            }
        }
    }

    @Test
    fun `nobody outside the Points Management roles can read a derivation (#862)`() {
        val player = provision(uid = "player")
        val host = provision(uid = "host")
        val event = seedEvent(createdBy = host.id)
        val awardId = eventAward(userId = player.id, eventId = event.id, points = "7").id
        // A rater sees derivations on the PUBLIC match card (#858) but has no business in this tool; the
        // two surfaces gate separately, and this pins that they do.
        provision(uid = "rater", roles = setOf(Capability.PLAYER, Capability.RATER))

        listOf("player", "host", "rater").forEach { uid ->
            withClue(clue = "$uid must not reach the ledger's derivation") {
                service
                    .derivation(token = token(uid = uid), awardId = awardId)
                    .shouldBeLeft()
                    .shouldBeInstanceOf<ServiceError.Forbidden>()
            }
        }
    }

    @Test
    fun `an unknown award id is a NotFound rather than an empty derivation (#862)`() {
        provision(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))

        service
            .derivation(token = token(uid = "root"), awardId = UUID.randomUUID())
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }
}
