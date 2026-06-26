// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.invite

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.InviteStatus
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.repository.InviteRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.ResourceNotFoundException
import org.skopeo.service.user.ForbiddenException
import org.skopeo.service.user.VerifiedFirebaseToken
import org.skopeo.testsupport.PostgresTestDatabase
import java.util.UUID

class InviteServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val invites = InviteRepository()
    private val service = InviteService(invites = invites, users = users)

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun provision(
        uid: String,
        roles: Set<Capability>,
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

    @Test
    fun `an admin creates an invite (normalized, pending, attributed) and re-inviting rotates it`() {
        val admin = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))

        val invite = service.create(token = token(uid = "admin"), email = "  New@Example.com ")
        invite.email shouldBe "new@example.com"
        invite.status shouldBe InviteStatus.PENDING
        invite.invitedBy shouldBe admin.id

        service.create(token = token(uid = "admin"), email = "new@example.com") // resend → rotate
        service.list(token = token(uid = "admin"), limit = 50, offset = 0).items shouldHaveSize 1
    }

    @Test
    fun `a non-admin cannot create, list, or revoke invites`() {
        provision(uid = "player", roles = setOf(Capability.PLAYER))
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val invite = service.create(token = token(uid = "admin"), email = "x@example.com")

        shouldThrow<ForbiddenException> { service.create(token = token(uid = "player"), email = "y@example.com") }
        shouldThrow<ForbiddenException> { service.list(token = token(uid = "player"), limit = 50, offset = 0) }
        shouldThrow<ForbiddenException> { service.revoke(token = token(uid = "player"), id = invite.id) }
    }

    @Test
    fun `revoke removes the invite from the open set, and an unknown id is a not-found`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val invite = service.create(token = token(uid = "admin"), email = "z@example.com")

        service.revoke(token = token(uid = "admin"), id = invite.id)
        invites.findOpenByEmail(email = "z@example.com", asOf = java.time.LocalDateTime.now()) shouldBe null

        shouldThrow<ResourceNotFoundException> { service.revoke(token = token(uid = "admin"), id = UUID.randomUUID()) }
    }

    @Test
    fun `an invalid email is rejected`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        shouldThrow<IllegalArgumentException> { service.create(token = token(uid = "admin"), email = "not-an-email") }
    }
}
