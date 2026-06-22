package org.skopeo.service.user

import org.skopeo.dto.user.CreateUserRequest
import org.skopeo.dto.user.NameDto
import org.skopeo.dto.user.ProfileRequest
import org.skopeo.model.AuthProvider
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
import java.time.LocalDate
import java.time.format.DateTimeParseException

private val ALLOWED_GENDERS = setOf("M", "F", "Other")

/** Firebase sign-in provider strings → our [AuthProvider]; anything else is treated as a password login. */
internal fun authProviderOf(signInProvider: String?): AuthProvider =
    when (signInProvider) {
        "google.com" -> AuthProvider.GOOGLE
        "facebook.com" -> AuthProvider.FACEBOOK
        else -> AuthProvider.PASSWORD
    }

/** A contact learned from an OAuth provider is sourced to that provider; password sign-ups are MANUAL. */
internal fun contactSourceOf(provider: AuthProvider): ContactSource =
    when (provider) {
        AuthProvider.GOOGLE -> ContactSource.GOOGLE
        AuthProvider.FACEBOOK -> ContactSource.FACEBOOK
        AuthProvider.PASSWORD -> ContactSource.MANUAL
    }

internal fun validatedGender(value: String?): String? {
    if (value == null) return null
    require(value in ALLOWED_GENDERS) { "Invalid gender '$value'; expected one of $ALLOWED_GENDERS" }
    return value
}

internal fun parseDateOfBirth(value: String?): LocalDate? {
    if (value == null) return null
    return try {
        LocalDate.parse(value)
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("Invalid dateOfBirth '$value'; expected ISO-8601 (yyyy-MM-dd)", e)
    }
}

private fun NameDto.toUserName(): UserName =
    UserName(
        type = parseNameType(type),
        value = value,
        isPrimary = isPrimary,
    )

private fun parseNameType(value: String): NameType =
    try {
        NameType.valueOf(value)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Unknown name type '$value'", e)
    }

private fun resolveNames(
    token: VerifiedFirebaseToken,
    request: CreateUserRequest,
): List<UserName> {
    if (request.names.isNotEmpty()) return request.names.map { it.toUserName() }
    // OAuth providers only give a display name; record it as a FULL name.
    return token.name?.let { listOf(UserName(type = NameType.FULL, value = it, isPrimary = true)) } ?: emptyList()
}

/**
 * Merge the verified token (authoritative identity) with the client-supplied profile
 * into a [ProvisionUserCommand]. Throws [IllegalArgumentException] on invalid input
 * (bad gender, malformed date, unknown name type) so the route can answer 400.
 */
internal fun buildProvisionCommand(
    token: VerifiedFirebaseToken,
    request: CreateUserRequest,
): ProvisionUserCommand {
    val provider = authProviderOf(token.signInProvider)
    val email =
        token.email?.let { address ->
            ContactInfo(
                type = ContactType.EMAIL,
                value = address,
                source = contactSourceOf(provider),
                status = if (token.emailVerified) VerificationStatus.VERIFIED else VerificationStatus.PENDING,
                method = if (token.emailVerified) VerificationMethod.OAUTH_PROVIDER else null,
                isPrimary = true,
            )
        }
    val phone =
        request.phone?.let { number ->
            ContactInfo(
                type = ContactType.PHONE,
                value = number,
                source = ContactSource.MANUAL,
                status = VerificationStatus.PENDING,
                isPrimary = true,
            )
        }
    return ProvisionUserCommand(
        firebaseUid = token.uid,
        identity = UserIdentity(provider = provider, providerUid = token.providerUid, isPrimary = true),
        names = resolveNames(token = token, request = request),
        photoUrl = token.picture,
        email = email,
        phone = phone,
        dateOfBirth = parseDateOfBirth(request.dateOfBirth),
        gender = validatedGender(request.gender),
        city = request.city,
        country = request.country,
    )
}

internal fun ProfileRequest.toProfilePatch(): ProfilePatch =
    ProfilePatch(
        photoUrl = photoUrl,
        dateOfBirth = parseDateOfBirth(dateOfBirth),
        gender = validatedGender(gender),
        city = city,
    )
