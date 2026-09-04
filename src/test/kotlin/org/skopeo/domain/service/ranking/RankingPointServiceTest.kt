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
import org.skopeo.common.dto.ranking.AdjustRankingPointsRequest
import org.skopeo.common.dto.ranking.GrantRankingPointsRequest
import org.skopeo.common.error.ServiceError
import org.skopeo.common.redaction.asRedactable
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuditAction
import org.skopeo.domain.model.AuditEntityType
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
import org.skopeo.repository.AuditRepository
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

class RankingPointServiceTest {
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

    private fun grantRequest(
        points: String = "100",
        pointClass: PointClass = PointClass.ANNUAL_TOURNAMENT,
        sourceType: PointSourceType = PointSourceType.INTERNAL,
        sourceId: String? = null,
        band: String? = null,
        reason: String? = null,
        validFrom: LocalDateTime? = null,
        validUntil: LocalDateTime? = null,
    ) = GrantRankingPointsRequest(
        points = points,
        pointClass = pointClass.name,
        sourceType = sourceType.name,
        sourceId = sourceId,
        band = band,
        reason = reason,
        validFrom = validFrom?.toString(),
        validUntil = validUntil?.toString(),
    )

    @Test
    fun `an admin grants an award band-tagged from the target rating with policy validity`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player", sex = "Female")
        ratings.setRating(userId = player.id, rating = BigDecimal("4.3"), level = "4.0")

        val award = service.grant(token = token(uid = "admin"), userId = player.id, request = grantRequest()).shouldBeRight()
        // Band tagged from the current rating (4.3 → 4.0 band); sex from the target user.
        award.band shouldBe "4.0"
        award.sex shouldBe "Female"
        award.status shouldBe AwardStatus.ACTIVE.name
        // Validity from the ANNUAL_TOURNAMENT policy (12 months).
        LocalDateTime.parse(award.validUntil) shouldBe LocalDateTime.parse(award.validFrom).plusMonths(12)
    }

    @Test
    fun `a grant records an audit entry targeting the player (#471)`() {
        val admin = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player", sex = "Female")
        ratings.setRating(userId = player.id, rating = BigDecimal("4.3"), level = "4.0")

        val award = service.grant(token = token(uid = "admin"), userId = player.id, request = grantRequest()).shouldBeRight()

        // Target = the player (USER), not the ledger row, so the Activity Log links to the player (#471).
        val entry =
            AuditRepository()
                .list(actions = listOf(element = AuditAction.RANKING_POINTS_AWARDED), limit = 10, offset = 0)
                .first.single()
        entry.entityType shouldBe AuditEntityType.USER.name
        entry.entityId shouldBe player.id
        entry.actorUserId shouldBe admin.id
        entry.details["awardId"] shouldBe award.id
        entry.details["userId"] shouldBe player.id.toString()
    }

    @Test
    fun `explicit band and validity overrides are honored`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player")
        val from = LocalDateTime.of(2026, 1, 1, 0, 0)
        val until = from.plusDays(10)

        val award =
            service.grant(
                token = token(uid = "admin"),
                userId = player.id,
                request = grantRequest(band = "5.5", validFrom = from, validUntil = until),
            ).shouldBeRight()
        award.band shouldBe "5.5"
        LocalDateTime.parse(award.validFrom) shouldBe from
        LocalDateTime.parse(award.validUntil) shouldBe until
    }

    @Test
    fun `an external grant requires a reason`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player")
        ratings.setRating(userId = player.id, rating = BigDecimal("4.3"), level = "4.0")

        // No reason on an external grant → Validation.
        service.grant(
            token = token(uid = "admin"),
            userId = player.id,
            request = grantRequest(pointClass = PointClass.EXTERNAL, sourceType = PointSourceType.EXTERNAL),
        ).shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()

        // With a reason it succeeds.
        service.grant(
            token = token(uid = "admin"),
            userId = player.id,
            request =
                grantRequest(
                    pointClass = PointClass.EXTERNAL,
                    sourceType = PointSourceType.EXTERNAL,
                    reason = "credited by partner league",
                ),
        ).shouldBeRight().reason shouldBe "credited by partner league"
    }

    @Test
    fun `non-positive points are a validation error`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player")
        ratings.setRating(userId = player.id, rating = BigDecimal("4.0"), level = "4.0")

        service.grant(token = token(uid = "admin"), userId = player.id, request = grantRequest(points = "0"))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        service.grant(token = token(uid = "admin"), userId = player.id, request = grantRequest(points = "-5"))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `fractional points are rejected while an integral grant with trailing zeros succeeds`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player")
        ratings.setRating(userId = player.id, rating = BigDecimal("4.0"), level = "4.0")

        // A fractional grant (100.5) is rejected per Decision #6 (#403).
        service.grant(token = token(uid = "admin"), userId = player.id, request = grantRequest(points = "100.5"))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()

        // An integral value with trailing zeros (100.0000) is still accepted.
        service.grant(token = token(uid = "admin"), userId = player.id, request = grantRequest(points = "100.0000"))
            .shouldBeRight()
    }

    @Test
    fun `an unrated target with no explicit band is a validation error`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player")

        service.grant(token = token(uid = "admin"), userId = player.id, request = grantRequest())
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `granting is administrator-only`() {
        provision(uid = "rater", roles = setOf(Capability.PLAYER, Capability.RATER))
        val player = provision(uid = "player")
        ratings.setRating(userId = player.id, rating = BigDecimal("4.0"), level = "4.0")

        service.grant(token = token(uid = "rater"), userId = player.id, request = grantRequest())
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        // An unprovisioned caller is also forbidden (null-caller arm).
        service.grant(token = token(uid = "ghost"), userId = player.id, request = grantRequest())
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `revoke flags the award and is a not-found for a missing or already-revoked award`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player")
        ratings.setRating(userId = player.id, rating = BigDecimal("4.0"), level = "4.0")
        val award = service.grant(token = token(uid = "admin"), userId = player.id, request = grantRequest()).shouldBeRight()

        service.revoke(token = token(uid = "admin"), awardId = UUID.fromString(award.id), reason = "duplicate").shouldBeRight()
        // The ledger now has the original (revoked) + the marker.
        service.listForUser(token = token(uid = "admin"), userId = player.id).shouldBeRight() shouldHaveSize 2

        // A second revoke is a not-found.
        service.revoke(token = token(uid = "admin"), awardId = UUID.fromString(award.id), reason = null)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
        service.revoke(token = token(uid = "admin"), awardId = UUID.randomUUID(), reason = null)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `revoke and list are administrator-only`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        provision(uid = "player-user")
        val player = provision(uid = "player")
        ratings.setRating(userId = player.id, rating = BigDecimal("4.0"), level = "4.0")
        val award = service.grant(token = token(uid = "admin"), userId = player.id, request = grantRequest()).shouldBeRight()

        service.revoke(token = token(uid = "player-user"), awardId = UUID.fromString(award.id), reason = null)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.listForUser(token = token(uid = "player-user"), userId = player.id)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `list is a not-found for an unknown user`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        service.listForUser(token = token(uid = "admin"), userId = UUID.randomUUID())
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `granting to an unknown user is a validation error`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        service.grant(token = token(uid = "admin"), userId = UUID.randomUUID(), request = grantRequest(band = "4.0"))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `granting to a deactivated user is a validation error`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player")
        users.deactivate(id = player.id).shouldBeRight()

        service.grant(token = token(uid = "admin"), userId = player.id, request = grantRequest(band = "4.0"))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `a validity window that does not advance is a validation error`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player")
        val now = LocalDateTime.now()

        service.grant(
            token = token(uid = "admin"),
            userId = player.id,
            request = grantRequest(band = "4.0", validFrom = now, validUntil = now.minusDays(1)),
        ).shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `a blank explicit band falls back to the target's current band`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player")
        ratings.setRating(userId = player.id, rating = BigDecimal("4.3"), level = "4.0")

        val award =
            service.grant(token = token(uid = "admin"), userId = player.id, request = grantRequest(band = "  ")).shouldBeRight()
        award.band shouldBe "4.0"
    }

    @Test
    fun `an award records a trimmed sourceId and defaults sex to Unspecified for a sexless target`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player", sex = null)
        ratings.setRating(userId = player.id, rating = BigDecimal("4.0"), level = "4.0")

        val award =
            service.grant(
                token = token(uid = "admin"),
                userId = player.id,
                request = grantRequest(sourceId = "event-42"),
            ).shouldBeRight()
        award.sourceId shouldBe "event-42"
        award.sex shouldBe "Unspecified"

        // A blank sourceId normalizes to null (the ifBlank arm).
        val blankSource =
            service.grant(token = token(uid = "admin"), userId = player.id, request = grantRequest(sourceId = "  ")).shouldBeRight()
        blankSource.sourceId shouldBe null
    }

    @Test
    fun `an internal grant with a blank reason stores no reason`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player")
        ratings.setRating(userId = player.id, rating = BigDecimal("4.0"), level = "4.0")

        val award =
            service.grant(token = token(uid = "admin"), userId = player.id, request = grantRequest(reason = "   ")).shouldBeRight()
        award.reason shouldBe null
    }

    @Test
    fun `revoke accepts and normalizes a blank reason`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player")
        ratings.setRating(userId = player.id, rating = BigDecimal("4.0"), level = "4.0")
        val award = service.grant(token = token(uid = "admin"), userId = player.id, request = grantRequest()).shouldBeRight()

        service.revoke(token = token(uid = "admin"), awardId = UUID.fromString(award.id), reason = "   ").shouldBeRight()
    }

    @Test
    fun `listAwards returns a newest-first page resolving the player and manual source (#472)`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val alice = provision(uid = "alice", sex = "Female")
        val bob = provision(uid = "bob")
        ratings.setRating(userId = alice.id, rating = BigDecimal("4.0"), level = "4.0")
        ratings.setRating(userId = bob.id, rating = BigDecimal("3.5"), level = "3.5")

        val alicePoints =
            service.grant(
                token = token(uid = "admin"),
                userId = alice.id,
                request = grantRequest(points = "10"),
            ).shouldBeRight()
        val bobPoints = service.grant(token = token(uid = "admin"), userId = bob.id, request = grantRequest(points = "20")).shouldBeRight()

        val page = service.listAwards(token = token(uid = "admin"), limit = 25, offset = 0).shouldBeRight()
        page.total shouldBe 2
        // The whole ledger is returned across users (both grants, no strict-time ordering assumed here —
        // the newest-first order is asserted precisely in the repository test with distinct awarded_at).
        page.rows.map { it.id }.toSet() shouldBe setOf(alicePoints.id, bobPoints.id)
        // The player identity is resolved (display name + public code); a manual grant's source is "manual".
        val bobRow = page.rows.single { it.userId == bob.id.toString() }
        bobRow.playerDisplayName shouldBe "bob"
        bobRow.playerPublicCode shouldBe bob.publicCode
        bobRow.matchPublicCode shouldBe null
        bobRow.eventPublicCode shouldBe null
    }

    @Test
    fun `listAwards honors the page window (#472)`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player")
        ratings.setRating(userId = player.id, rating = BigDecimal("4.0"), level = "4.0")
        repeat(times = 3) { service.grant(token = token(uid = "admin"), userId = player.id, request = grantRequest()).shouldBeRight() }

        val windowed = service.listAwards(token = token(uid = "admin"), limit = 1, offset = 1).shouldBeRight()
        windowed.total shouldBe 3
        windowed.limit shouldBe 1
        windowed.offset shouldBe 1
        windowed.rows shouldHaveSize 1
    }

    @Test
    fun `listAwards is allowed for a points manager and for an administrator but forbidden otherwise (#472)`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        provision(uid = "manager", roles = setOf(Capability.PLAYER, Capability.POINTS_MANAGER))
        provision(uid = "plain")

        service.listAwards(token = token(uid = "admin"), limit = 25, offset = 0).shouldBeRight()
        service.listAwards(token = token(uid = "manager"), limit = 25, offset = 0).shouldBeRight()
        service.listAwards(token = token(uid = "plain"), limit = 25, offset = 0)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        // An unprovisioned caller is also forbidden (null-caller arm).
        service.listAwards(token = token(uid = "ghost"), limit = 25, offset = 0)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `an external grant with a blank reason is a validation error`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player")

        service.grant(
            token = token(uid = "admin"),
            userId = player.id,
            request =
                grantRequest(
                    band = "4.0",
                    pointClass = PointClass.EXTERNAL,
                    sourceType = PointSourceType.EXTERNAL,
                    reason = "   ",
                ),
        ).shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    private fun adjustRequest(
        points: String = "50",
        reason: String = "manual correction",
        validFrom: LocalDateTime = LocalDateTime.of(2026, 1, 1, 0, 0),
        validUntil: LocalDateTime = LocalDateTime.of(2026, 6, 1, 0, 0),
    ) = AdjustRankingPointsRequest(
        points = points,
        reason = reason,
        validFrom = validFrom.toString(),
        validUntil = validUntil.toString(),
    )

    @Test
    fun `an admin awards positive points as a signed EXTERNAL adjustment with explicit validity (#469)`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player", sex = "Female")
        ratings.setRating(userId = player.id, rating = BigDecimal("4.3"), level = "4.0")
        val from = LocalDateTime.of(2026, 2, 1, 0, 0)
        val until = from.plusDays(30)

        val award =
            service.adjust(
                token = token(uid = "admin"),
                userId = player.id,
                request = adjustRequest(points = "75", reason = "bonus", validFrom = from, validUntil = until),
            ).shouldBeRight()
        award.points shouldBe BigDecimal("75.0000").toPlainString()
        award.pointClass shouldBe PointClass.EXTERNAL.name
        award.sourceType shouldBe PointSourceType.EXTERNAL.name
        award.band shouldBe "4.0"
        award.sex shouldBe "Female"
        award.reason shouldBe "bonus"
        award.status shouldBe AwardStatus.ACTIVE.name
        LocalDateTime.parse(award.validFrom) shouldBe from
        LocalDateTime.parse(award.validUntil) shouldBe until
    }

    @Test
    fun `an admin deducts negative points as a signed adjustment, trimming the reason (#469)`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player")
        ratings.setRating(userId = player.id, rating = BigDecimal("4.0"), level = "4.0")

        val award =
            service.adjust(
                token = token(uid = "admin"),
                userId = player.id,
                request = adjustRequest(points = "-40", reason = "  penalty  "),
            ).shouldBeRight()
        award.points shouldBe BigDecimal("-40.0000").toPlainString()
        award.reason shouldBe "penalty"
    }

    @Test
    fun `a manual adjustment audits with the player as the target (#469, #471)`() {
        val admin = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player")
        ratings.setRating(userId = player.id, rating = BigDecimal("4.0"), level = "4.0")

        val award = service.adjust(token = token(uid = "admin"), userId = player.id, request = adjustRequest()).shouldBeRight()

        val entry =
            AuditRepository()
                .list(actions = listOf(element = AuditAction.RANKING_POINTS_AWARDED), limit = 10, offset = 0)
                .first.single()
        entry.entityType shouldBe AuditEntityType.USER.name
        entry.entityId shouldBe player.id
        entry.actorUserId shouldBe admin.id
        entry.details["awardId"] shouldBe award.id
        entry.details["points"] shouldBe award.points
    }

    @Test
    fun `zero, fractional, and blank-reason adjustments are validation errors (#469)`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player")
        ratings.setRating(userId = player.id, rating = BigDecimal("4.0"), level = "4.0")

        service.adjust(token = token(uid = "admin"), userId = player.id, request = adjustRequest(points = "0"))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        service.adjust(token = token(uid = "admin"), userId = player.id, request = adjustRequest(points = "10.5"))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        service.adjust(token = token(uid = "admin"), userId = player.id, request = adjustRequest(points = "-10.5"))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        service.adjust(token = token(uid = "admin"), userId = player.id, request = adjustRequest(reason = "   "))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        // A signed integer with trailing zeros is still whole → accepted.
        service.adjust(token = token(uid = "admin"), userId = player.id, request = adjustRequest(points = "-50.0000"))
            .shouldBeRight()
    }

    @Test
    fun `an adjustment whose window does not advance is a validation error (#469)`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player")
        ratings.setRating(userId = player.id, rating = BigDecimal("4.0"), level = "4.0")
        val from = LocalDateTime.of(2026, 3, 1, 0, 0)

        service.adjust(
            token = token(uid = "admin"),
            userId = player.id,
            request = adjustRequest(validFrom = from, validUntil = from.minusDays(1)),
        ).shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `an unrated target cannot be adjusted (#469)`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player")

        service.adjust(token = token(uid = "admin"), userId = player.id, request = adjustRequest())
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `adjusting is administrator-only (#469)`() {
        provision(uid = "rater", roles = setOf(Capability.PLAYER, Capability.RATER))
        val player = provision(uid = "player")
        ratings.setRating(userId = player.id, rating = BigDecimal("4.0"), level = "4.0")

        service.adjust(token = token(uid = "rater"), userId = player.id, request = adjustRequest())
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        // An unprovisioned caller is also forbidden (null-caller arm).
        service.adjust(token = token(uid = "ghost"), userId = player.id, request = adjustRequest())
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `adjusting an unknown or deactivated user is a validation error (#469)`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        service.adjust(token = token(uid = "admin"), userId = UUID.randomUUID(), request = adjustRequest())
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()

        val player = provision(uid = "player")
        ratings.setRating(userId = player.id, rating = BigDecimal("4.0"), level = "4.0")
        users.deactivate(id = player.id).shouldBeRight()
        service.adjust(token = token(uid = "admin"), userId = player.id, request = adjustRequest())
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

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
