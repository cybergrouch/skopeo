// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
import org.skopeo.model.ServiceError
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.model.UserSearchQuery
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
        sex = "Male",
    )

    @Test
    fun `provision writes and reads back the full aggregate`() {
        val created = repository.provision(command = googleSignup(email = "juan@example.com"))

        created.firebaseUid.shouldNotBeNull()
        created.country shouldBe "PH" // defaulted by the DB
        created.isActive.shouldBeTrue()
        created.kycVerified.shouldBeFalse()
        created.city shouldBe "Manila"
        created.sex shouldBe "Male"
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
    fun `search paginates with limit and offset over a stable id order (#197)`() {
        val ids =
            (1..3)
                .map { n ->
                    // Distinct identities — the helper hardcodes providerUid, which is uniquely constrained.
                    repository.provision(
                        command =
                            googleSignup().copy(
                                identity = UserIdentity(provider = AuthProvider.GOOGLE, providerUid = "sub-$n", isPrimary = true),
                            ),
                    ).id
                }.toSet()
        val query =
            UserSearchQuery(name = null, code = null, q = null, sex = "Male", dobMin = null, dobMax = null, rating = null)

        val firstPage = repository.search(query = query, limit = 2, offset = 0)
        val secondPage = repository.search(query = query, limit = 2, offset = 2)

        firstPage shouldHaveSize 2
        secondPage shouldHaveSize 1
        // The pages are disjoint and together cover every match (offset over the id-ASC order).
        (firstPage.map { it.id } + secondPage.map { it.id }).toSet() shouldBe ids

        // Default limit/offset (the typeahead path) returns every match in one page.
        repository.search(query = query).map { it.id }.toSet() shouldBe ids
    }

    @Test
    fun `findByFirebaseUid returns the aggregate`() {
        val created = repository.provision(command = googleSignup(firebaseUid = "uid-lookup"))

        val found = repository.findByFirebaseUid(firebaseUid = "uid-lookup")

        found.shouldNotBeNull()
        found.id shouldBe created.id
        found.contacts.size shouldBe 1
    }

    @Test
    fun `findByFirebaseUid returns null when absent`() {
        repository.findByFirebaseUid(firebaseUid = "nobody-home").shouldBe(expected = null)
    }

    @Test
    fun `findById reports NotFound for an unknown id`() {
        repository.findById(id = UUID.randomUUID()).shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
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

        val created = repository.provision(command = command)

        created.contacts.map { it.type }.shouldContainExactlyInAnyOrder(ContactType.EMAIL, ContactType.PHONE)
        created.country shouldBe "US"
    }

    @Test
    fun `updateProfile patches only the provided fields`() {
        val created = repository.provision(command = googleSignup())

        val updated =
            repository
                .updateProfile(id = created.id, patch = ProfilePatch(city = "Cebu", dateOfBirth = LocalDate.of(1990, 1, 2)))
                .shouldBeRight()

        updated.city shouldBe "Cebu"
        updated.dateOfBirth shouldBe LocalDate.of(1990, 1, 2)
        updated.sex shouldBe "Male" // untouched
    }

    @Test
    fun `updateProviderPhotoUrl refreshes the provider photo without touching other fields (#219)`() {
        val created = repository.provision(command = googleSignup())

        repository.updateProviderPhotoUrl(userId = created.id, providerPhotoUrl = "https://example.com/new.jpg")

        val updated = repository.findById(id = created.id).shouldBeRight()
        // No custom URL / hide, so the effective photo is the freshly synced provider photo.
        updated.photoUrl shouldBe "https://example.com/new.jpg"
        updated.city shouldBe "Manila" // untouched
    }

    @Test
    fun `updatePhotoSettings sets a custom URL and hide, provider refresh never clobbers the custom photo (#303)`() {
        val created = repository.provision(command = googleSignup())

        // A custom photo overrides the provider photo as the effective photo.
        repository.updatePhotoSettings(id = created.id, customPhotoUrl = "https://c/me.png", photoHidden = false)
            .shouldBeRight()
            .photoUrl shouldBe "https://c/me.png"

        // Refreshing the provider photo (as login does) keeps the custom photo effective.
        repository.updateProviderPhotoUrl(userId = created.id, providerPhotoUrl = "https://p/new.jpg")
        repository.findById(id = created.id).shouldBeRight().let {
            it.photoUrl shouldBe "https://c/me.png"
            it.providerPhotoUrl shouldBe "https://p/new.jpg"
        }

        // Hiding suppresses the effective photo; clearing the custom URL reverts to the provider photo.
        repository.updatePhotoSettings(id = created.id, customPhotoUrl = null, photoHidden = true)
            .shouldBeRight()
            .photoUrl
            .shouldBeNull()
        repository.updatePhotoSettings(id = created.id, customPhotoUrl = null, photoHidden = false)
            .shouldBeRight()
            .photoUrl shouldBe "https://p/new.jpg"
    }

    @Test
    fun `updatePhotoSettings is a NotFound for an unknown user (#303)`() {
        repository.updatePhotoSettings(id = UUID.randomUUID(), customPhotoUrl = null, photoHidden = true)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `updateProfile changes sex when provided`() {
        val created = repository.provision(command = googleSignup()) // sex = Male

        val updated = repository.updateProfile(id = created.id, patch = ProfilePatch(sex = "Female")).shouldBeRight()

        updated.sex shouldBe "Female"
    }

    @Test
    fun `updateProfile reports NotFound for an unknown id`() {
        repository
            .updateProfile(id = UUID.randomUUID(), patch = ProfilePatch(city = "Davao"))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `replaceProfile overwrites all mutable fields, clearing omitted ones`() {
        val created = repository.provision(command = googleSignup()) // sex = Male, city = Manila

        val replaced = repository.replaceProfile(id = created.id, patch = ProfilePatch(city = "Iloilo")).shouldBeRight()

        replaced.city shouldBe "Iloilo"
        replaced.sex.shouldBe(expected = null) // omitted from the replacement → cleared
    }

    @Test
    fun `replaceProfile reports NotFound for an unknown id`() {
        repository
            .replaceProfile(id = UUID.randomUUID(), patch = ProfilePatch(city = "Davao"))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `deactivate soft-deletes the user`() {
        val created = repository.provision(command = googleSignup())

        repository.deactivate(id = created.id).shouldBeRight()

        repository.findById(id = created.id).shouldBeRight().isActive.shouldBeFalse()
        repository.deactivate(id = UUID.randomUUID()).shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `a verified email is globally unique across users`() {
        repository.provision(command = googleSignup(email = "shared@example.com"))

        assertThrows<Exception> {
            repository.provision(command = googleSignup(email = "shared@example.com"))
        }
    }
}
