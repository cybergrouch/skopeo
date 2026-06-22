package org.skopeo.repository

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.testsupport.PostgresTestDatabase
import java.time.LocalDateTime
import java.util.UUID

class CapabilityRepositoryTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val capabilities = CapabilityRepository()

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun newUser(uid: String): UUID =
        users.provision(
            ProvisionUserCommand(
                firebaseUid = uid,
                identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                names = listOf(UserName(type = NameType.DISPLAY, value = uid)),
                capabilities = emptySet(),
            ),
        ).id

    @Test
    fun `grant inserts an active grant with audit`() {
        val userId = newUser("u1")
        val admin = newUser("admin")

        val grant = capabilities.grant(userId = userId, capability = Capability.HOST, grantedBy = admin)

        grant.capability shouldBe Capability.HOST
        grant.isActive.shouldBeTrue()
        grant.grantedBy shouldBe admin
        grant.grantedAt.shouldNotBeNull()
        capabilities.findActive(userId, Capability.HOST).shouldNotBeNull()
    }

    @Test
    fun `findActive returns null when not held`() {
        val userId = newUser("u2")
        capabilities.findActive(userId, Capability.HOST).shouldBeNull()
    }

    @Test
    fun `revoke disables the active grant with audit, and re-granting adds a fresh row`() {
        val userId = newUser("u3")
        val admin = newUser("admin")
        capabilities.grant(userId = userId, capability = Capability.HOST, grantedBy = admin)

        val revoked = capabilities.revoke(userId = userId, capability = Capability.HOST, revokedBy = admin, revokedAt = LocalDateTime.now())
        revoked.shouldNotBeNull()
        revoked.isActive.shouldBeFalse()
        revoked.revokedBy shouldBe admin
        revoked.revokedAt.shouldNotBeNull()
        capabilities.findActive(userId, Capability.HOST).shouldBeNull()

        capabilities.grant(userId = userId, capability = Capability.HOST, grantedBy = admin)
        capabilities.findActive(userId, Capability.HOST).shouldNotBeNull()
        // one revoked + one active row in history
        capabilities.listByUser(userId).count { it.capability == Capability.HOST } shouldBe 2
    }

    @Test
    fun `revoke reports absence`() {
        val userId = newUser("u4")
        capabilities.revoke(userId = userId, capability = Capability.HOST, revokedBy = userId, revokedAt = LocalDateTime.now())
            .shouldBeNull()
    }

    @Test
    fun `two active grants of the same capability are rejected`() {
        val userId = newUser("u5")
        val admin = newUser("admin")
        capabilities.grant(userId = userId, capability = Capability.HOST, grantedBy = admin)

        shouldThrow<ExposedSQLException> {
            capabilities.grant(userId = userId, capability = Capability.HOST, grantedBy = admin)
        }
    }

    @Test
    fun `countActiveAdministrators counts active admin grants only`() {
        val admin = newUser("admin")
        val other = newUser("other")
        capabilities.grant(userId = admin, capability = Capability.ADMINISTRATOR, grantedBy = admin)
        capabilities.grant(userId = other, capability = Capability.ADMINISTRATOR, grantedBy = admin)
        capabilities.countActiveAdministrators() shouldBe 2L

        capabilities.revoke(userId = other, capability = Capability.ADMINISTRATOR, revokedBy = admin, revokedAt = LocalDateTime.now())
        capabilities.countActiveAdministrators() shouldBe 1L
    }
}
