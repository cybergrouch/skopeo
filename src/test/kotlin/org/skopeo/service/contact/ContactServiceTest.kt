// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.contact

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.dto.contact.ContactCreateRequest
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
import org.skopeo.service.user.UserNotFoundException
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
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = listOf(UserName(type = NameType.FIRST, value = "N")),
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
        token = token(uid = adminUid),
        userId = userId,
        contactId = contactId,
        request = VerificationRequest(status = status),
    )

    @Test
    fun `owner can create, list, get and disable own contacts`() {
        val owner = provisionUser(uid = "owner")

        val created = service.create(token = token(uid = "owner"), userId = owner.id, request = email())
        service.list(token = token(uid = "owner"), userId = owner.id).single().id shouldBe created.id
        service.get(token = token(uid = "owner"), userId = owner.id, contactId = created.id).id shouldBe created.id

        val disabled = service.setActive(token = token(uid = "owner"), userId = owner.id, contactId = created.id, active = false)
        disabled.isActive.shouldBeFalse()
    }

    @Test
    fun `disabling lets a new contact of the same type be added, re-enabling then conflicts`() {
        val owner = provisionUser(uid = "owner")
        val first = service.create(token = token(uid = "owner"), userId = owner.id, request = email(value = "first@example.com"))

        service.setActive(token = token(uid = "owner"), userId = owner.id, contactId = first.id, active = false)
        val second = service.create(token = token(uid = "owner"), userId = owner.id, request = email(value = "second@example.com"))

        // Re-enabling the first now collides with the active second.
        shouldThrow<ContactConflictException> {
            service.setActive(token = token(uid = "owner"), userId = owner.id, contactId = first.id, active = true)
        }
        second.isActive.shouldBeTrue()
    }

    @Test
    fun `a disabled contact cannot be verified`() {
        val owner = provisionUser(uid = "owner")
        provisionUser(uid = "root", capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val contact = service.create(token = token(uid = "owner"), userId = owner.id, request = email())
        service.setActive(token = token(uid = "owner"), userId = owner.id, contactId = contact.id, active = false)

        shouldThrow<IllegalArgumentException> {
            verify(adminUid = "root", userId = owner.id, contactId = contact.id)
        }
    }

    @Test
    fun `a non-owner non-admin cannot access another user's contacts`() {
        val owner = provisionUser(uid = "owner")
        provisionUser(uid = "intruder")
        service.create(token = token(uid = "owner"), userId = owner.id, request = email())

        shouldThrow<ForbiddenException> { service.list(token = token(uid = "intruder"), userId = owner.id) }
    }

    @Test
    fun `owner cannot self-verify, but an ADMINISTRATOR can`() {
        val owner = provisionUser(uid = "owner")
        val admin = provisionUser(uid = "root", capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val contact = service.create(token = token(uid = "owner"), userId = owner.id, request = email())

        shouldThrow<ForbiddenException> {
            service.setVerification(
                token = token(uid = "owner"),
                userId = owner.id,
                contactId = contact.id,
                request = VerificationRequest(status = "VERIFIED"),
            )
        }

        val verified =
            service.setVerification(
                token = token(uid = "root"),
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
        val owner = provisionUser(uid = "owner")
        provisionUser(uid = "root", capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val created = service.create(token = token(uid = "owner"), userId = owner.id, request = email())

        service.list(token = token(uid = "root"), userId = owner.id).single().id shouldBe created.id
        service.get(token = token(uid = "root"), userId = owner.id, contactId = created.id).id shouldBe created.id
    }

    @Test
    fun `a caller without a provisioned account is forbidden`() {
        val owner = provisionUser(uid = "owner")
        service.create(token = token(uid = "owner"), userId = owner.id, request = email())

        shouldThrow<ForbiddenException> { service.list(token = token(uid = "ghost"), userId = owner.id) }
    }

    @Test
    fun `a caller without a provisioned account cannot verify`() {
        val owner = provisionUser(uid = "owner")
        val contact = service.create(token = token(uid = "owner"), userId = owner.id, request = email())

        shouldThrow<ForbiddenException> {
            verify(adminUid = "ghost", userId = owner.id, contactId = contact.id)
        }
    }

    @Test
    fun `an ADMINISTRATOR can revoke a verification back to PENDING`() {
        val owner = provisionUser(uid = "owner")
        provisionUser(uid = "root", capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val contact = service.create(token = token(uid = "owner"), userId = owner.id, request = email())
        verify(adminUid = "root", userId = owner.id, contactId = contact.id)

        val revoked = verify(adminUid = "root", userId = owner.id, contactId = contact.id, status = "PENDING")

        revoked.status shouldBe VerificationStatus.PENDING
        revoked.method.shouldBe(expected = null)
    }

    @Test
    fun `duplicate contact type is a conflict`() {
        val owner = provisionUser(uid = "owner")
        service.create(token = token(uid = "owner"), userId = owner.id, request = email(value = "first@example.com"))

        shouldThrow<ContactConflictException> {
            service.create(token = token(uid = "owner"), userId = owner.id, request = email(value = "second@example.com"))
        }
    }

    @Test
    fun `verifying a value already verified elsewhere is a conflict`() {
        provisionUser(uid = "root", capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val userA = provisionUser(uid = "a")
        val userB = provisionUser(uid = "b")
        val cA = service.create(token = token(uid = "a"), userId = userA.id, request = email(value = "dup@example.com"))
        verify(adminUid = "root", userId = userA.id, contactId = cA.id)
        val cB = service.create(token = token(uid = "b"), userId = userB.id, request = email(value = "dup@example.com"))

        shouldThrow<ContactConflictException> {
            verify(adminUid = "root", userId = userB.id, contactId = cB.id)
        }
    }

    @Test
    fun `unknown or mismatched contact is not found`() {
        val owner = provisionUser(uid = "owner")
        val other = provisionUser(uid = "other")
        val contact = service.create(token = token(uid = "other"), userId = other.id, request = email())

        shouldThrow<ContactNotFoundException> {
            service.get(token = token(uid = "owner"), userId = owner.id, contactId = UUID.randomUUID())
        }
        // contact exists but belongs to a different user than the path
        shouldThrow<ContactNotFoundException> {
            service.get(token = token(uid = "owner"), userId = owner.id, contactId = contact.id)
        }
    }

    @Test
    fun `disabling then re-enabling the only contact succeeds`() {
        val owner = provisionUser(uid = "owner")
        val contact = service.create(token = token(uid = "owner"), userId = owner.id, request = email())
        service.setActive(token = token(uid = "owner"), userId = owner.id, contactId = contact.id, active = false)

        val reenabled = service.setActive(token = token(uid = "owner"), userId = owner.id, contactId = contact.id, active = true)

        reenabled.isActive.shouldBeTrue()
        reenabled.disabledAt.shouldBe(expected = null)
    }

    @Test
    fun `an explicit verification method is honored over the default`() {
        val owner = provisionUser(uid = "owner")
        provisionUser(uid = "root", capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val contact = service.create(token = token(uid = "owner"), userId = owner.id, request = email())

        val verified =
            service.setVerification(
                token = token(uid = "root"),
                userId = owner.id,
                contactId = contact.id,
                request = VerificationRequest(status = "VERIFIED", method = "EMAIL_LINK"),
            )

        verified.status shouldBe VerificationStatus.VERIFIED
        verified.method shouldBe VerificationMethod.EMAIL_LINK
    }

    @Test
    fun `creating a contact for a non-existent user is not found`() {
        val owner = provisionUser(uid = "owner")

        shouldThrow<UserNotFoundException> {
            service.create(token = token(uid = "owner"), userId = UUID.randomUUID(), request = email())
        }
    }

    @Test
    fun `listing contacts for a non-existent user is not found`() {
        provisionUser(uid = "owner")

        shouldThrow<UserNotFoundException> {
            service.list(token = token(uid = "owner"), userId = UUID.randomUUID())
        }
    }

    @Test
    fun `a provisioned non-admin self cannot verify but reaches the capability check`() {
        val owner = provisionUser(uid = "owner")
        val contact = service.create(token = token(uid = "owner"), userId = owner.id, request = email())

        // The caller is provisioned (non-null) but lacks ADMINISTRATOR -> capability branch is false.
        shouldThrow<ForbiddenException> {
            verify(adminUid = "owner", userId = owner.id, contactId = contact.id)
        }
    }

    @Test
    fun `invalid type and status are rejected`() {
        val owner = provisionUser(uid = "owner")

        shouldThrow<IllegalArgumentException> {
            service.create(token = token(uid = "owner"), userId = owner.id, request = ContactCreateRequest(type = "FAX", value = "x"))
        }
        val contact = service.create(token = token(uid = "owner"), userId = owner.id, request = email())
        provisionUser(uid = "root", capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        shouldThrow<IllegalArgumentException> {
            verify(adminUid = "root", userId = owner.id, contactId = contact.id, status = "MAYBE")
        }
    }
}
