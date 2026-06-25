// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.user

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.skopeo.dto.user.CreateUserRequest
import org.skopeo.dto.user.ProfileRequest
import org.skopeo.model.AuthProvider
import org.skopeo.model.ContactSource
import org.skopeo.model.ContactType
import org.skopeo.model.NameType
import org.skopeo.model.VerificationMethod
import org.skopeo.model.VerificationStatus
import java.time.LocalDate
import kotlin.test.Test

class TokenMappingTest {
    private fun token(
        uid: String = "uid-1",
        email: String? = null,
        emailVerified: Boolean = false,
        name: String? = null,
        signInProvider: String? = "password",
    ) = VerifiedFirebaseToken(
        uid = uid,
        email = email,
        emailVerified = emailVerified,
        name = name,
        picture = "https://example.com/p.jpg",
        signInProvider = signInProvider,
        providerUid = uid,
    )

    @Test
    fun `maps sign-in providers`() {
        authProviderOf(signInProvider = "google.com") shouldBe AuthProvider.GOOGLE
        authProviderOf(signInProvider = "facebook.com") shouldBe AuthProvider.FACEBOOK
        authProviderOf(signInProvider = "password") shouldBe AuthProvider.PASSWORD
        authProviderOf(signInProvider = null) shouldBe AuthProvider.PASSWORD
    }

    @Test
    fun `maps provider to contact source`() {
        contactSourceOf(provider = AuthProvider.GOOGLE) shouldBe ContactSource.GOOGLE
        contactSourceOf(provider = AuthProvider.FACEBOOK) shouldBe ContactSource.FACEBOOK
        contactSourceOf(provider = AuthProvider.PASSWORD) shouldBe ContactSource.MANUAL
    }

    @Test
    fun `validates sex`() {
        validatedSex(value = "Male") shouldBe "Male"
        validatedSex(value = null).shouldBeNull()
        shouldThrow<IllegalArgumentException> { validatedSex(value = "X") }
    }

    @Test
    fun `parses date of birth`() {
        parseDateOfBirth(value = "1990-05-01") shouldBe LocalDate.of(1990, 5, 1)
        parseDateOfBirth(value = null).shouldBeNull()
        shouldThrow<IllegalArgumentException> { parseDateOfBirth(value = "not-a-date") }
    }

    @Test
    fun `verified google email becomes a VERIFIED OAUTH contact`() {
        val command =
            buildProvisionCommand(
                token = token(email = "a@example.com", emailVerified = true, name = "Ana", signInProvider = "google.com"),
                request = CreateUserRequest(dateOfBirth = "2000-01-01", sex = "Male"),
            )

        command.identity.provider shouldBe AuthProvider.GOOGLE
        command.email!!.let {
            it.type shouldBe ContactType.EMAIL
            it.source shouldBe ContactSource.GOOGLE
            it.status shouldBe VerificationStatus.VERIFIED
            it.method shouldBe VerificationMethod.OAUTH_PROVIDER
        }
        // No display name supplied → the token name becomes the single DISPLAY name.
        command.names.single().let {
            it.type shouldBe NameType.DISPLAY
            it.value shouldBe "Ana"
        }
    }

    @Test
    fun `unverified email is PENDING with no method`() {
        val command =
            buildProvisionCommand(
                token = token(email = "b@example.com", emailVerified = false, name = "Bea"),
                request = CreateUserRequest(dateOfBirth = "2000-01-01", sex = "Male"),
            )

        command.email!!.status shouldBe VerificationStatus.PENDING
        command.email!!.method.shouldBeNull()
    }

    @Test
    fun `the request display name overrides the token name, and phone is carried through`() {
        val command =
            buildProvisionCommand(
                token = token(name = "from-provider"),
                request = CreateUserRequest(displayName = "Juan", phone = "+639170000000", dateOfBirth = "2000-01-01", sex = "Male"),
            )

        command.names.single().let {
            it.type shouldBe NameType.DISPLAY
            it.value shouldBe "Juan"
        }
        command.phone!!.let {
            it.type shouldBe ContactType.PHONE
            it.source shouldBe ContactSource.MANUAL
            it.status shouldBe VerificationStatus.PENDING
        }
    }

    @Test
    fun `a surrounding-whitespace display name is trimmed`() {
        val command =
            buildProvisionCommand(
                token = token(name = "from-provider"),
                request = CreateUserRequest(displayName = "  Juan  ", dateOfBirth = "2000-01-01", sex = "Male"),
            )

        command.names.single().value shouldBe "Juan"
    }

    @Test
    fun `a blank display name falls back to the token name`() {
        val command =
            buildProvisionCommand(
                token = token(name = "from-provider"),
                request = CreateUserRequest(displayName = "   ", dateOfBirth = "2000-01-01", sex = "Male"),
            )

        command.names.single().value shouldBe "from-provider"
    }

    @Test
    fun `a blank display name with no token name is rejected`() {
        shouldThrow<IllegalArgumentException> {
            buildProvisionCommand(
                token = token(name = null),
                request = CreateUserRequest(displayName = "   ", dateOfBirth = "2000-01-01", sex = "Male"),
            )
        }
    }

    @Test
    fun `rejects a missing display name, bad sex, and bad date`() {
        // No request display name and no token name.
        shouldThrow<IllegalArgumentException> {
            buildProvisionCommand(token = token(name = null), request = CreateUserRequest(dateOfBirth = "2000-01-01", sex = "Male"))
        }
        shouldThrow<IllegalArgumentException> {
            buildProvisionCommand(token = token(), request = CreateUserRequest(displayName = "Juan", sex = "X", dateOfBirth = "2000-01-01"))
        }
        shouldThrow<IllegalArgumentException> {
            buildProvisionCommand(
                token = token(),
                request = CreateUserRequest(displayName = "Juan", dateOfBirth = "31-12-1990", sex = "Male"),
            )
        }
    }

    @Test
    fun `toProfilePatch parses and validates`() {
        val patch = ProfileRequest(photoUrl = "p", dateOfBirth = "2000-01-01", sex = "Female", city = "Cebu").toProfilePatch()
        patch.dateOfBirth shouldBe LocalDate.of(2000, 1, 1)
        patch.sex shouldBe "Female"
        shouldThrow<IllegalArgumentException> { ProfileRequest(sex = "Z").toProfilePatch() }
    }
}
