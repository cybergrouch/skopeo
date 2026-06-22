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
import org.skopeo.model.ContactType
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
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
            ProvisionUserCommand(
                firebaseUid = uid,
                identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                names = listOf(UserName(type = NameType.FIRST, value = "Test", isPrimary = true)),
            ),
        ).id

    @Test
    fun `create stores a MANUAL PENDING contact`() {
        val userId = newUser("u1")

        val contact = contacts.create(userId = userId, type = ContactType.PHONE, value = "+639170000000", isPrimary = true)

        contact.type shouldBe ContactType.PHONE
        contact.status shouldBe VerificationStatus.PENDING
        contact.isActive.shouldBeTrue()
        contact.verifiedAt.shouldBeNull()
        contacts.listByUser(userId).single().id shouldBe contact.id
        contacts.findById(contact.id).shouldNotBeNull()
    }

    @Test
    fun `findById returns null when absent`() {
        contacts.findById(UUID.randomUUID()).shouldBeNull()
    }

    @Test
    fun `setVerification stamps then clears the audit fields`() {
        val userId = newUser("u2")
        val contact = contacts.create(userId = userId, type = ContactType.EMAIL, value = "u2@example.com", isPrimary = true)

        val verified =
            contacts.setVerification(
                id = contact.id,
                status = VerificationStatus.VERIFIED,
                method = VerificationMethod.ADMIN_OVERRIDE,
                verifiedBy = userId,
                verifiedAt = LocalDateTime.now(),
            )
        verified.shouldNotBeNull()
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
            )
        reverted.shouldNotBeNull()
        reverted.status shouldBe VerificationStatus.PENDING
        reverted.method.shouldBeNull()
        reverted.verifiedAt.shouldBeNull()
        reverted.verifiedBy.shouldBeNull()
    }

    @Test
    fun `setActive disables then re-enables a contact`() {
        val userId = newUser("u3")
        val contact = contacts.create(userId = userId, type = ContactType.EMAIL, value = "u3@example.com", isPrimary = true)

        val disabled = contacts.setActive(id = contact.id, active = false, disabledAt = LocalDateTime.now())
        disabled.shouldNotBeNull()
        disabled.isActive.shouldBeFalse()
        disabled.disabledAt.shouldNotBeNull()

        val reenabled = contacts.setActive(id = contact.id, active = true, disabledAt = null)
        reenabled.shouldNotBeNull()
        reenabled.isActive.shouldBeTrue()
        reenabled.disabledAt.shouldBeNull()
    }

    @Test
    fun `setActive reports absence`() {
        contacts.setActive(id = UUID.randomUUID(), active = false, disabledAt = LocalDateTime.now()).shouldBeNull()
    }

    @Test
    fun `only one active contact per type is allowed, but disabled history accumulates`() {
        val userId = newUser("u5")
        val first = contacts.create(userId = userId, type = ContactType.EMAIL, value = "first@example.com", isPrimary = true)

        // A second active email collides with the first.
        shouldThrow<ExposedSQLException> {
            contacts.create(userId = userId, type = ContactType.EMAIL, value = "second@example.com", isPrimary = true)
        }

        // Disable the first, and a new active email is accepted; both rows remain as history.
        contacts.setActive(id = first.id, active = false, disabledAt = LocalDateTime.now())
        contacts.create(userId = userId, type = ContactType.EMAIL, value = "second@example.com", isPrimary = true)
        contacts.listByUser(userId).size shouldBe 2
    }

    @Test
    fun `a verified value is globally unique`() {
        val userA = newUser("ua")
        val first = contacts.create(userId = userA, type = ContactType.EMAIL, value = "shared@example.com", isPrimary = true)
        contacts.setVerification(
            id = first.id,
            status = VerificationStatus.VERIFIED,
            method = VerificationMethod.ADMIN_OVERRIDE,
            verifiedBy = userA,
            verifiedAt = LocalDateTime.now(),
        )

        val userB = newUser("ub")
        val second = contacts.create(userId = userB, type = ContactType.EMAIL, value = "shared@example.com", isPrimary = true)

        shouldThrow<ExposedSQLException> {
            contacts.setVerification(
                id = second.id,
                status = VerificationStatus.VERIFIED,
                method = VerificationMethod.ADMIN_OVERRIDE,
                verifiedBy = userB,
                verifiedAt = LocalDateTime.now(),
            )
        }

        // Disabling the first holder releases the value: the second can now be verified.
        contacts.setActive(id = first.id, active = false, disabledAt = LocalDateTime.now())
        val nowVerified =
            contacts.setVerification(
                id = second.id,
                status = VerificationStatus.VERIFIED,
                method = VerificationMethod.ADMIN_OVERRIDE,
                verifiedBy = userB,
                verifiedAt = LocalDateTime.now(),
            )
        nowVerified.shouldNotBeNull()
        nowVerified.status shouldBe VerificationStatus.VERIFIED
    }
}
