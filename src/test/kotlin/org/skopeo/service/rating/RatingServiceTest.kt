// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.rating

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.user.ForbiddenException
import org.skopeo.service.user.VerifiedFirebaseToken
import org.skopeo.testsupport.PostgresTestDatabase
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

        val rating = service.setRating(token = token(uid = "root"), userId = player.id, value = "4.3", confidence = null)

        rating.currentRating.toPlainString() shouldBe "4.300000" // stored as NUMERIC(10,6)
        rating.currentLevel shouldBe "4.0" // NTRP rounds down to the 0.5 level
        rating.confidence.toPlainString() shouldBe "0.50" // default

        // An explicit confidence is honored.
        val adjusted = service.setRating(token = token(uid = "root"), userId = player.id, value = "4.5", confidence = "0.70")
        adjusted.confidence.toPlainString() shouldBe "0.70"
    }

    @Test
    fun `only an admin may set a rating`() {
        admin(uid = "root")
        val player = provisionUser(uid = "player")

        shouldThrow<ForbiddenException> {
            service.setRating(
                token = token(uid = "player"),
                userId = player.id,
                value = "4.0",
                confidence = null,
            )
        }
        shouldThrow<ForbiddenException> {
            service.setRating(
                token = token(uid = "ghost"),
                userId = player.id,
                value = "4.0",
                confidence = null,
            )
        }
    }

    @Test
    fun `invalid rating value or confidence is rejected`() {
        admin(uid = "root")
        val player = provisionUser(uid = "player")

        shouldThrow<IllegalArgumentException> {
            service.setRating(token = token(uid = "root"), userId = player.id, value = "9.0", confidence = null)
        }
        shouldThrow<IllegalArgumentException> {
            service.setRating(token = token(uid = "root"), userId = player.id, value = "4.0", confidence = "1.5")
        }
    }

    @Test
    fun `reading ratings and history is self-or-admin`() {
        admin(uid = "root")
        val player = provisionUser(uid = "player")
        provisionUser(uid = "other")
        service.setRating(token = token(uid = "root"), userId = player.id, value = "4.0", confidence = null)

        service.getRatings(token = token(uid = "player"), userId = player.id).single().currentRating.toPlainString() shouldBe "4.000000"
        service.getRatings(token = token(uid = "root"), userId = player.id).single().currentRating.toPlainString() shouldBe "4.000000"
        service.getHistory(token = token(uid = "player"), userId = player.id) shouldBe emptyList()
        shouldThrow<ForbiddenException> { service.getRatings(token = token(uid = "other"), userId = player.id) }
        shouldThrow<ForbiddenException> { service.getHistory(token = token(uid = "other"), userId = player.id) }
        // A caller with no provisioned account is forbidden too.
        shouldThrow<ForbiddenException> { service.getRatings(token = token(uid = "ghost"), userId = player.id) }
    }

    @Test
    fun `pending assessment lists unrated users for admins only`() {
        admin(uid = "root")
        val unrated = provisionUser(uid = "unrated")
        val rated = provisionUser(uid = "rated")
        service.setRating(token = token(uid = "root"), userId = rated.id, value = "3.0", confidence = null)

        val pending = service.pendingAssessment(token = token(uid = "root")).map { it.userId }
        (unrated.id in pending) shouldBe true
        (rated.id in pending) shouldBe false

        shouldThrow<ForbiddenException> { service.pendingAssessment(token = token(uid = "unrated")) }
    }

    @Test
    fun `setting a rating for an unknown user is rejected`() {
        admin(uid = "root")
        shouldThrow<org.skopeo.service.user.UserNotFoundException> {
            service.setRating(token = token(uid = "root"), userId = UUID.randomUUID(), value = "4.0", confidence = null)
        }
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

        val entry = service.pendingAssessment(token = token(uid = "root")).single { it.userId == nameless.id }
        entry.displayName shouldBe null
    }

    @Test
    fun `a negative confidence is rejected`() {
        admin(uid = "root")
        val player = provisionUser(uid = "player")

        shouldThrow<IllegalArgumentException> {
            service.setRating(token = token(uid = "root"), userId = player.id, value = "4.0", confidence = "-0.1")
        }
    }

    @Test
    fun `an admin may read their own ratings and history`() {
        val root = admin(uid = "root")
        service.setRating(token = token(uid = "root"), userId = root.id, value = "5.0", confidence = null)

        service.getRatings(token = token(uid = "root"), userId = root.id).single().currentRating.toPlainString() shouldBe "5.000000"
        service.getHistory(token = token(uid = "root"), userId = root.id) shouldBe emptyList()
    }
}
