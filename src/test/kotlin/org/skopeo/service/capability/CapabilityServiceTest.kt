// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.capability

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
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
import org.skopeo.repository.CapabilityRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.user.VerifiedFirebaseToken
import org.skopeo.testsupport.PostgresTestDatabase
import java.util.UUID

class CapabilityServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val capabilities = CapabilityRepository()
    private val service = CapabilityService(capabilities = capabilities, users = users)

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

    private fun admin(uid: String = "root"): User = provisionUser(uid = uid, roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid)

    @Test
    fun `only an administrator may use the API`() {
        admin(uid = "root")
        val player = provisionUser(uid = "player")

        service.list(token = token(uid = "player"), userId = player.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service
            .grant(token = token(uid = "player"), userId = player.id, capability = Capability.HOST)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
        service
            .grant(token = token(uid = "ghost"), userId = player.id, capability = Capability.HOST)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `grant is idempotent and records the granting admin`() {
        val root = admin(uid = "root")
        val player = provisionUser(uid = "player")

        val first = service.grant(token = token(uid = "root"), userId = player.id, capability = Capability.HOST).shouldBeRight()
        first.created.shouldBeTrue()
        first.grant.capability shouldBe Capability.HOST
        first.grant.grantedBy shouldBe root.id

        val again = service.grant(token = token(uid = "root"), userId = player.id, capability = Capability.HOST).shouldBeRight()
        again.created.shouldBeFalse()
    }

    @Test
    fun `granting and revoking write audit-log entries (#100)`() {
        val root = admin(uid = "root")
        val player = provisionUser(uid = "player")
        service.grant(token = token(uid = "root"), userId = player.id, capability = Capability.HOST).shouldBeRight()
        service.revoke(token = token(uid = "root"), userId = player.id, capability = Capability.HOST).shouldBeRight()

        val audit = AuditRepository()
        audit.list(actions = listOf(element = AuditAction.CAPABILITY_GRANTED), limit = 10, offset = 0).let { (items, total) ->
            total shouldBe 1L
            items.single().let {
                it.actorUserId shouldBe root.id
                it.entityId shouldBe player.id
                it.summary shouldBe "Granted HOST role"
                it.details["capability"] shouldBe "HOST"
            }
        }
        audit.list(actions = listOf(element = AuditAction.CAPABILITY_REVOKED), limit = 10, offset = 0).second shouldBe 1L
    }

    @Test
    fun `an administrator can revoke a non-baseline role`() {
        admin(uid = "root")
        val player = provisionUser(uid = "player")
        service.grant(token = token(uid = "root"), userId = player.id, capability = Capability.HOST).shouldBeRight()

        service.revoke(token = token(uid = "root"), userId = player.id, capability = Capability.HOST).shouldBeRight()

        service
            .list(token = token(uid = "root"), userId = player.id)
            .shouldBeRight()
            .none { it.capability == Capability.HOST && it.isActive }
            .shouldBeTrue()
    }

    @Test
    fun `the PLAYER role cannot be revoked`() {
        admin(uid = "root")
        val player = provisionUser(uid = "player")

        service
            .revoke(token = token(uid = "root"), userId = player.id, capability = Capability.PLAYER)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Conflict>()
    }

    @Test
    fun `the last administrator cannot be revoked, but one of several can`() {
        val root = admin(uid = "root")
        val second = provisionUser(uid = "second")
        service.grant(token = token(uid = "root"), userId = second.id, capability = Capability.ADMINISTRATOR).shouldBeRight()

        // Two admins now: root may revoke second's admin.
        service.revoke(token = token(uid = "root"), userId = second.id, capability = Capability.ADMINISTRATOR).shouldBeRight()

        // root is the last admin: revoking it (their own) is blocked as the last-admin case.
        service
            .revoke(token = token(uid = "root"), userId = root.id, capability = Capability.ADMINISTRATOR)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Conflict>()
    }

    @Test
    fun `an administrator cannot revoke their own admin while others remain`() {
        val root = admin(uid = "root")
        provisionUser(uid = "second", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))

        service
            .revoke(token = token(uid = "root"), userId = root.id, capability = Capability.ADMINISTRATOR)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `revoking a capability not held is not found`() {
        admin(uid = "root")
        val player = provisionUser(uid = "player")

        service
            .revoke(token = token(uid = "root"), userId = player.id, capability = Capability.HOST)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `an unknown user is rejected`() {
        admin(uid = "root")

        service
            .list(token = token(uid = "root"), userId = UUID.randomUUID())
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }
}
