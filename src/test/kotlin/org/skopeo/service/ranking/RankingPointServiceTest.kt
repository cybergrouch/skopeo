// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.ranking

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuthProvider
import org.skopeo.model.AwardStatus
import org.skopeo.model.Capability
import org.skopeo.model.GrantRankingPointCommand
import org.skopeo.model.NameType
import org.skopeo.model.PointClass
import org.skopeo.model.PointSourceType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.ServiceError
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.repository.RankingPointRepository
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.user.VerifiedFirebaseToken
import org.skopeo.testsupport.PostgresTestDatabase
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

class RankingPointServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val ratings = RatingRepository()
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
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                    sex = sex,
                    capabilities = roles,
                ),
        )

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid)

    private fun grantCommand(
        userId: UUID,
        points: String = "100",
        pointClass: PointClass = PointClass.ANNUAL_TOURNAMENT,
        sourceType: PointSourceType = PointSourceType.INTERNAL,
        sourceId: String? = null,
        band: String? = null,
        reason: String? = null,
        validFrom: LocalDateTime? = null,
        validUntil: LocalDateTime? = null,
    ) = GrantRankingPointCommand(
        userId = userId,
        points = BigDecimal(points),
        pointClass = pointClass,
        sourceType = sourceType,
        sourceId = sourceId,
        band = band,
        reason = reason,
        validFrom = validFrom,
        validUntil = validUntil,
    )

    @Test
    fun `an admin grants an award band-tagged from the target rating with policy validity`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player", sex = "Female")
        ratings.setRating(userId = player.id, rating = BigDecimal("4.3"), level = "4.0")

        val award = service.grant(token = token(uid = "admin"), command = grantCommand(userId = player.id)).shouldBeRight()
        // Band tagged from the current rating (4.3 → 4.0 band); sex from the target user.
        award.band shouldBe "4.0"
        award.sex shouldBe "Female"
        award.status shouldBe AwardStatus.ACTIVE
        // Validity from the ANNUAL_TOURNAMENT policy (12 months).
        award.validUntil shouldBe award.validFrom.plusMonths(12)
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
                command = grantCommand(userId = player.id, band = "5.5", validFrom = from, validUntil = until),
            ).shouldBeRight()
        award.band shouldBe "5.5"
        award.validFrom shouldBe from
        award.validUntil shouldBe until
    }

    @Test
    fun `an external grant requires a reason`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player")
        ratings.setRating(userId = player.id, rating = BigDecimal("4.3"), level = "4.0")

        // No reason on an external grant → Validation.
        service.grant(
            token = token(uid = "admin"),
            command = grantCommand(userId = player.id, pointClass = PointClass.EXTERNAL, sourceType = PointSourceType.EXTERNAL),
        ).shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()

        // With a reason it succeeds.
        service.grant(
            token = token(uid = "admin"),
            command =
                grantCommand(
                    userId = player.id,
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

        service.grant(token = token(uid = "admin"), command = grantCommand(userId = player.id, points = "0"))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        service.grant(token = token(uid = "admin"), command = grantCommand(userId = player.id, points = "-5"))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `fractional points are rejected while an integral grant with trailing zeros succeeds`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player")
        ratings.setRating(userId = player.id, rating = BigDecimal("4.0"), level = "4.0")

        // A fractional grant (100.5) is rejected per Decision #6 (#403).
        service.grant(token = token(uid = "admin"), command = grantCommand(userId = player.id, points = "100.5"))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()

        // An integral value with trailing zeros (100.0000) is still accepted.
        service.grant(token = token(uid = "admin"), command = grantCommand(userId = player.id, points = "100.0000"))
            .shouldBeRight()
    }

    @Test
    fun `an unrated target with no explicit band is a validation error`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player")

        service.grant(token = token(uid = "admin"), command = grantCommand(userId = player.id))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `granting is administrator-only`() {
        provision(uid = "rater", roles = setOf(Capability.PLAYER, Capability.RATER))
        val player = provision(uid = "player")
        ratings.setRating(userId = player.id, rating = BigDecimal("4.0"), level = "4.0")

        service.grant(token = token(uid = "rater"), command = grantCommand(userId = player.id))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        // An unprovisioned caller is also forbidden (null-caller arm).
        service.grant(token = token(uid = "ghost"), command = grantCommand(userId = player.id))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `revoke flags the award and is a not-found for a missing or already-revoked award`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player")
        ratings.setRating(userId = player.id, rating = BigDecimal("4.0"), level = "4.0")
        val award = service.grant(token = token(uid = "admin"), command = grantCommand(userId = player.id)).shouldBeRight()

        service.revoke(token = token(uid = "admin"), awardId = award.id, reason = "duplicate").shouldBeRight()
        // The ledger now has the original (revoked) + the marker.
        service.listForUser(token = token(uid = "admin"), userId = player.id).shouldBeRight() shouldHaveSize 2

        // A second revoke is a not-found.
        service.revoke(token = token(uid = "admin"), awardId = award.id, reason = null)
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
        val award = service.grant(token = token(uid = "admin"), command = grantCommand(userId = player.id)).shouldBeRight()

        service.revoke(token = token(uid = "player-user"), awardId = award.id, reason = null)
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
        service.grant(token = token(uid = "admin"), command = grantCommand(userId = UUID.randomUUID(), band = "4.0"))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `granting to a deactivated user is a validation error`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player")
        users.deactivate(id = player.id).shouldBeRight()

        service.grant(token = token(uid = "admin"), command = grantCommand(userId = player.id, band = "4.0"))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `a validity window that does not advance is a validation error`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player")
        val now = LocalDateTime.now()

        service.grant(
            token = token(uid = "admin"),
            command = grantCommand(userId = player.id, band = "4.0", validFrom = now, validUntil = now.minusDays(1)),
        ).shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `a blank explicit band falls back to the target's current band`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player")
        ratings.setRating(userId = player.id, rating = BigDecimal("4.3"), level = "4.0")

        val award =
            service.grant(token = token(uid = "admin"), command = grantCommand(userId = player.id, band = "  ")).shouldBeRight()
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
                command = grantCommand(userId = player.id, sourceId = "event-42"),
            ).shouldBeRight()
        award.sourceId shouldBe "event-42"
        award.sex shouldBe "Unspecified"

        // A blank sourceId normalizes to null (the ifBlank arm).
        val blankSource =
            service.grant(token = token(uid = "admin"), command = grantCommand(userId = player.id, sourceId = "  ")).shouldBeRight()
        blankSource.sourceId shouldBe null
    }

    @Test
    fun `an internal grant with a blank reason stores no reason`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player")
        ratings.setRating(userId = player.id, rating = BigDecimal("4.0"), level = "4.0")

        val award =
            service.grant(token = token(uid = "admin"), command = grantCommand(userId = player.id, reason = "   ")).shouldBeRight()
        award.reason shouldBe null
    }

    @Test
    fun `revoke accepts and normalizes a blank reason`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player")
        ratings.setRating(userId = player.id, rating = BigDecimal("4.0"), level = "4.0")
        val award = service.grant(token = token(uid = "admin"), command = grantCommand(userId = player.id)).shouldBeRight()

        service.revoke(token = token(uid = "admin"), awardId = award.id, reason = "   ").shouldBeRight()
    }

    @Test
    fun `an external grant with a blank reason is a validation error`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player")

        service.grant(
            token = token(uid = "admin"),
            command =
                grantCommand(
                    userId = player.id,
                    band = "4.0",
                    pointClass = PointClass.EXTERNAL,
                    sourceType = PointSourceType.EXTERNAL,
                    reason = "   ",
                ),
        ).shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }
}
