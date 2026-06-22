package org.skopeo.service.user

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.skopeo.dto.user.CreateUserRequest
import org.skopeo.dto.user.NameDto
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
        authProviderOf("google.com") shouldBe AuthProvider.GOOGLE
        authProviderOf("facebook.com") shouldBe AuthProvider.FACEBOOK
        authProviderOf("password") shouldBe AuthProvider.PASSWORD
        authProviderOf(null) shouldBe AuthProvider.PASSWORD
    }

    @Test
    fun `maps provider to contact source`() {
        contactSourceOf(AuthProvider.GOOGLE) shouldBe ContactSource.GOOGLE
        contactSourceOf(AuthProvider.FACEBOOK) shouldBe ContactSource.FACEBOOK
        contactSourceOf(AuthProvider.PASSWORD) shouldBe ContactSource.MANUAL
    }

    @Test
    fun `validates gender`() {
        validatedGender("M") shouldBe "M"
        validatedGender(null).shouldBeNull()
        shouldThrow<IllegalArgumentException> { validatedGender("X") }
    }

    @Test
    fun `parses date of birth`() {
        parseDateOfBirth("1990-05-01") shouldBe LocalDate.of(1990, 5, 1)
        parseDateOfBirth(null).shouldBeNull()
        shouldThrow<IllegalArgumentException> { parseDateOfBirth("not-a-date") }
    }

    @Test
    fun `verified google email becomes a VERIFIED OAUTH contact`() {
        val command =
            buildProvisionCommand(
                token = token(email = "a@example.com", emailVerified = true, name = "Ana", signInProvider = "google.com"),
                request = CreateUserRequest(),
            )

        command.identity.provider shouldBe AuthProvider.GOOGLE
        command.email!!.let {
            it.type shouldBe ContactType.EMAIL
            it.source shouldBe ContactSource.GOOGLE
            it.status shouldBe VerificationStatus.VERIFIED
            it.method shouldBe VerificationMethod.OAUTH_PROVIDER
        }
        // No structured names supplied → the token display name is stored as a FULL name.
        command.names.single().let {
            it.type shouldBe NameType.FULL
            it.value shouldBe "Ana"
        }
    }

    @Test
    fun `unverified email is PENDING with no method`() {
        val command =
            buildProvisionCommand(
                token = token(email = "b@example.com", emailVerified = false),
                request = CreateUserRequest(),
            )

        command.email!!.status shouldBe VerificationStatus.PENDING
        command.email!!.method.shouldBeNull()
    }

    @Test
    fun `request names and phone are carried through`() {
        val command =
            buildProvisionCommand(
                token = token(name = "ignored-display-name"),
                request =
                    CreateUserRequest(
                        names = listOf(NameDto(type = "FIRST", value = "Juan", isPrimary = true)),
                        phone = "+639170000000",
                    ),
            )

        command.names.single().type shouldBe NameType.FIRST
        command.phone!!.let {
            it.type shouldBe ContactType.PHONE
            it.source shouldBe ContactSource.MANUAL
            it.status shouldBe VerificationStatus.PENDING
        }
    }

    @Test
    fun `rejects invalid name type, gender, and date`() {
        shouldThrow<IllegalArgumentException> {
            buildProvisionCommand(token = token(), request = CreateUserRequest(names = listOf(NameDto(type = "BOGUS", value = "x"))))
        }
        shouldThrow<IllegalArgumentException> {
            buildProvisionCommand(token = token(), request = CreateUserRequest(gender = "X"))
        }
        shouldThrow<IllegalArgumentException> {
            buildProvisionCommand(token = token(), request = CreateUserRequest(dateOfBirth = "31-12-1990"))
        }
    }

    @Test
    fun `toProfilePatch parses and validates`() {
        val patch = ProfileRequest(photoUrl = "p", dateOfBirth = "2000-01-01", gender = "F", city = "Cebu").toProfilePatch()
        patch.dateOfBirth shouldBe LocalDate.of(2000, 1, 1)
        patch.gender shouldBe "F"
        shouldThrow<IllegalArgumentException> { ProfileRequest(gender = "Z").toProfilePatch() }
    }
}
