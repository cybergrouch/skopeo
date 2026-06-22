package org.skopeo.service.name

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.dto.name.NameCreateRequest
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.repository.NameRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.user.ForbiddenException
import org.skopeo.service.user.VerifiedFirebaseToken
import org.skopeo.testsupport.PostgresTestDatabase
import java.util.UUID

class NameServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val nameRepo = NameRepository()
    private val service = NameService(names = nameRepo, users = users)

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    /** Provision a user with one DISPLAY name (as the API would). */
    private fun provisionUser(
        uid: String,
        capabilities: Set<Capability> = setOf(Capability.PLAYER),
    ): User =
        users.provision(
            ProvisionUserCommand(
                firebaseUid = uid,
                identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                names = listOf(UserName(type = NameType.DISPLAY, value = "Display $uid")),
                capabilities = capabilities,
            ),
        )

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid)

    private fun nickname(value: String) = NameCreateRequest(type = "NICKNAME", value = value)

    private fun displayNameOf(
        uid: String,
        userId: UUID,
    ) = service.list(token = token(uid), userId = userId).single { it.type == NameType.DISPLAY && it.isActive }

    @Test
    fun `multiple names of the same type are allowed`() {
        val owner = provisionUser("owner")
        service.create(token = token("owner"), userId = owner.id, request = nickname("JB"))
        service.create(token = token("owner"), userId = owner.id, request = nickname("Boy"))

        service.list(token = token("owner"), userId = owner.id).count { it.type == NameType.NICKNAME } shouldBe 2
    }

    @Test
    fun `posting a new display name replaces the previous one`() {
        val owner = provisionUser("owner")
        val original = displayNameOf("owner", owner.id)

        val replacement =
            service.create(
                token = token("owner"),
                userId = owner.id,
                request = NameCreateRequest(type = "DISPLAY", value = "Johnny"),
            )

        replacement.value shouldBe "Johnny"
        // exactly one active display, and the old one is retained as disabled history
        displayNameOf("owner", owner.id).id shouldBe replacement.id
        service.get(token = token("owner"), userId = owner.id, nameId = original.id).isActive.shouldBeFalse()
    }

    @Test
    fun `the display name cannot be disabled`() {
        val owner = provisionUser("owner")
        val display = displayNameOf("owner", owner.id)

        shouldThrow<IllegalArgumentException> {
            service.setActive(token = token("owner"), userId = owner.id, nameId = display.id, active = false)
        }
    }

    @Test
    fun `re-enabling a former display name conflicts with the current one`() {
        val owner = provisionUser("owner")
        val original = displayNameOf("owner", owner.id)
        service.create(token = token("owner"), userId = owner.id, request = NameCreateRequest(type = "DISPLAY", value = "Johnny"))

        // `original` is now disabled history; re-enabling it collides with the active display.
        shouldThrow<NameConflictException> {
            service.setActive(token = token("owner"), userId = owner.id, nameId = original.id, active = true)
        }
    }

    @Test
    fun `owner can disable a non-display name`() {
        val owner = provisionUser("owner")
        val name = service.create(token = token("owner"), userId = owner.id, request = nickname("JB"))

        service.setActive(token = token("owner"), userId = owner.id, nameId = name.id, active = false).isActive.shouldBeFalse()
    }

    @Test
    fun `access is restricted to the owner or an administrator`() {
        val owner = provisionUser("owner")
        provisionUser("intruder")
        provisionUser("root", capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val name = service.create(token = token("owner"), userId = owner.id, request = nickname("JB"))

        shouldThrow<ForbiddenException> { service.list(token = token("intruder"), userId = owner.id) }
        shouldThrow<ForbiddenException> { service.list(token = token("ghost"), userId = owner.id) }
        service.get(token = token("root"), userId = owner.id, nameId = name.id).id shouldBe name.id
    }

    @Test
    fun `unknown or mismatched names are not found`() {
        val owner = provisionUser("owner")
        val other = provisionUser("other")
        val name = service.create(token = token("other"), userId = other.id, request = nickname("JB"))

        shouldThrow<NameNotFoundException> {
            service.get(token = token("owner"), userId = owner.id, nameId = UUID.randomUUID())
        }
        shouldThrow<NameNotFoundException> {
            service.get(token = token("owner"), userId = owner.id, nameId = name.id)
        }
    }

    @Test
    fun `invalid name type is rejected`() {
        val owner = provisionUser("owner")

        shouldThrow<IllegalArgumentException> {
            service.create(token = token("owner"), userId = owner.id, request = NameCreateRequest(type = "ALIAS", value = "x"))
        }
    }
}
