// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.ContactInfo
import org.skopeo.model.ContactSource
import org.skopeo.model.ContactType
import org.skopeo.model.NameType
import org.skopeo.model.ProfilePatch
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.model.VerificationMethod
import org.skopeo.model.VerificationStatus
import org.skopeo.testsupport.PostgresTestDatabase
import java.time.LocalDate
import java.util.UUID

/**
 * Exercises [UserRepository] against a real PostgreSQL with the actual Flyway V1
 * migration applied — so the Exposed mappings, the aggregate writes, and the
 * schema's constraints (one-contact-per-type, globally-unique verified value,
 * unique firebase_uid) are all validated end-to-end.
 */
class UserRepositoryTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun migrateAndConnect() {
            PostgresTestDatabase.start()
        }
    }

    private val repository = UserRepository()

    @BeforeEach
    fun resetTables() {
        PostgresTestDatabase.truncate()
    }

    private fun googleSignup(
        firebaseUid: String = "firebase-${UUID.randomUUID()}",
        email: String = "${UUID.randomUUID()}@example.com",
    ) = ProvisionUserCommand(
        firebaseUid = firebaseUid,
        identity = UserIdentity(provider = AuthProvider.GOOGLE, providerUid = "google-sub-123", isPrimary = true),
        names =
            listOf(
                UserName(type = NameType.FIRST, value = "Juan"),
                UserName(type = NameType.LAST, value = "dela Cruz"),
                UserName(type = NameType.NICKNAME, value = "Johnny"),
            ),
        photoUrl = "https://example.com/photo.jpg",
        email =
            ContactInfo(
                type = ContactType.EMAIL,
                value = email,
                source = ContactSource.GOOGLE,
                status = VerificationStatus.VERIFIED,
                method = VerificationMethod.OAUTH_PROVIDER,
                isPrimary = true,
            ),
        city = "Manila",
        gender = "M",
    )

    @Test
    fun `provision writes and reads back the full aggregate`() {
        val created = repository.provision(googleSignup(email = "juan@example.com"))

        created.firebaseUid.shouldNotBeNull()
        created.country shouldBe "PH" // defaulted by the DB
        created.isActive.shouldBeTrue()
        created.kycVerified.shouldBeFalse()
        created.city shouldBe "Manila"
        created.gender shouldBe "M"
        created.names.map { it.value }.shouldContainExactlyInAnyOrder("Juan", "dela Cruz", "Johnny")
        created.identities.single().provider shouldBe AuthProvider.GOOGLE
        created.contacts.single().let {
            it.type shouldBe ContactType.EMAIL
            it.value shouldBe "juan@example.com"
            it.status shouldBe VerificationStatus.VERIFIED
        }
        created.capabilities shouldBe setOf(Capability.PLAYER)
    }

    @Test
    fun `findByFirebaseUid returns the aggregate`() {
        val created = repository.provision(googleSignup(firebaseUid = "uid-lookup"))

        val found = repository.findByFirebaseUid("uid-lookup")

        found.shouldNotBeNull()
        found.id shouldBe created.id
        found.contacts.size shouldBe 1
    }

    @Test
    fun `findByFirebaseUid returns null when absent`() {
        repository.findByFirebaseUid("nobody-home").shouldBe(null)
    }

    @Test
    fun `findById returns null for an unknown id`() {
        repository.findById(UUID.randomUUID()).shouldBe(null)
    }

    @Test
    fun `provision persists an optional phone alongside the email`() {
        val command =
            googleSignup().copy(
                country = "US",
                phone =
                    ContactInfo(
                        type = ContactType.PHONE,
                        value = "+639170000000",
                        source = ContactSource.MANUAL,
                        status = VerificationStatus.PENDING,
                    ),
            )

        val created = repository.provision(command)

        created.contacts.map { it.type }.shouldContainExactlyInAnyOrder(ContactType.EMAIL, ContactType.PHONE)
        created.country shouldBe "US"
    }

    @Test
    fun `updateProfile patches only the provided fields`() {
        val created = repository.provision(googleSignup())

        val updated = repository.updateProfile(created.id, ProfilePatch(city = "Cebu", dateOfBirth = LocalDate.of(1990, 1, 2)))

        updated.shouldNotBeNull()
        updated.city shouldBe "Cebu"
        updated.dateOfBirth shouldBe LocalDate.of(1990, 1, 2)
        updated.gender shouldBe "M" // untouched
    }

    @Test
    fun `updateProfile returns null for an unknown id`() {
        repository.updateProfile(UUID.randomUUID(), ProfilePatch(city = "Davao")).shouldBe(null)
    }

    @Test
    fun `replaceProfile overwrites all mutable fields, clearing omitted ones`() {
        val created = repository.provision(googleSignup()) // gender = M, city = Manila

        val replaced = repository.replaceProfile(created.id, ProfilePatch(city = "Iloilo"))

        replaced.shouldNotBeNull()
        replaced.city shouldBe "Iloilo"
        replaced.gender.shouldBe(null) // omitted from the replacement → cleared
    }

    @Test
    fun `replaceProfile returns null for an unknown id`() {
        repository.replaceProfile(UUID.randomUUID(), ProfilePatch(city = "Davao")).shouldBe(null)
    }

    @Test
    fun `deactivate soft-deletes the user`() {
        val created = repository.provision(googleSignup())

        repository.deactivate(created.id).shouldBeTrue()

        repository.findById(created.id)?.isActive?.shouldBeFalse()
        repository.deactivate(UUID.randomUUID()).shouldBeFalse()
    }

    @Test
    fun `a verified email is globally unique across users`() {
        repository.provision(googleSignup(email = "shared@example.com"))

        assertThrows<Exception> {
            repository.provision(googleSignup(email = "shared@example.com"))
        }
    }
}
