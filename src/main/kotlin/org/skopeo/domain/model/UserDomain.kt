// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.model

import org.skopeo.common.redaction.Redactable
import org.skopeo.common.security.Capability
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.util.UUID

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
    val value: Redactable<String>,
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
    val value: Redactable<String>,
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
    // The effective profile photo to display (#303) — derived from the fields below via
    // [effectivePhotoUrl]: null when hidden, else the custom URL, else the provider photo.
    val photoUrl: String?,
    // Raw photo state (#303). [providerPhotoUrl] is the OAuth photo (synced on login, retained for
    // revert); [customPhotoUrl] is the user-set override; [photoHidden] suppresses display.
    val providerPhotoUrl: String? = null,
    val customPhotoUrl: String? = null,
    val photoHidden: Boolean = false,
    // When true, the player's match history is withheld from unprivileged viewers on the public
    // profile (#622); the owner and elevated roles still see it. Default false = visible to all.
    val matchHistoryHidden: Boolean = false,
    val dateOfBirth: LocalDate?,
    val sex: String?,
    val city: String?,
    val country: String,
    val kycVerified: Boolean,
    val isActive: Boolean,
    // Optional self-reported NTRP rating from sign-up (issue #75) — a proposal for an admin to
    // approve/override, not an authoritative rating; the user stays pending until one is set.
    val proposedRating: BigDecimal? = null,
    // When set, this user is a disabled duplicate of the referenced canonical ("true") account (#124).
    val canonicalUserId: UUID? = null,
    // Login-less placeholder ("dummy") player (#496): a user matches can be logged against before the
    // real person signs up. PLAYER-only, no login; [claimedAt]/[claimedBy] are set when it is adopted.
    val placeholder: Boolean = false,
    val claimedAt: LocalDateTime? = null,
    val claimedBy: UUID? = null,
    // Per-admin "preview as non-admin" toggle (#583): when true, this admin sees the band-only rating
    // view (no raw NTRP value). Default false; only meaningful for administrators.
    val previewRatingsAsNonAdmin: Boolean = false,
    val names: List<Name>,
    val contacts: List<Contact>,
    val identities: List<UserIdentity>,
    val capabilities: Set<Capability>,
)

/**
 * The derived login/link status of an account (#643): which login anchor the account currently has,
 * or [NONE] for a login-less placeholder. Computed (never stored) from [User.firebaseUid] plus the
 * primary identity's provider — see [User.linkStatus]. Surfaced read-only in the account-management UI
 * so an admin can see, before an irreversible merge, which account carries the accessible login.
 */
enum class AccountLinkStatus { GOOGLE, FACEBOOK, PASSWORD, NONE }

/**
 * Derive the account's [AccountLinkStatus] (#643) from the login anchor + identities: [NONE] when there
 * is no login (firebase_uid null — a placeholder or freed account), otherwise the primary identity's
 * provider (falling back to any identity, then to PASSWORD for a login with no recorded identity row).
 */
fun User.linkStatus(): AccountLinkStatus =
    if (firebaseUid == null) {
        AccountLinkStatus.NONE
    } else {
        when (identities.firstOrNull { it.isPrimary }?.provider ?: identities.firstOrNull()?.provider) {
            AuthProvider.GOOGLE -> AccountLinkStatus.GOOGLE
            AuthProvider.FACEBOOK -> AccountLinkStatus.FACEBOOK
            AuthProvider.PASSWORD, null -> AccountLinkStatus.PASSWORD
        }
    }

/**
 * The outcome of an admin account-merge (#643): the count of each kind of participation/membership record
 * re-pointed from the retired account onto the survivor, plus whether the retired account's login was
 * transferred to the survivor. Rating history + ranking points are intentionally NOT moved (the survivor
 * keeps its own), so they carry no count here. Used for the audit trail and for verifying the merge.
 */
data class AccountMergeResult(
    val teamMemberships: Int,
    val eventParticipations: Int,
    val playerListMemberships: Int,
    val clubOwnerships: Int,
    val seedingEntries: Int,
    val loginTransferred: Boolean,
)

/**
 * The profile photo to actually show (#303): nothing when hidden, otherwise the user's custom URL
 * if set, otherwise the OAuth-provider photo. Centralized so every read site (and the login sync)
 * derives the displayed photo identically.
 */
fun effectivePhotoUrl(
    providerPhotoUrl: String?,
    customPhotoUrl: String?,
    photoHidden: Boolean,
): String? = if (photoHidden) null else customPhotoUrl ?: providerPhotoUrl

/**
 * Whether this viewer may see the raw NTRP rating value (full precision), #583. ADMINISTRATOR only,
 * and never while that admin has the per-admin "preview as non-admin" toggle on. Everyone else sees
 * the band + confidence + speedometer only. Anonymous/unresolved viewers get false at the call site.
 */
fun User.canSeeRawRating(): Boolean = capabilities.contains(element = Capability.ADMINISTRATOR) && !previewRatingsAsNonAdmin

/** Null-safe [canSeeRawRating] for optional/anonymous callers: no caller ⇒ band only. */
fun User?.canSeeRawRatingOrFalse(): Boolean = this?.canSeeRawRating() == true

/** The user's single active first name, if any (private — never shown on the public profile). */
fun User.firstName(): String? = names.firstOrNull { it.type == NameType.FIRST && it.isActive }?.value

/** The user's single active last name, if any (private — never shown on the public profile). */
fun User.lastName(): String? = names.firstOrNull { it.type == NameType.LAST && it.isActive }?.value

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
    val dateOfBirth: LocalDate? = null,
    val sex: String? = null,
    val city: String? = null,
)

/**
 * Everything needed to create a login-less placeholder ("dummy") player (#496) in one transaction: a
 * users row with [firebaseUid] = NULL, [placeholder] = true, an auto public code, a DISPLAY name, and
 * the PLAYER capability only. [displayName] and [sex] are required; [dateOfBirth] is optional.
 */
data class CreatePlaceholderCommand(
    val displayName: String,
    val sex: String,
    val dateOfBirth: LocalDate? = null,
)

/** Lifecycle of a placeholder claim code (#496): ACTIVE (usable) or CONSUMED (claimed or superseded). */
enum class ClaimCodeStatus { ACTIVE, CONSUMED }

/**
 * A backend-generated secret claim code for a placeholder account (#496). Only the [codeHash] is
 * stored — the plaintext is returned once on generation and never persisted. One-time-use and
 * expiring: a claim is admitted only by an ACTIVE code whose hash matches and which is not past
 * [expiresAt].
 */
data class ClaimCode(
    val id: UUID,
    val placeholderUserId: UUID,
    val codeHash: String,
    val expiresAt: LocalDateTime,
    val status: ClaimCodeStatus,
    val createdBy: UUID? = null,
    val createdAt: LocalDateTime,
    val consumedAt: LocalDateTime? = null,
    val consumedBy: UUID? = null,
) {
    /** True when the code still admits a claim: ACTIVE and not past its expiry. */
    fun isUsable(asOf: LocalDateTime): Boolean = status == ClaimCodeStatus.ACTIVE && expiresAt.isAfter(asOf)
}

/**
 * The one-time result of generating a claim code (#496): the [plaintext] to hand off (shown once,
 * never re-derivable) plus the stored [code] record. Only the plaintext leaves the service to the admin.
 */
data class GeneratedClaimCode(
    // A bearer credential: whoever holds it can adopt the placeholder account, so it is shown once and
    // stored only as a hash. Same treatment as an API key (#822).
    val plaintext: Redactable<String>,
    val code: ClaimCode,
    // The placeholder's shareable public code, so the caller can render which account the code adopts.
    val placeholderPublicCode: String,
)
