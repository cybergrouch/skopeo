package org.skopeo.service.name

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.dto.name.NameCreateRequest
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
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

    private fun provisionUser(
        uid: String,
        capabilities: Set<Capability> = setOf(Capability.PLAYER),
    ): User =
        users.provision(
            ProvisionUserCommand(
                firebaseUid = uid,
                identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                names = emptyList(),
                capabilities = capabilities,
            ),
        )

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid)

    private fun nickname(value: String) = NameCreateRequest(type = "NICKNAME", value = value, isPrimary = false)

    @Test
    fun `the first name is auto-primary, later non-primary names are not`() {
        val owner = provisionUser("owner")

        val first = service.create(token = token("owner"), userId = owner.id, request = nickname("JB"))
        first.isPrimary.shouldBeTrue()

        val second = service.create(token = token("owner"), userId = owner.id, request = nickname("Boy"))
        second.isPrimary.shouldBeFalse()
    }

    @Test
    fun `an explicit primary demotes the previous one`() {
        val owner = provisionUser("owner")
        val first = service.create(token = token("owner"), userId = owner.id, request = nickname("JB"))

        val second =
            service.create(
                token = token("owner"),
                userId = owner.id,
                request = NameCreateRequest(type = "PREFERRED", value = "Johnny", isPrimary = true),
            )

        second.isPrimary.shouldBeTrue()
        service.get(token = token("owner"), userId = owner.id, nameId = first.id).isPrimary.shouldBeFalse()
    }

    @Test
    fun `multiple names of the same type are allowed`() {
        val owner = provisionUser("owner")
        service.create(token = token("owner"), userId = owner.id, request = nickname("JB"))
        service.create(token = token("owner"), userId = owner.id, request = nickname("Boy"))

        service.list(token = token("owner"), userId = owner.id).size shouldBe 2
    }

    @Test
    fun `owner can disable a name`() {
        val owner = provisionUser("owner")
        val name = service.create(token = token("owner"), userId = owner.id, request = nickname("JB"))

        val disabled = service.setActive(token = token("owner"), userId = owner.id, nameId = name.id, active = false)
        disabled.isActive.shouldBeFalse()
    }

    @Test
    fun `re-enabling a former primary into an occupied slot is a conflict`() {
        val owner = provisionUser("owner")
        val first =
            service.create(
                token = token("owner"),
                userId = owner.id,
                request = NameCreateRequest(type = "FIRST", value = "Juan", isPrimary = true),
            )
        service.setActive(token = token("owner"), userId = owner.id, nameId = first.id, active = false)
        service.create(
            token = token("owner"),
            userId = owner.id,
            request = NameCreateRequest(type = "PREFERRED", value = "Johnny", isPrimary = true),
        )

        shouldThrow<NameConflictException> {
            service.setActive(token = token("owner"), userId = owner.id, nameId = first.id, active = true)
        }
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
