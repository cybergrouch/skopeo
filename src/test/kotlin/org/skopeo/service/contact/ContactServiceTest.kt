package org.skopeo.service.contact

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.dto.contact.ContactCreateRequest
import org.skopeo.dto.contact.ContactUpdateRequest
import org.skopeo.dto.contact.VerificationRequest
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.model.VerificationMethod
import org.skopeo.model.VerificationStatus
import org.skopeo.repository.ContactRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.user.ForbiddenException
import org.skopeo.service.user.VerifiedFirebaseToken
import org.skopeo.testsupport.PostgresTestDatabase
import java.util.UUID

class ContactServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val contacts = ContactRepository()
    private val service = ContactService(contacts = contacts, users = users)

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
                names = listOf(UserName(type = NameType.FIRST, value = "N", isPrimary = true)),
                capabilities = capabilities,
            ),
        )

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid)

    private fun email(value: String = "a@example.com") = ContactCreateRequest(type = "EMAIL", value = value, isPrimary = true)

    private fun verify(
        adminUid: String,
        userId: UUID,
        contactId: UUID,
        status: String = "VERIFIED",
    ) = service.setVerification(
        token = token(adminUid),
        userId = userId,
        contactId = contactId,
        request = VerificationRequest(status = status),
    )

    @Test
    fun `owner can create, list, get, update and delete own contacts`() {
        val owner = provisionUser("owner")

        val created = service.create(token = token("owner"), userId = owner.id, request = email())
        service.list(token = token("owner"), userId = owner.id).single().id shouldBe created.id
        service.get(token = token("owner"), userId = owner.id, contactId = created.id).id shouldBe created.id

        val updated =
            service.update(
                token = token("owner"),
                userId = owner.id,
                contactId = created.id,
                request = ContactUpdateRequest(value = "b@example.com", isPrimary = true),
            )
        updated.value shouldBe "b@example.com"

        service.delete(token = token("owner"), userId = owner.id, contactId = created.id)
        shouldThrow<ContactNotFoundException> { service.get(token = token("owner"), userId = owner.id, contactId = created.id) }
    }

    @Test
    fun `a non-owner non-admin cannot access another user's contacts`() {
        val owner = provisionUser("owner")
        provisionUser("intruder")
        service.create(token = token("owner"), userId = owner.id, request = email())

        shouldThrow<ForbiddenException> { service.list(token = token("intruder"), userId = owner.id) }
    }

    @Test
    fun `owner cannot self-verify, but an ADMINISTRATOR can`() {
        val owner = provisionUser("owner")
        val admin = provisionUser("root", capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val contact = service.create(token = token("owner"), userId = owner.id, request = email())

        shouldThrow<ForbiddenException> {
            service.setVerification(
                token = token("owner"),
                userId = owner.id,
                contactId = contact.id,
                request = VerificationRequest(status = "VERIFIED"),
            )
        }

        val verified =
            service.setVerification(
                token = token("root"),
                userId = owner.id,
                contactId = contact.id,
                request = VerificationRequest(status = "VERIFIED"),
            )
        verified.status shouldBe VerificationStatus.VERIFIED
        verified.method shouldBe VerificationMethod.ADMIN_OVERRIDE
        verified.verifiedBy shouldBe admin.id
    }

    @Test
    fun `an ADMINISTRATOR can read another user's contacts`() {
        val owner = provisionUser("owner")
        provisionUser("root", capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val created = service.create(token = token("owner"), userId = owner.id, request = email())

        service.list(token = token("root"), userId = owner.id).single().id shouldBe created.id
        service.get(token = token("root"), userId = owner.id, contactId = created.id).id shouldBe created.id
    }

    @Test
    fun `a caller without a provisioned account is forbidden`() {
        val owner = provisionUser("owner")
        service.create(token = token("owner"), userId = owner.id, request = email())

        shouldThrow<ForbiddenException> { service.list(token = token("ghost"), userId = owner.id) }
    }

    @Test
    fun `a caller without a provisioned account cannot verify`() {
        val owner = provisionUser("owner")
        val contact = service.create(token = token("owner"), userId = owner.id, request = email())

        shouldThrow<ForbiddenException> {
            verify(adminUid = "ghost", userId = owner.id, contactId = contact.id)
        }
    }

    @Test
    fun `an ADMINISTRATOR can revoke a verification back to PENDING`() {
        val owner = provisionUser("owner")
        provisionUser("root", capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val contact = service.create(token = token("owner"), userId = owner.id, request = email())
        verify(adminUid = "root", userId = owner.id, contactId = contact.id)

        val revoked = verify(adminUid = "root", userId = owner.id, contactId = contact.id, status = "PENDING")

        revoked.status shouldBe VerificationStatus.PENDING
        revoked.method.shouldBe(null)
    }

    @Test
    fun `editing a verified contact resets it to PENDING`() {
        val owner = provisionUser("owner")
        provisionUser("root", capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val contact = service.create(token = token("owner"), userId = owner.id, request = email())
        service.setVerification(
            token = token("root"),
            userId = owner.id,
            contactId = contact.id,
            request = VerificationRequest(status = "VERIFIED"),
        )

        val edited =
            service.update(
                token = token("owner"),
                userId = owner.id,
                contactId = contact.id,
                request = ContactUpdateRequest(value = "changed@example.com", isPrimary = true),
            )

        edited.status shouldBe VerificationStatus.PENDING
    }

    @Test
    fun `duplicate contact type is a conflict`() {
        val owner = provisionUser("owner")
        service.create(token = token("owner"), userId = owner.id, request = email("first@example.com"))

        shouldThrow<ContactConflictException> {
            service.create(token = token("owner"), userId = owner.id, request = email("second@example.com"))
        }
    }

    @Test
    fun `verifying a value already verified elsewhere is a conflict`() {
        provisionUser("root", capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val userA = provisionUser("a")
        val userB = provisionUser("b")
        val cA = service.create(token = token("a"), userId = userA.id, request = email("dup@example.com"))
        verify(adminUid = "root", userId = userA.id, contactId = cA.id)
        val cB = service.create(token = token("b"), userId = userB.id, request = email("dup@example.com"))

        shouldThrow<ContactConflictException> {
            verify(adminUid = "root", userId = userB.id, contactId = cB.id)
        }
    }

    @Test
    fun `unknown or mismatched contact is not found`() {
        val owner = provisionUser("owner")
        val other = provisionUser("other")
        val contact = service.create(token = token("other"), userId = other.id, request = email())

        shouldThrow<ContactNotFoundException> {
            service.get(token = token("owner"), userId = owner.id, contactId = UUID.randomUUID())
        }
        // contact exists but belongs to a different user than the path
        shouldThrow<ContactNotFoundException> {
            service.get(token = token("owner"), userId = owner.id, contactId = contact.id)
        }
    }

    @Test
    fun `invalid type and status are rejected`() {
        val owner = provisionUser("owner")

        shouldThrow<IllegalArgumentException> {
            service.create(token = token("owner"), userId = owner.id, request = ContactCreateRequest(type = "FAX", value = "x"))
        }
        val contact = service.create(token = token("owner"), userId = owner.id, request = email())
        provisionUser("root", capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        shouldThrow<IllegalArgumentException> {
            verify(adminUid = "root", userId = owner.id, contactId = contact.id, status = "MAYBE")
        }
    }
}
