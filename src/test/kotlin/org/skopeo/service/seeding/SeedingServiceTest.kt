// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.seeding

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.ServiceError
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.user.VerifiedFirebaseToken
import org.skopeo.testsupport.PostgresTestDatabase
import java.math.BigDecimal
import java.util.UUID

class SeedingServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val ratings = RatingRepository()
    private val lists = PlayerListService()
    private val service = SeedingService()

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
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                    capabilities = roles,
                ),
        )

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid)

    private fun rate(
        user: User,
        value: String,
        confidence: String = "0.5",
    ) = ratings.setRating(
        userId = user.id,
        rating = BigDecimal(value),
        level = value.toBigDecimal().let { "${it.toInt()}.${if (it.toDouble() % 1.0 >= 0.5) 5 else 0}" },
        confidence = BigDecimal(confidence),
    )

    /** A HOST with a list containing [members]; returns the list id. */
    private fun listWith(members: List<User>): UUID {
        val list = lists.create(token = token(uid = "host"), name = "Seeded").shouldBeRight()
        members.forEach { lists.addMember(token = token(uid = "host"), listId = list.id, userId = it.id).shouldBeRight() }
        return list.id
    }

    @Test
    fun `generate sorts by rating descending and seeds the top half (round up)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val a = provision(uid = "alice").also { rate(user = it, value = "4.5") }
        val b = provision(uid = "bob").also { rate(user = it, value = "3.5") }
        val c = provision(uid = "carol").also { rate(user = it, value = "4.0") }
        val listId = listWith(members = listOf(a, b, c))

        val seeding = service.generate(token = token(uid = "host"), listId = listId).shouldBeRight()
        seeding.entries shouldHaveSize 3
        // Order is rating-desc: alice (4.5), carol (4.0), bob (3.5).
        seeding.entries.map { it.userId } shouldBe listOf(a.id, c.id, b.id)
        seeding.entries.map { it.position } shouldBe listOf(1, 2, 3)
        // Top ⌈3/2⌉ = 2 are seeded; the rest blank.
        seeding.entries.map { it.seed } shouldBe listOf(1, 2, null)
        // The exact rating is captured (stored at scale 6, so compare numerically).
        seeding.entries.first().rating.toBigDecimal().compareTo(other = BigDecimal("4.5")) shouldBe 0
    }

    @Test
    fun `rating ties break by confidence, then matches, then name`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val low = provision(uid = "zoe").also { rate(user = it, value = "4.0", confidence = "0.3") }
        val high = provision(uid = "amy").also { rate(user = it, value = "4.0", confidence = "0.9") }
        val listId = listWith(members = listOf(low, high))

        val seeding = service.generate(token = token(uid = "host"), listId = listId).shouldBeRight()
        // Same rating → higher confidence first.
        seeding.entries.map { it.userId } shouldBe listOf(high.id, low.id)
    }

    @Test
    fun `only members with a rating are seeded`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val rated = provision(uid = "rated").also { rate(user = it, value = "4.0") }
        val unrated = provision(uid = "unrated")
        val listId = listWith(members = listOf(rated, unrated))

        val seeding = service.generate(token = token(uid = "host"), listId = listId).shouldBeRight()
        seeding.entries.map { it.userId } shouldBe listOf(element = rated.id)
    }

    @Test
    fun `regenerating overwrites the previous seeding`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val a = provision(uid = "alice").also { rate(user = it, value = "4.0") }
        val b = provision(uid = "bob").also { rate(user = it, value = "3.5") }
        val listId = listWith(members = listOf(a, b))

        service.generate(token = token(uid = "host"), listId = listId).shouldBeRight()
        // Bump bob above alice, then regenerate.
        rate(user = b, value = "5.0")
        val regenerated = service.generate(token = token(uid = "host"), listId = listId).shouldBeRight()
        regenerated.entries.map { it.userId } shouldBe listOf(b.id, a.id)
        // Still a single current seeding.
        service.get(token = token(uid = "host"), listId = listId).shouldBeRight().entries shouldHaveSize 2
    }

    @Test
    fun `get returns not-found before a seeding is generated`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val listId = listWith(members = emptyList())
        service.get(token = token(uid = "host"), listId = listId).shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `generating someone else's list is forbidden`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "intruder", roles = setOf(Capability.PLAYER, Capability.HOST))
        val listId = listWith(members = emptyList())

        service.generate(token = token(uid = "intruder"), listId = listId)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }
}
