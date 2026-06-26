// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.util.UUID

/** Authorization roles granted to a user (broad for now; devolvable to fine-grained capabilities later). */
enum class Capability { PLAYER, HOST, CLUB_OWNER, ADMINISTRATOR }

/**
 * An append-only grant of a [Capability] to a user. A grant is active until revoked; the
 * audit fields record who granted/revoked it and when. Re-granting after a revoke is a new row.
 */
data class CapabilityGrant(
    val id: UUID,
    val userId: UUID,
    val capability: Capability,
    val isActive: Boolean = true,
    val grantedBy: UUID? = null,
    val grantedAt: LocalDateTime? = null,
    val revokedBy: UUID? = null,
    val revokedAt: LocalDateTime? = null,
)

/**
 * Name variants a user may carry (Filipino nicknames vs legal names; KYC matching).
 * DISPLAY is the single active name shown in the UI — see [Name].
 */
enum class NameType { FIRST, MIDDLE, LAST, SUFFIX, NICKNAME, PREFERRED, FULL, GOVERNMENT, DISPLAY }

enum class ContactType { EMAIL, PHONE }

/** Where a contact came from — drives the initial verification state. */
enum class ContactSource { GOOGLE, FACEBOOK, MANUAL }

enum class VerificationStatus { PENDING, VERIFIED, FAILED }

enum class VerificationMethod { OAUTH_PROVIDER, EMAIL_LINK, SMS_OTP, WHATSAPP_OTP, VIBER_OTP, ADMIN_OVERRIDE }

/** Authentication provider a user signs in with (brokered by Firebase). */
enum class AuthProvider { GOOGLE, FACEBOOK, PASSWORD }

/** A name to be written (provisioning input); identity is assigned by the database. */
data class UserName(
    val type: NameType,
    val value: String,
)

/**
 * A name as stored — the addressable sub-resource. Values are immutable: a name is disabled
 * ([isActive] = false) rather than edited, and a new one added, so the table keeps the full
 * history of a profile's names. The display name is the single active name of type DISPLAY.
 */
data class Name(
    val id: UUID,
    val userId: UUID,
    val type: NameType,
    val value: String,
    val isActive: Boolean = true,
    val disabledAt: LocalDateTime? = null,
)

/** A contact to be written (provisioning input); identity is assigned by the database. */
data class ContactInfo(
    val type: ContactType,
    val value: String,
    val source: ContactSource,
    val status: VerificationStatus,
    val method: VerificationMethod? = null,
    val isPrimary: Boolean = false,
)

/**
 * A contact as stored — the addressable sub-resource. Values are immutable: a contact is
 * disabled ([isActive] = false) rather than edited, and a new one is added, so the table
 * keeps the full history of a profile's contacts.
 */
data class Contact(
    val id: UUID,
    val userId: UUID,
    val type: ContactType,
    val value: String,
    val source: ContactSource,
    val status: VerificationStatus,
    val method: VerificationMethod? = null,
    val isPrimary: Boolean = false,
    val isActive: Boolean = true,
    val verifiedAt: LocalDateTime? = null,
    val verifiedBy: UUID? = null,
    val disabledAt: LocalDateTime? = null,
)

data class UserIdentity(
    val provider: AuthProvider,
    val providerUid: String,
    val isPrimary: Boolean = false,
)

/** The user aggregate as stored across users / user_names / contact_information / user_identities / user_capabilities. */
data class User(
    val id: UUID,
    // Short, human-readable, shareable player code (e.g. "K7Q2MX"); unique. See issue #56.
    val publicCode: String,
    val firebaseUid: String?,
    val photoUrl: String?,
    val dateOfBirth: LocalDate?,
    val sex: String?,
    val city: String?,
    val country: String,
    val kycVerified: Boolean,
    val isActive: Boolean,
    // Optional self-reported NTRP rating from sign-up (issue #75) — a proposal for an admin to
    // approve/override, not an authoritative rating; the user stays pending until one is set.
    val proposedRating: BigDecimal? = null,
    val names: List<Name>,
    val contacts: List<Contact>,
    val identities: List<UserIdentity>,
    val capabilities: Set<Capability>,
)

/** The user's single active display name, if any (names include disabled ones). */
fun User.displayName(): String? = names.firstOrNull { it.type == NameType.DISPLAY && it.isActive }?.value

/** Whole years between [dateOfBirth] and [asOf]. */
fun ageInYears(
    dateOfBirth: LocalDate,
    asOf: LocalDate,
): Int = Period.between(dateOfBirth, asOf).years

/** Everything needed to provision a new user in one transaction (e.g. on first sign-in). */
data class ProvisionUserCommand(
    val firebaseUid: String,
    val identity: UserIdentity,
    val names: List<UserName>,
    val photoUrl: String? = null,
    val email: ContactInfo? = null,
    val phone: ContactInfo? = null,
    val dateOfBirth: LocalDate? = null,
    val sex: String? = null,
    val city: String? = null,
    val country: String? = null,
    val proposedRating: BigDecimal? = null,
    val capabilities: Set<Capability> = setOf(Capability.PLAYER),
)

/** Partial update of mutable profile fields; null means "leave unchanged". */
data class ProfilePatch(
    val photoUrl: String? = null,
    val dateOfBirth: LocalDate? = null,
    val sex: String? = null,
    val city: String? = null,
)
