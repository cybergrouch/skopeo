package org.skopeo.service.capability

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
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
import org.skopeo.repository.CapabilityRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.ConflictException
import org.skopeo.service.user.ForbiddenException
import org.skopeo.service.user.UserNotFoundException
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

    private fun admin(uid: String = "root"): User = provisionUser(uid, roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid)

    @Test
    fun `only an administrator may use the API`() {
        admin("root")
        val player = provisionUser("player")

        shouldThrow<ForbiddenException> { service.list(token = token("player"), userId = player.id) }
        shouldThrow<ForbiddenException> { service.grant(token = token("player"), userId = player.id, capabilityName = "HOST") }
        shouldThrow<ForbiddenException> { service.grant(token = token("ghost"), userId = player.id, capabilityName = "HOST") }
    }

    @Test
    fun `grant is idempotent and records the granting admin`() {
        val root = admin("root")
        val player = provisionUser("player")

        val first = service.grant(token = token("root"), userId = player.id, capabilityName = "HOST")
        first.created.shouldBeTrue()
        first.grant.capability shouldBe Capability.HOST
        first.grant.grantedBy shouldBe root.id

        val again = service.grant(token = token("root"), userId = player.id, capabilityName = "HOST")
        again.created.shouldBeFalse()
    }

    @Test
    fun `an administrator can revoke a non-baseline role`() {
        admin("root")
        val player = provisionUser("player")
        service.grant(token = token("root"), userId = player.id, capabilityName = "HOST")

        service.revoke(token = token("root"), userId = player.id, capabilityName = "HOST")

        service.list(token = token("root"), userId = player.id).none { it.capability == Capability.HOST && it.isActive }.shouldBeTrue()
    }

    @Test
    fun `the PLAYER role cannot be revoked`() {
        admin("root")
        val player = provisionUser("player")

        shouldThrow<ConflictException> {
            service.revoke(token = token("root"), userId = player.id, capabilityName = "PLAYER")
        }
    }

    @Test
    fun `the last administrator cannot be revoked, but one of several can`() {
        val root = admin("root")
        val second = provisionUser("second")
        service.grant(token = token("root"), userId = second.id, capabilityName = "ADMINISTRATOR")

        // Two admins now: root may revoke second's admin.
        service.revoke(token = token("root"), userId = second.id, capabilityName = "ADMINISTRATOR")

        // root is the last admin: revoking it (their own) is blocked as the last-admin case.
        shouldThrow<ConflictException> {
            service.revoke(token = token("root"), userId = root.id, capabilityName = "ADMINISTRATOR")
        }
    }

    @Test
    fun `an administrator cannot revoke their own admin while others remain`() {
        val root = admin("root")
        provisionUser("second", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))

        shouldThrow<ForbiddenException> {
            service.revoke(token = token("root"), userId = root.id, capabilityName = "ADMINISTRATOR")
        }
    }

    @Test
    fun `revoking a capability not held is not found`() {
        admin("root")
        val player = provisionUser("player")

        shouldThrow<CapabilityNotFoundException> {
            service.revoke(token = token("root"), userId = player.id, capabilityName = "HOST")
        }
    }

    @Test
    fun `unknown user and invalid capability are rejected`() {
        admin("root")

        shouldThrow<UserNotFoundException> {
            service.list(token = token("root"), userId = UUID.randomUUID())
        }
        val player = provisionUser("player")
        shouldThrow<IllegalArgumentException> {
            service.grant(token = token("root"), userId = player.id, capabilityName = "WIZARD")
        }
    }
}
