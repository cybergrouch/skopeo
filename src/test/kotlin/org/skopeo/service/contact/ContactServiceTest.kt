// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.contact

import arrow.core.Either
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuditAction
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.Contact
import org.skopeo.model.ContactType
import org.skopeo.model.DuplicateCandidateStatus
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.ServiceError
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.model.VerificationMethod
import org.skopeo.model.VerificationStatus
import org.skopeo.repository.AuditRepository
import org.skopeo.repository.ContactRepository
import org.skopeo.repository.DuplicateCandidateRepository
import org.skopeo.repository.UserRepository
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
    private val candidates = DuplicateCandidateRepository()
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

    private fun verify(
        adminUid: String,
        userId: UUID,
        contactId: UUID,
        status: VerificationStatus = VerificationStatus.VERIFIED,
    ): Either<ServiceError, Contact> =
        service.setVerification(
            token = token(uid = adminUid),
            userId = userId,
            contactId = contactId,
            status = status,
            method = null,
        )

    @Test
    fun `adding and disabling a contact write audit-log entries (#100)`() {
        val owner = provisionUser(uid = "owner")
        val created =
            service
                .create(
                    token = token(uid = "owner"),
                    userId = owner.id,
                    type = ContactType.EMAIL,
                    value = "a@b.dev",
                    isPrimary = true,
                ).shouldBeRight()
        service.setActive(token = token(uid = "owner"), userId = owner.id, contactId = created.id, active = false).shouldBeRight()
        service.setActive(token = token(uid = "owner"), userId = owner.id, contactId = created.id, active = true).shouldBeRight()

        val audit = AuditRepository()
        audit.list(actions = listOf(element = AuditAction.CONTACT_ADDED), limit = 10, offset = 0).first.single().let {
            it.actorUserId shouldBe owner.id
            it.entityId shouldBe owner.id
            it.summary shouldBe "Added EMAIL a@b.dev"
        }
        // Both the disable and the re-enable are recorded.
        val updates =
            audit.list(actions = listOf(element = AuditAction.CONTACT_UPDATED), limit = 10, offset = 0).first.map { it.summary }
        updates.toSet() shouldBe setOf("Disabled EMAIL a@b.dev", "Enabled EMAIL a@b.dev")
    }

    @Test
    fun `owner can create, list, get and disable own contacts`() {
        val owner = provisionUser(uid = "owner")

        val created =
            service
                .create(
                    token = token(uid = "owner"),
                    userId = owner.id,
                    type = ContactType.EMAIL,
                    value = "a@example.com",
                    isPrimary = true,
                ).shouldBeRight()
        service.list(token = token(uid = "owner"), userId = owner.id).shouldBeRight().single().id shouldBe created.id
        service.get(token = token(uid = "owner"), userId = owner.id, contactId = created.id).shouldBeRight().id shouldBe created.id

        val disabled =
            service.setActive(token = token(uid = "owner"), userId = owner.id, contactId = created.id, active = false).shouldBeRight()
        disabled.isActive.shouldBeFalse()
    }

    @Test
    fun `disabling lets a new contact of the same type be added, re-enabling then conflicts`() {
        val owner = provisionUser(uid = "owner")
        val first =
            service
                .create(
                    token = token(uid = "owner"),
                    userId = owner.id,
                    type = ContactType.EMAIL,
                    value = "first@example.com",
                    isPrimary = true,
                ).shouldBeRight()

        service.setActive(token = token(uid = "owner"), userId = owner.id, contactId = first.id, active = false).shouldBeRight()
        val second =
            service
                .create(
                    token = token(uid = "owner"),
                    userId = owner.id,
                    type = ContactType.EMAIL,
                    value = "second@example.com",
                    isPrimary = true,
                ).shouldBeRight()

        // Re-enabling the first now collides with the active second.
        service
            .setActive(token = token(uid = "owner"), userId = owner.id, contactId = first.id, active = true)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Conflict>()
        second.isActive.shouldBeTrue()
    }

    @Test
    fun `a disabled contact cannot be verified`() {
        val owner = provisionUser(uid = "owner")
        provisionUser(uid = "root", capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val contact =
            service
                .create(
                    token = token(uid = "owner"),
                    userId = owner.id,
                    type = ContactType.EMAIL,
                    value = "a@example.com",
                    isPrimary = true,
                ).shouldBeRight()
        service.setActive(token = token(uid = "owner"), userId = owner.id, contactId = contact.id, active = false).shouldBeRight()

        verify(adminUid = "root", userId = owner.id, contactId = contact.id)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `a non-owner non-admin cannot access another user's contacts`() {
        val owner = provisionUser(uid = "owner")
        provisionUser(uid = "intruder")
        service
            .create(token = token(uid = "owner"), userId = owner.id, type = ContactType.EMAIL, value = "a@example.com", isPrimary = true)
            .shouldBeRight()

        service.list(token = token(uid = "intruder"), userId = owner.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `owner cannot self-verify, but an ADMINISTRATOR can`() {
        val owner = provisionUser(uid = "owner")
        val admin = provisionUser(uid = "root", capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val contact =
            service
                .create(
                    token = token(uid = "owner"),
                    userId = owner.id,
                    type = ContactType.EMAIL,
                    value = "a@example.com",
                    isPrimary = true,
                ).shouldBeRight()

        service
            .setVerification(
                token = token(uid = "owner"),
                userId = owner.id,
                contactId = contact.id,
                status = VerificationStatus.VERIFIED,
                method = null,
            ).shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()

        val verified =
            service
                .setVerification(
                    token = token(uid = "root"),
                    userId = owner.id,
                    contactId = contact.id,
                    status = VerificationStatus.VERIFIED,
                    method = null,
                ).shouldBeRight()
        verified.status shouldBe VerificationStatus.VERIFIED
        verified.method shouldBe VerificationMethod.ADMIN_OVERRIDE
        verified.verifiedBy shouldBe admin.id
    }

    @Test
    fun `an ADMINISTRATOR can read another user's contacts`() {
        val owner = provisionUser(uid = "owner")
        provisionUser(uid = "root", capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val created =
            service
                .create(
                    token = token(uid = "owner"),
                    userId = owner.id,
                    type = ContactType.EMAIL,
                    value = "a@example.com",
                    isPrimary = true,
                ).shouldBeRight()

        service.list(token = token(uid = "root"), userId = owner.id).shouldBeRight().single().id shouldBe created.id
        service.get(token = token(uid = "root"), userId = owner.id, contactId = created.id).shouldBeRight().id shouldBe created.id
    }

    @Test
    fun `a caller without a provisioned account is forbidden`() {
        val owner = provisionUser(uid = "owner")
        service
            .create(token = token(uid = "owner"), userId = owner.id, type = ContactType.EMAIL, value = "a@example.com", isPrimary = true)
            .shouldBeRight()

        service.list(token = token(uid = "ghost"), userId = owner.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `a caller without a provisioned account cannot verify`() {
        val owner = provisionUser(uid = "owner")
        val contact =
            service
                .create(
                    token = token(uid = "owner"),
                    userId = owner.id,
                    type = ContactType.EMAIL,
                    value = "a@example.com",
                    isPrimary = true,
                ).shouldBeRight()

        verify(adminUid = "ghost", userId = owner.id, contactId = contact.id)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `an ADMINISTRATOR can revoke a verification back to PENDING`() {
        val owner = provisionUser(uid = "owner")
        provisionUser(uid = "root", capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val contact =
            service
                .create(
                    token = token(uid = "owner"),
                    userId = owner.id,
                    type = ContactType.EMAIL,
                    value = "a@example.com",
                    isPrimary = true,
                ).shouldBeRight()
        verify(adminUid = "root", userId = owner.id, contactId = contact.id).shouldBeRight()

        val revoked =
            verify(adminUid = "root", userId = owner.id, contactId = contact.id, status = VerificationStatus.PENDING).shouldBeRight()

        revoked.status shouldBe VerificationStatus.PENDING
        revoked.method.shouldBe(expected = null)
    }

    @Test
    fun `duplicate contact type is a conflict`() {
        val owner = provisionUser(uid = "owner")
        service
            .create(
                token = token(uid = "owner"),
                userId = owner.id,
                type = ContactType.EMAIL,
                value = "first@example.com",
                isPrimary = true,
            ).shouldBeRight()

        service
            .create(
                token = token(uid = "owner"),
                userId = owner.id,
                type = ContactType.EMAIL,
                value = "second@example.com",
                isPrimary = true,
            ).shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Conflict>()
    }

    @Test
    fun `verifying a value already verified elsewhere is a conflict`() {
        provisionUser(uid = "root", capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val userA = provisionUser(uid = "a")
        val userB = provisionUser(uid = "b")
        val cA =
            service
                .create(
                    token = token(uid = "a"),
                    userId = userA.id,
                    type = ContactType.EMAIL,
                    value = "dup@example.com",
                    isPrimary = true,
                ).shouldBeRight()
        verify(adminUid = "root", userId = userA.id, contactId = cA.id).shouldBeRight()
        val cB =
            service
                .create(
                    token = token(uid = "b"),
                    userId = userB.id,
                    type = ContactType.EMAIL,
                    value = "dup@example.com",
                    isPrimary = true,
                ).shouldBeRight()

        verify(adminUid = "root", userId = userB.id, contactId = cB.id)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Conflict>()
    }

    @Test
    fun `unknown or mismatched contact is not found`() {
        val owner = provisionUser(uid = "owner")
        val other = provisionUser(uid = "other")
        val contact =
            service
                .create(
                    token = token(uid = "other"),
                    userId = other.id,
                    type = ContactType.EMAIL,
                    value = "a@example.com",
                    isPrimary = true,
                ).shouldBeRight()

        service
            .get(token = token(uid = "owner"), userId = owner.id, contactId = UUID.randomUUID())
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
        // contact exists but belongs to a different user than the path
        service
            .get(token = token(uid = "owner"), userId = owner.id, contactId = contact.id)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `disabling then re-enabling the only contact succeeds`() {
        val owner = provisionUser(uid = "owner")
        val contact =
            service
                .create(
                    token = token(uid = "owner"),
                    userId = owner.id,
                    type = ContactType.EMAIL,
                    value = "a@example.com",
                    isPrimary = true,
                ).shouldBeRight()
        service.setActive(token = token(uid = "owner"), userId = owner.id, contactId = contact.id, active = false).shouldBeRight()

        val reenabled =
            service.setActive(token = token(uid = "owner"), userId = owner.id, contactId = contact.id, active = true).shouldBeRight()

        reenabled.isActive.shouldBeTrue()
        reenabled.disabledAt.shouldBe(expected = null)
    }

    @Test
    fun `an explicit verification method is honored over the default`() {
        val owner = provisionUser(uid = "owner")
        provisionUser(uid = "root", capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val contact =
            service
                .create(
                    token = token(uid = "owner"),
                    userId = owner.id,
                    type = ContactType.EMAIL,
                    value = "a@example.com",
                    isPrimary = true,
                ).shouldBeRight()

        val verified =
            service
                .setVerification(
                    token = token(uid = "root"),
                    userId = owner.id,
                    contactId = contact.id,
                    status = VerificationStatus.VERIFIED,
                    method = VerificationMethod.EMAIL_LINK,
                ).shouldBeRight()

        verified.status shouldBe VerificationStatus.VERIFIED
        verified.method shouldBe VerificationMethod.EMAIL_LINK
    }

    @Test
    fun `creating a contact for a non-existent user is not found`() {
        provisionUser(uid = "owner")

        service
            .create(
                token = token(uid = "owner"),
                userId = UUID.randomUUID(),
                type = ContactType.EMAIL,
                value = "a@example.com",
                isPrimary = true,
            ).shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `listing contacts for a non-existent user is not found`() {
        provisionUser(uid = "owner")

        service
            .list(token = token(uid = "owner"), userId = UUID.randomUUID())
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `a provisioned non-admin self cannot verify but reaches the capability check`() {
        val owner = provisionUser(uid = "owner")
        val contact =
            service
                .create(
                    token = token(uid = "owner"),
                    userId = owner.id,
                    type = ContactType.EMAIL,
                    value = "a@example.com",
                    isPrimary = true,
                ).shouldBeRight()

        // The caller is provisioned (non-null) but lacks ADMINISTRATOR -> capability branch is false.
        verify(adminUid = "owner", userId = owner.id, contactId = contact.id)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `adding a phone matching another active user flags a duplicate candidate, normalized (#126)`() {
        val a = provisionUser(uid = "a")
        val b = provisionUser(uid = "b")
        service
            .create(token = token(uid = "a"), userId = a.id, type = ContactType.PHONE, value = "+639170000000", isPrimary = true)
            .shouldBeRight()

        // A formatting variant of the same number still flags — and the contact add itself succeeds.
        val added =
            service
                .create(token = token(uid = "b"), userId = b.id, type = ContactType.PHONE, value = "+63 917 000 0000", isPrimary = true)
                .shouldBeRight()
        added.value shouldBe "+63 917 000 0000"

        val open = candidates.list(limit = 50, offset = 0, status = DuplicateCandidateStatus.OPEN).first
        open shouldHaveSize 1
        setOf(open.single().userAId, open.single().userBId) shouldBe setOf(a.id, b.id)
    }

    @Test
    fun `a non-phone contact, or a match on a disabled user, raises no candidate (#126)`() {
        val a = provisionUser(uid = "a")
        val b = provisionUser(uid = "b")
        // EMAIL never triggers detection.
        service
            .create(token = token(uid = "a"), userId = a.id, type = ContactType.EMAIL, value = "a@x.dev", isPrimary = true)
            .shouldBeRight()
        // A's phone, then A is disabled — B reusing it should not flag (only active users count).
        service
            .create(token = token(uid = "a"), userId = a.id, type = ContactType.PHONE, value = "+639170000000", isPrimary = false)
            .shouldBeRight()
        users.deactivate(id = a.id)
        service
            .create(token = token(uid = "b"), userId = b.id, type = ContactType.PHONE, value = "+639170000000", isPrimary = true)
            .shouldBeRight()

        candidates.list(limit = 50, offset = 0, status = DuplicateCandidateStatus.OPEN).second shouldBe 0L
    }
}
