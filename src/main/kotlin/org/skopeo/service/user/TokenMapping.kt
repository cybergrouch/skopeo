// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.user

import org.skopeo.dto.user.CreateUserRequest
import org.skopeo.dto.user.ProfileRequest
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
import java.time.LocalDate
import java.time.format.DateTimeParseException

private val ALLOWED_SEXES = setOf("Male", "Female")

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

internal fun validatedSex(value: String?): String? {
    if (value == null) return null
    require(value = value in ALLOWED_SEXES) { "Invalid sex '$value'; expected one of $ALLOWED_SEXES" }
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

/**
 * The display name at sign-up comes from the request (manual input) or, failing that, the
 * verified token's name (Google/Facebook). A user must have one.
 */
private fun displayName(
    token: VerifiedFirebaseToken,
    request: CreateUserRequest,
): List<UserName> {
    // Treat a blank/whitespace value as absent (the web sends null, but a direct API
    // call could send "" or "  "); fall back to the provider's token name, else reject.
    val value =
        request.displayName?.trim()?.ifBlank { null }
            ?: token.name?.trim()?.ifBlank { null }
            ?: throw IllegalArgumentException("A display name is required")
    return listOf(UserName(type = NameType.DISPLAY, value = value))
}

/**
 * Merge the verified token (authoritative identity) with the client-supplied profile
 * into a [ProvisionUserCommand]. Throws [IllegalArgumentException] on invalid input
 * (missing display name, bad sex, malformed date) so the route can answer 400.
 */
internal fun buildProvisionCommand(
    token: VerifiedFirebaseToken,
    request: CreateUserRequest,
    adminEmails: Set<String> = emptySet(),
): ProvisionUserCommand {
    val provider = authProviderOf(signInProvider = token.signInProvider)
    val email =
        token.email?.let { address ->
            ContactInfo(
                type = ContactType.EMAIL,
                value = address,
                source = contactSourceOf(provider = provider),
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
        names = displayName(token = token, request = request),
        photoUrl = token.picture,
        email = email,
        phone = phone,
        dateOfBirth = parseDateOfBirth(value = request.dateOfBirth),
        sex = validatedSex(value = request.sex),
        city = request.city,
        country = request.country,
        capabilities =
            if (isBootstrapAdmin(token = token, adminEmails = adminEmails)) {
                setOf(Capability.PLAYER, Capability.ADMINISTRATOR)
            } else {
                setOf(element = Capability.PLAYER)
            },
    )
}

/**
 * Whether this sign-in should be promoted to ADMINISTRATOR via the bootstrap allowlist.
 * The verified-email gate is the security crux: a manual email/password account is NOT
 * email-verified at sign-up, so it can only become admin after verifying the address —
 * otherwise anyone could register an allowlisted email and self-promote. See
 * docs/engineering/architecture/ADMIN_BOOTSTRAP.md.
 */
internal fun isBootstrapAdmin(
    token: VerifiedFirebaseToken,
    adminEmails: Set<String>,
): Boolean = token.emailVerified && token.email?.trim()?.lowercase() in adminEmails

internal fun ProfileRequest.toProfilePatch(): ProfilePatch =
    ProfilePatch(
        photoUrl = photoUrl,
        dateOfBirth = parseDateOfBirth(value = dateOfBirth),
        sex = validatedSex(value = sex),
        city = city,
    )
