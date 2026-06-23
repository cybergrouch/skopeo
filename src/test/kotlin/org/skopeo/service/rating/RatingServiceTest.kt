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

    private fun admin(uid: String = "root") = provisionUser(uid, roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid)

    @Test
    fun `admin sets a rating and the published level is derived`() {
        admin("root")
        val player = provisionUser("player")

        val rating = service.setRating(token("root"), player.id, "4.3", null)

        rating.currentRating.toPlainString() shouldBe "4.300000" // stored as NUMERIC(10,6)
        rating.currentLevel shouldBe "4.0" // NTRP rounds down to the 0.5 level
        rating.confidence.toPlainString() shouldBe "0.50" // default

        // An explicit confidence is honored.
        val adjusted = service.setRating(token("root"), player.id, "4.5", "0.70")
        adjusted.confidence.toPlainString() shouldBe "0.70"
    }

    @Test
    fun `only an admin may set a rating`() {
        admin("root")
        val player = provisionUser("player")

        shouldThrow<ForbiddenException> { service.setRating(token("player"), player.id, "4.0", null) }
        shouldThrow<ForbiddenException> { service.setRating(token("ghost"), player.id, "4.0", null) }
    }

    @Test
    fun `invalid rating value or confidence is rejected`() {
        admin("root")
        val player = provisionUser("player")

        shouldThrow<IllegalArgumentException> { service.setRating(token("root"), player.id, "9.0", null) }
        shouldThrow<IllegalArgumentException> { service.setRating(token("root"), player.id, "4.0", "1.5") }
    }

    @Test
    fun `reading ratings and history is self-or-admin`() {
        admin("root")
        val player = provisionUser("player")
        provisionUser("other")
        service.setRating(token("root"), player.id, "4.0", null)

        service.getRatings(token("player"), player.id).single().currentRating.toPlainString() shouldBe "4.000000"
        service.getRatings(token("root"), player.id).single().currentRating.toPlainString() shouldBe "4.000000"
        service.getHistory(token("player"), player.id) shouldBe emptyList()
        shouldThrow<ForbiddenException> { service.getRatings(token("other"), player.id) }
        shouldThrow<ForbiddenException> { service.getHistory(token("other"), player.id) }
        // A caller with no provisioned account is forbidden too.
        shouldThrow<ForbiddenException> { service.getRatings(token("ghost"), player.id) }
    }

    @Test
    fun `pending assessment lists unrated users for admins only`() {
        admin("root")
        val unrated = provisionUser("unrated")
        val rated = provisionUser("rated")
        service.setRating(token("root"), rated.id, "3.0", null)

        val pending = service.pendingAssessment(token("root")).map { it.userId }
        (unrated.id in pending) shouldBe true
        (rated.id in pending) shouldBe false

        shouldThrow<ForbiddenException> { service.pendingAssessment(token("unrated")) }
    }

    @Test
    fun `setting a rating for an unknown user is rejected`() {
        admin("root")
        shouldThrow<org.skopeo.service.user.UserNotFoundException> {
            service.setRating(token("root"), UUID.randomUUID(), "4.0", null)
        }
    }
}
