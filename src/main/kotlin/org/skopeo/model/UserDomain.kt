package org.skopeo.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/** Authorization roles granted to a user (broad for now; devolvable to fine-grained capabilities later). */
enum class Capability { PLAYER, HOST, CLUB_OWNER, ADMINISTRATOR }

/** Name variants a user may carry (Filipino nicknames vs legal names; KYC matching). */
enum class NameType { FIRST, MIDDLE, LAST, SUFFIX, NICKNAME, PREFERRED, FULL, GOVERNMENT }

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
    val isPrimary: Boolean = false,
)

/**
 * A name as stored — the addressable sub-resource. Values are immutable: a name is disabled
 * ([isActive] = false) rather than edited, and a new one added, so the table keeps the full
 * history of a profile's names. [isPrimary] marks the single active display name.
 */
data class Name(
    val id: UUID,
    val userId: UUID,
    val type: NameType,
    val value: String,
    val isPrimary: Boolean = false,
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
    val firebaseUid: String?,
    val photoUrl: String?,
    val dateOfBirth: LocalDate?,
    val gender: String?,
    val city: String?,
    val country: String,
    val kycVerified: Boolean,
    val isActive: Boolean,
    val names: List<Name>,
    val contacts: List<Contact>,
    val identities: List<UserIdentity>,
    val capabilities: Set<Capability>,
)

/** Everything needed to provision a new user in one transaction (e.g. on first sign-in). */
data class ProvisionUserCommand(
    val firebaseUid: String,
    val identity: UserIdentity,
    val names: List<UserName>,
    val photoUrl: String? = null,
    val email: ContactInfo? = null,
    val phone: ContactInfo? = null,
    val dateOfBirth: LocalDate? = null,
    val gender: String? = null,
    val city: String? = null,
    val country: String? = null,
    val capabilities: Set<Capability> = setOf(Capability.PLAYER),
)

/** Partial update of mutable profile fields; null means "leave unchanged". */
data class ProfilePatch(
    val photoUrl: String? = null,
    val dateOfBirth: LocalDate? = null,
    val gender: String? = null,
    val city: String? = null,
)
