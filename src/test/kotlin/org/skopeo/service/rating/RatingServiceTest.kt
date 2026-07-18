// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.rating

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuditAction
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.ServiceError
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.repository.AuditRepository
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.user.VerifiedFirebaseToken
import org.skopeo.testsupport.PostgresTestDatabase
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class RatingServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val ratings = RatingRepository()
    private val service = RatingService(ratings = ratings, users = users)

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun provisionUser(
        uid: String,
        roles: Set<Capability> = setOf(Capability.PLAYER),
    ): User =
        users.provision(
            ProvisionUserCommand(
                firebaseUid = uid,
                identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                names = listOf(UserName(type = NameType.DISPLAY, value = uid)),
                capabilities = roles,
            ),
        )

    private fun admin(uid: String = "root") = provisionUser(uid = uid, roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid)

    @Test
    fun `admin sets a rating and the published level is derived`() {
        admin(uid = "root")
        val player = provisionUser(uid = "player")

        val rating =
            service.setRating(token = token(uid = "root"), userId = player.id, value = BigDecimal("4.3")).shouldBeRight()

        rating.currentRating.toPlainString() shouldBe "4.300000" // stored as NUMERIC(10,6)
        rating.currentLevel shouldBe "4.0" // NTRP rounds down to the 0.5 level
        // The player has no in-window matches, so confidence computes to 0 (#459).
        rating.confidence.toPlainString() shouldBe "0.000000"
    }

    @Test
    fun `the initial assessment writes no history, but a later override is recorded (#96)`() {
        admin(uid = "root")
        val player = provisionUser(uid = "player")

        // Initial assessment — the baseline, no history row.
        service.setRating(token = token(uid = "root"), userId = player.id, value = BigDecimal("4.0")).shouldBeRight()
        service.getHistory(token = token(uid = "root"), userId = player.id).shouldBeRight() shouldBe emptyList()

        // Override — recorded as a manual (matchId = null) history entry.
        service.setRating(token = token(uid = "root"), userId = player.id, value = BigDecimal("4.6")).shouldBeRight()
        val history = service.getHistory(token = token(uid = "root"), userId = player.id).shouldBeRight()
        history shouldHaveSize 1
        history.single().let {
            it.matchId shouldBe null
            it.previousRating.toPlainString() shouldBe "4.000000"
            it.newRating.toPlainString() shouldBe "4.600000"
            it.previousLevel shouldBe "4.0"
            it.newLevel shouldBe "4.5"
            it.levelChanged shouldBe true
        }
    }

    @Test
    fun `setting then overriding a rating write audit-log entries (#100)`() {
        val root = admin(uid = "root")
        val player = provisionUser(uid = "player")
        service.setRating(token = token(uid = "root"), userId = player.id, value = BigDecimal("4.0")).shouldBeRight()
        service.setRating(token = token(uid = "root"), userId = player.id, value = BigDecimal("4.5")).shouldBeRight()

        val audit = AuditRepository()
        audit.list(actions = listOf(element = AuditAction.RATING_SET), limit = 10, offset = 0).first.single().let {
            it.actorUserId shouldBe root.id
            it.entityId shouldBe player.id
            it.summary shouldBe "Set rating to 4.0"
        }
        audit.list(actions = listOf(element = AuditAction.RATING_OVERRIDDEN), limit = 10, offset = 0).first.single().let {
            it.summary shouldBe "Overrode rating 4.0 → 4.5"
            it.details["previousRating"] shouldBe "4.000000"
            it.details["newRating"] shouldBe "4.500000"
        }
    }

    @Test
    fun `an override within the same NTRP band records levelChanged false`() {
        admin(uid = "root")
        val player = provisionUser(uid = "player")
        service.setRating(token = token(uid = "root"), userId = player.id, value = BigDecimal("4.0")).shouldBeRight()
        // 4.0 and 4.2 both publish as the "4.0" band, so the override doesn't cross a level.
        service.setRating(token = token(uid = "root"), userId = player.id, value = BigDecimal("4.2")).shouldBeRight()

        service.getHistory(token = token(uid = "root"), userId = player.id).shouldBeRight().single().let {
            it.previousLevel shouldBe "4.0"
            it.newLevel shouldBe "4.0"
            it.levelChanged shouldBe false
        }
    }

    @Test
    fun `a RATER or admin may set a rating, but a plain player or unknown caller may not (#106)`() {
        admin(uid = "root")
        val rater = provisionUser(uid = "rater", roles = setOf(Capability.PLAYER, Capability.RATER))
        val player = provisionUser(uid = "player")

        // A RATER (and, implicitly, an ADMINISTRATOR) may set a rating.
        service
            .setRating(token = token(uid = "rater"), userId = player.id, value = BigDecimal("4.0"))
            .shouldBeRight()
        service
            .setRating(token = token(uid = "root"), userId = rater.id, value = BigDecimal("3.5"))
            .shouldBeRight()

        // A plain PLAYER and an unprovisioned caller may not.
        service
            .setRating(token = token(uid = "player"), userId = player.id, value = BigDecimal("4.0"))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
        service
            .setRating(token = token(uid = "ghost"), userId = player.id, value = BigDecimal("4.0"))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `reading ratings and history is self-or-admin`() {
        admin(uid = "root")
        val player = provisionUser(uid = "player")
        provisionUser(uid = "other")
        service.setRating(token = token(uid = "root"), userId = player.id, value = BigDecimal("4.0")).shouldBeRight()

        service
            .getRatings(token = token(uid = "player"), userId = player.id)
            .shouldBeRight()
            .ratings
            .single()
            .currentRating
            .toPlainString() shouldBe "4.000000"
        service
            .getRatings(token = token(uid = "root"), userId = player.id)
            .shouldBeRight()
            .ratings
            .single()
            .currentRating
            .toPlainString() shouldBe "4.000000"
        service.getHistory(token = token(uid = "player"), userId = player.id).shouldBeRight() shouldBe emptyList()
        service.getRatings(token = token(uid = "other"), userId = player.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.getHistory(token = token(uid = "other"), userId = player.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        // A caller with no provisioned account is forbidden too.
        service.getRatings(token = token(uid = "ghost"), userId = player.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `pending assessment lists unrated users for a RATER or admin only (#106)`() {
        admin(uid = "root")
        provisionUser(uid = "rater", roles = setOf(Capability.PLAYER, Capability.RATER))
        val unrated = provisionUser(uid = "unrated")
        val rated = provisionUser(uid = "rated")
        service.setRating(token = token(uid = "root"), userId = rated.id, value = BigDecimal("3.0")).shouldBeRight()

        // A RATER sees the same pending queue as an admin.
        val pending =
            service.pendingAssessment(token = token(uid = "rater"), limit = 50, offset = 0).shouldBeRight().items.map { it.userId }
        (unrated.id in pending) shouldBe true
        (rated.id in pending) shouldBe false

        service
            .pendingAssessment(token = token(uid = "unrated"), limit = 50, offset = 0)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `pending assessment enriches each entry with public code, sex and computed age`() {
        admin(uid = "root")
        val dob = LocalDate.now().minusYears(30).minusDays(1) // 30 years and a day ago → age 30
        val player =
            users.provision(
                command =
                    ProvisionUserCommand(
                        firebaseUid = "rich",
                        identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = "rich", isPrimary = true),
                        names = listOf(element = UserName(type = NameType.DISPLAY, value = "Rich")),
                        dateOfBirth = dob,
                        sex = "Female",
                        capabilities = setOf(element = Capability.PLAYER),
                    ),
            )

        val page = service.pendingAssessment(token = token(uid = "root"), limit = 50, offset = 0).shouldBeRight()
        val entry = page.items.single { it.userId == player.id }
        entry.publicCode shouldBe player.publicCode
        entry.displayName shouldBe "Rich"
        entry.sex shouldBe "Female"
        entry.dateOfBirth shouldBe dob
        entry.age shouldBe 30
        page.total shouldBe 2 // the new player plus the unrated admin
    }

    @Test
    fun `pending assessment surfaces a self-reported rating as its NTRP band`() {
        admin(uid = "root")
        val selfRated =
            users.provision(
                command =
                    ProvisionUserCommand(
                        firebaseUid = "self",
                        identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = "self", isPrimary = true),
                        names = listOf(element = UserName(type = NameType.DISPLAY, value = "Self")),
                        proposedRating = BigDecimal("3.5"),
                        capabilities = setOf(element = Capability.PLAYER),
                    ),
            )
        val plain = provisionUser(uid = "plain")

        val items = service.pendingAssessment(token = token(uid = "root"), limit = 50, offset = 0).shouldBeRight().items
        items.single { it.userId == selfRated.id }.proposedRating shouldBe "3.5"
        items.single { it.userId == plain.id }.proposedRating shouldBe null
    }

    @Test
    fun `pending assessment paginates and clamps an oversized limit`() {
        admin(uid = "root") // 1 pending (the admin has no rating)
        (1..3).forEach { provisionUser(uid = "p$it") } // 3 more pending → 4 total

        val firstTwo = service.pendingAssessment(token = token(uid = "root"), limit = 2, offset = 0).shouldBeRight()
        firstTwo.items shouldHaveSize 2
        firstTwo.total shouldBe 4

        // Limit is clamped to the max page size, so an oversized request still returns everyone.
        val clamped = service.pendingAssessment(token = token(uid = "root"), limit = 9999, offset = 0).shouldBeRight()
        clamped.items shouldHaveSize 4
    }

    @Test
    fun `setting a rating for an unknown user is rejected`() {
        admin(uid = "root")
        service
            .setRating(token = token(uid = "root"), userId = UUID.randomUUID(), value = BigDecimal("4.0"))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `pending assessment yields a null display name for a user without one`() {
        admin(uid = "root")
        // Provision a user with no DISPLAY name (only a FIRST name) so the display-name lookup
        // resolves to null on the pending-assessment list.
        val nameless =
            users.provision(
                command =
                    ProvisionUserCommand(
                        firebaseUid = "nameless",
                        identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = "nameless", isPrimary = true),
                        names = listOf(UserName(type = NameType.FIRST, value = "First")),
                        capabilities = setOf(Capability.PLAYER),
                    ),
            )

        val entry =
            service
                .pendingAssessment(token = token(uid = "root"), limit = 50, offset = 0)
                .shouldBeRight()
                .items
                .single { it.userId == nameless.id }
        entry.displayName shouldBe null
    }

    @Test
    fun `an admin may read their own ratings and history`() {
        val root = admin(uid = "root")
        service.setRating(token = token(uid = "root"), userId = root.id, value = BigDecimal("5.0")).shouldBeRight()

        service
            .getRatings(token = token(uid = "root"), userId = root.id)
            .shouldBeRight()
            .ratings
            .single()
            .currentRating
            .toPlainString() shouldBe "5.000000"
        service.getHistory(token = token(uid = "root"), userId = root.id).shouldBeRight() shouldBe emptyList()
    }

    @Test
    fun `getRatings reveals the raw value to an admin but not to the owning player (#114)`() {
        admin(uid = "root")
        val player = provisionUser(uid = "player")
        service.setRating(token = token(uid = "root"), userId = player.id, value = BigDecimal("4.3")).shouldBeRight()

        // The owner (a plain player) may read their ratings but the reveal flag stays off.
        service.getRatings(token = token(uid = "player"), userId = player.id).shouldBeRight().revealRawValue shouldBe false
        // An ADMINISTRATOR (rating manager) gets the reveal flag.
        service.getRatings(token = token(uid = "root"), userId = player.id).shouldBeRight().revealRawValue shouldBe true
    }
}
