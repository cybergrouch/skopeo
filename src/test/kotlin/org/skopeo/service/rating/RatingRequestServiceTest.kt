// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.rating

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
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
import org.skopeo.model.RatingRequestStatus
import org.skopeo.model.ServiceError
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.repository.AuditRepository
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.RatingRequestRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.user.VerifiedFirebaseToken
import org.skopeo.testsupport.PostgresTestDatabase
import java.math.BigDecimal
import java.util.UUID

class RatingRequestServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val ratings = RatingRepository()
    private val requests = RatingRequestRepository()
    private val service = RatingRequestService(requests = requests, ratings = ratings, users = users)

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun provision(
        uid: String,
        roles: Set<Capability> = setOf(element = Capability.PLAYER),
        rated: Boolean = false,
    ): User {
        val user =
            users.provision(
                command =
                    ProvisionUserCommand(
                        firebaseUid = uid,
                        identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                        names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                        capabilities = roles,
                    ),
            )
        if (rated) {
            ratings.setRating(userId = user.id, rating = BigDecimal("4.0"), level = "4.0")
        }
        return user
    }

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid)

    @Test
    fun `a rated player raises one open request (audited), and a second is a conflict`() {
        val player = provision(uid = "player", rated = true)

        val created = service.create(token = token(uid = "player"), justification = "I won a tough league").shouldBeRight()
        created.status shouldBe RatingRequestStatus.PENDING
        created.userId shouldBe player.id

        service.create(token = token(uid = "player"), justification = "again").shouldBeLeft().shouldBeInstanceOf<ServiceError.Conflict>()

        AuditRepository().list(actions = listOf(element = AuditAction.RATING_REREQUESTED), limit = 10, offset = 0).second shouldBe 1L
    }

    @Test
    fun `create needs an existing rating, a non-blank justification, and an account`() {
        provision(uid = "unrated", rated = false)
        service.create(token = token(uid = "unrated"), justification = "pls").shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()

        provision(uid = "rated", rated = true)
        service.create(token = token(uid = "rated"), justification = "  ").shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()

        service.create(token = token(uid = "ghost"), justification = "x").shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `mine returns the latest request or null`() {
        provision(uid = "player", rated = true)
        service.mine(token = token(uid = "player")).shouldBeRight().shouldBeNull()

        val created = service.create(token = token(uid = "player"), justification = "review me").shouldBeRight()
        service.mine(token = token(uid = "player")).shouldBeRight().shouldNotBeNull().id shouldBe created.id

        service.mine(token = token(uid = "ghost")).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `a rater lists, filters by status, and a non-rater is refused`() {
        provision(uid = "rater", roles = setOf(Capability.PLAYER, Capability.RATER))
        provision(uid = "player", rated = true)
        service.create(token = token(uid = "player"), justification = "look").shouldBeRight()

        val pending =
            service.list(
                token = token(uid = "rater"),
                limit = 50,
                offset = 0,
                status = RatingRequestStatus.PENDING,
            ).shouldBeRight()
        pending.items shouldHaveSize 1
        pending.items.single().requester?.displayName shouldBe "player"
        service
            .list(token = token(uid = "rater"), limit = 50, offset = 0, status = RatingRequestStatus.DENIED)
            .shouldBeRight()
            .total shouldBe 0

        service.list(
            token = token(uid = "player"),
            limit = 50,
            offset = 0,
            status = null,
        ).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `an administrator without RATER can triage, and a ghost rater is refused`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        provision(uid = "player", rated = true)
        val request = service.create(token = token(uid = "player"), justification = "x").shouldBeRight()

        // status = null lists every request; ADMINISTRATOR implicitly rates.
        service.list(token = token(uid = "admin"), limit = 50, offset = 0, status = null).shouldBeRight().total shouldBe 1
        service.approve(token = token(uid = "admin"), id = request.id, newRating = BigDecimal("4.5")).shouldBeRight()

        service.list(token = token(uid = "ghost"), limit = 50, offset = 0, status = null)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `approve applies the new rating and resolves the request, and re-approve is a conflict`() {
        val rater = provision(uid = "rater", roles = setOf(Capability.PLAYER, Capability.RATER))
        val player = provision(uid = "player", rated = true)
        val request = service.create(token = token(uid = "player"), justification = "promote me").shouldBeRight()

        val resolved =
            service.approve(token = token(uid = "rater"), id = request.id, newRating = BigDecimal("4.5")).shouldBeRight()
        resolved.status shouldBe RatingRequestStatus.APPROVED
        resolved.resolvedBy shouldBe rater.id
        ratings.findCurrentRating(userId = player.id)!!.currentRating shouldBe BigDecimal("4.500000")
        // The override is recorded in the rating history + audited.
        AuditRepository().list(actions = listOf(element = AuditAction.RATING_OVERRIDDEN), limit = 10, offset = 0).second shouldBe 1L

        service.approve(token = token(uid = "rater"), id = request.id, newRating = BigDecimal("5.0"))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Conflict>()
        // Denying an already-resolved request is likewise a conflict.
        service.deny(token = token(uid = "rater"), id = request.id, reason = "too late")
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Conflict>()
    }

    @Test
    fun `deny requires a reason, resolves the request (audited), and leaves the rating unchanged`() {
        provision(uid = "rater", roles = setOf(Capability.PLAYER, Capability.RATER))
        val player = provision(uid = "player", rated = true)
        val request = service.create(token = token(uid = "player"), justification = "review").shouldBeRight()

        service.deny(token = token(uid = "rater"), id = request.id, reason = "  ")
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()

        val denied = service.deny(token = token(uid = "rater"), id = request.id, reason = "Not enough evidence").shouldBeRight()
        denied.status shouldBe RatingRequestStatus.DENIED
        denied.reason shouldBe "Not enough evidence"
        ratings.findCurrentRating(userId = player.id)!!.currentRating shouldBe BigDecimal("4.000000") // unchanged
        AuditRepository().list(actions = listOf(element = AuditAction.RATING_REQUEST_DENIED), limit = 10, offset = 0).second shouldBe 1L
    }

    @Test
    fun `approve and deny reject an unknown id and a non-rater`() {
        provision(uid = "rater", roles = setOf(Capability.PLAYER, Capability.RATER))
        provision(uid = "player", rated = true)
        val request = service.create(token = token(uid = "player"), justification = "x").shouldBeRight()

        service.approve(token = token(uid = "rater"), id = UUID.randomUUID(), newRating = BigDecimal("4.5"))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
        service.deny(token = token(uid = "rater"), id = UUID.randomUUID(), reason = "no")
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
        service.approve(token = token(uid = "player"), id = request.id, newRating = BigDecimal("4.5"))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.deny(token = token(uid = "player"), id = request.id, reason = "no")
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }
}
