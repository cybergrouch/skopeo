// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto.user

import kotlinx.serialization.Serializable
import java.net.URI

/**
 * Request/response shapes for the user-management API.
 *
 * The caller's identity (firebase uid, email, sign-in provider, photo) is taken
 * from the verified Firebase token — never from the request body — so [CreateUserRequest]
 * carries only the profile details the client legitimately supplies.
 */
@Serializable
data class NameDto(
    val id: String,
    val type: String,
    val value: String,
    val isActive: Boolean = true,
)

@Serializable
data class ContactDto(
    val id: String,
    val type: String,
    val value: String,
    val source: String,
    val status: String,
    val method: String? = null,
    val isPrimary: Boolean = false,
    val isActive: Boolean = true,
)

@Serializable
data class IdentityDto(
    val provider: String,
    val providerUid: String,
    val isPrimary: Boolean = false,
)

/**
 * Body for `POST /api/v1/users` — the convergence point of every sign-up flow.
 *
 * [dateOfBirth] (ISO yyyy-MM-dd) and [sex] (Male/Female) are required: every member supplies
 * them at sign-up because they drive tournament-category eligibility. [displayName] is the
 * single name shown in the UI; if omitted, the verified token's name (Google/Facebook) is used.
 */
@Serializable
data class CreateUserRequest(
    val dateOfBirth: String,
    val sex: String,
    val displayName: String? = null,
    val phone: String? = null,
    val city: String? = null,
    val country: String? = null,
    // Self-reported NTRP rating (issue #75) — required at sign-up; a proposal a RATER approves/overrides.
    // Nullable here so a missing value yields a clear validation 400 (see parseProposedRating).
    val proposedRating: String? = null,
)

/**
 * Body for `PUT`/`PATCH /api/v1/users/{id}`. PATCH treats omitted (null) fields as
 * "leave unchanged"; PUT replaces the mutable profile fields wholesale.
 */
@Serializable
data class ProfileRequest(
    // The profile photo is NOT editable here — it is provider-managed and synced on login (#219).
    val dateOfBirth: String? = null,
    val sex: String? = null,
    val city: String? = null,
)

/**
 * Body for `PUT /api/v1/users/{id}/photo` — the profile-photo controls (#303). A blank/absent
 * [customPhotoUrl] clears the custom photo (reverting to the provider photo); a non-blank value must
 * be an http(s) URL. [hidden] suppresses the photo everywhere. Authorized self-or-ADMINISTRATOR.
 */
@Serializable
data class PhotoSettingsRequest(
    val customPhotoUrl: String? = null,
    val hidden: Boolean = false,
) {
    init {
        val trimmed = customPhotoUrl?.trim()
        require(value = trimmed.isNullOrEmpty() || isHttpUrl(value = trimmed)) {
            "customPhotoUrl must be an http(s) URL"
        }
    }
}

/**
 * Body for `PUT /api/v1/users/{id}/match-history-visibility` (#622). [hidden] = true withholds the
 * player's match history from unprivileged viewers on the public profile. Authorized self-or-ADMIN.
 */
@Serializable
data class MatchHistoryVisibilityRequest(
    val hidden: Boolean = false,
)

/** True when [value] parses as an absolute http(s) URL — the only image sources we allow (#303). */
private fun isHttpUrl(value: String): Boolean =
    (value.startsWith(prefix = "http://") || value.startsWith(prefix = "https://")) &&
        runCatching { URI(value).toURL() }.isSuccess

/** Body for `POST /api/v1/users/{canonicalId}/duplicates` — mark these users as duplicates (#124). */
@Serializable
data class MarkDuplicatesRequest(
    val duplicateIds: List<String>,
)

/** Body for `PUT /api/v1/users/me/rating-preview` — the per-admin raw-rating preview toggle (#583). */
@Serializable
data class SetRatingPreviewRequest(
    val previewAsNonAdmin: Boolean,
)

/** Response echoing the persisted per-admin rating-preview toggle (#583). */
@Serializable
data class RatingPreviewResponse(
    val previewAsNonAdmin: Boolean,
)

@Serializable
data class UserResponse(
    val id: String,
    val publicCode: String,
    val firebaseUid: String?,
    // Effective photo to display (respects hide + custom, #303).
    val photoUrl: String?,
    // Raw photo controls, so the owner/admin can prefill the edit form (#303).
    val customPhotoUrl: String? = null,
    val photoHidden: Boolean = false,
    val matchHistoryHidden: Boolean = false,
    val dateOfBirth: String?,
    val sex: String?,
    val city: String?,
    val country: String,
    val kycVerified: Boolean,
    val isActive: Boolean,
    // When set, this user is a disabled duplicate of the referenced canonical account (#124).
    val canonicalUserId: String? = null,
    val names: List<NameDto>,
    val contacts: List<ContactDto>,
    val identities: List<IdentityDto>,
    val capabilities: List<String>,
    // Per-admin "preview ratings as non-admin" toggle state (#583) — backs the Admin-tab control.
    val previewRatingsAsNonAdmin: Boolean = false,
)

/**
 * Slim user shape for search results (player picker, role grants, research) — no contacts.
 * Carries the player's current NTRP [rating] and a computed [age] (raw date of birth is withheld,
 * mirroring the public-profile privacy stance — see issue #64).
 */
@Serializable
data class UserSummaryResponse(
    val id: String,
    val publicCode: String,
    val displayName: String?,
    val photoUrl: String?,
    val sex: String?,
    val age: Int?,
    val rating: PublicRatingDto?,
    val capabilities: List<String>,
    // Decided win–loss record (singles + doubles) — populated for research results (#342), else null.
    val record: WinLossDto? = null,
    // True for a login-less, not-yet-claimed placeholder ("dummy") player (#496/#505): pickers/search
    // render an "Unclaimed" tag beside the name. Real/claimed players leave it false.
    val isPlaceholder: Boolean = false,
    // True for an admin-soft-deleted account (#518): search/Research renders a dominant "Deleted" chip.
    val isDeleted: Boolean = false,
)

/** A player's decided win–loss record (#342): singles + doubles combined; [total] = wins + losses. */
@Serializable
data class WinLossDto(
    val wins: Int,
    val losses: Int,
    val total: Int,
)

/** A page of search results plus the total match count, for numbered pagination (#232). */
@Serializable
data class UserSummaryPageResponse(
    val items: List<UserSummaryResponse>,
    val total: Int,
)
