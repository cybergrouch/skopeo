// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import arrow.core.Either
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuthProvider
import org.skopeo.model.Contact
import org.skopeo.model.ContactType
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.ServiceError
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.model.VerificationMethod
import org.skopeo.model.VerificationStatus
import org.skopeo.testsupport.PostgresTestDatabase
import java.time.LocalDateTime
import java.util.UUID

class ContactRepositoryTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val contacts = ContactRepository()

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun newUser(uid: String): UUID =
        users.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = listOf(UserName(type = NameType.FIRST, value = "Test")),
                ),
        ).id

    /** Add a contact, returning the raw [Either] so callers can assert either branch. */
    private fun addContact(
        userId: UUID,
        type: ContactType,
        value: String,
    ): Either<ServiceError, Contact> = contacts.create(userId = userId, type = type, value = value, isPrimary = true)

    @Test
    fun `create stores a MANUAL PENDING contact`() {
        val userId = newUser(uid = "u1")

        val contact = addContact(userId = userId, type = ContactType.PHONE, value = "+639170000000").shouldBeRight()

        contact.type shouldBe ContactType.PHONE
        contact.status shouldBe VerificationStatus.PENDING
        contact.isActive.shouldBeTrue()
        contact.verifiedAt.shouldBeNull()
        contacts.listByUser(userId = userId).single().id shouldBe contact.id
        contacts.findById(id = contact.id).shouldBeRight()
    }

    @Test
    fun `findById returns NotFound when absent`() {
        contacts.findById(id = UUID.randomUUID()).shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `setVerification stamps then clears the audit fields`() {
        val userId = newUser(uid = "u2")
        val contact = addContact(userId = userId, type = ContactType.EMAIL, value = "u2@example.com").shouldBeRight()

        val verified =
            contacts.setVerification(
                id = contact.id,
                status = VerificationStatus.VERIFIED,
                method = VerificationMethod.ADMIN_OVERRIDE,
                verifiedBy = userId,
                verifiedAt = LocalDateTime.now(),
            ).shouldBeRight()
        verified.status shouldBe VerificationStatus.VERIFIED
        verified.method shouldBe VerificationMethod.ADMIN_OVERRIDE
        verified.verifiedAt.shouldNotBeNull()
        verified.verifiedBy shouldBe userId

        val reverted =
            contacts.setVerification(
                id = contact.id,
                status = VerificationStatus.PENDING,
                method = null,
                verifiedBy = userId,
                verifiedAt = LocalDateTime.now(),
            ).shouldBeRight()
        reverted.status shouldBe VerificationStatus.PENDING
        reverted.method.shouldBeNull()
        reverted.verifiedAt.shouldBeNull()
        reverted.verifiedBy.shouldBeNull()
    }

    @Test
    fun `setVerification with VERIFIED status but no method leaves the method null`() {
        val userId = newUser(uid = "u2b")
        val contact = addContact(userId = userId, type = ContactType.EMAIL, value = "u2b@example.com").shouldBeRight()

        val verified =
            contacts.setVerification(
                id = contact.id,
                status = VerificationStatus.VERIFIED,
                method = null,
                verifiedBy = userId,
                verifiedAt = LocalDateTime.now(),
            ).shouldBeRight()

        verified.status shouldBe VerificationStatus.VERIFIED
        verified.method.shouldBeNull()
        verified.verifiedAt.shouldNotBeNull()
        verified.verifiedBy shouldBe userId
    }

    @Test
    fun `setVerification reports absence`() {
        contacts.setVerification(
            id = UUID.randomUUID(),
            status = VerificationStatus.VERIFIED,
            method = VerificationMethod.ADMIN_OVERRIDE,
            verifiedBy = UUID.randomUUID(),
            verifiedAt = LocalDateTime.now(),
        ).shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `setActive disables then re-enables a contact`() {
        val userId = newUser(uid = "u3")
        val contact = addContact(userId = userId, type = ContactType.EMAIL, value = "u3@example.com").shouldBeRight()

        val disabled =
            contacts
                .setActive(id = contact.id, active = false, disabledAt = LocalDateTime.now())
                .shouldBeRight()
        disabled.isActive.shouldBeFalse()
        disabled.disabledAt.shouldNotBeNull()

        val reenabled =
            contacts.setActive(id = contact.id, active = true, disabledAt = null).shouldBeRight()
        reenabled.isActive.shouldBeTrue()
        reenabled.disabledAt.shouldBeNull()
    }

    @Test
    fun `setActive reports absence`() {
        contacts
            .setActive(id = UUID.randomUUID(), active = false, disabledAt = LocalDateTime.now())
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `only one active contact per type is allowed, but disabled history accumulates`() {
        val userId = newUser(uid = "u5")
        val first = addContact(userId = userId, type = ContactType.EMAIL, value = "first@example.com").shouldBeRight()

        // A second active email collides with the first.
        addContact(userId = userId, type = ContactType.EMAIL, value = "second@example.com")
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Conflict>()

        // Disable the first, and a new active email is accepted; both rows remain as history.
        contacts.setActive(id = first.id, active = false, disabledAt = LocalDateTime.now()).shouldBeRight()
        addContact(userId = userId, type = ContactType.EMAIL, value = "second@example.com").shouldBeRight()
        contacts.listByUser(userId = userId).size shouldBe 2
    }

    @Test
    fun `a verified value is globally unique`() {
        val userA = newUser(uid = "ua")
        val first = addContact(userId = userA, type = ContactType.EMAIL, value = "shared@example.com").shouldBeRight()
        contacts.setVerification(
            id = first.id,
            status = VerificationStatus.VERIFIED,
            method = VerificationMethod.ADMIN_OVERRIDE,
            verifiedBy = userA,
            verifiedAt = LocalDateTime.now(),
        ).shouldBeRight()

        val userB = newUser(uid = "ub")
        val second = addContact(userId = userB, type = ContactType.EMAIL, value = "shared@example.com").shouldBeRight()

        contacts.setVerification(
            id = second.id,
            status = VerificationStatus.VERIFIED,
            method = VerificationMethod.ADMIN_OVERRIDE,
            verifiedBy = userB,
            verifiedAt = LocalDateTime.now(),
        ).shouldBeLeft().shouldBeInstanceOf<ServiceError.Conflict>()

        // Disabling the first holder releases the value: the second can now be verified.
        contacts.setActive(id = first.id, active = false, disabledAt = LocalDateTime.now()).shouldBeRight()
        val nowVerified =
            contacts.setVerification(
                id = second.id,
                status = VerificationStatus.VERIFIED,
                method = VerificationMethod.ADMIN_OVERRIDE,
                verifiedBy = userB,
                verifiedAt = LocalDateTime.now(),
            ).shouldBeRight()
        nowVerified.status shouldBe VerificationStatus.VERIFIED
    }
}
