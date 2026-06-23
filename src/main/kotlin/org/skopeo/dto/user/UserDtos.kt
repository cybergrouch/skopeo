// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto.user

import kotlinx.serialization.Serializable
import org.skopeo.model.NameType
import org.skopeo.model.User

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
 * [displayName] is the single name shown in the UI; if omitted, the verified token's name
 * (from Google/Facebook) is used. Structured names (FIRST/LAST/nicknames) are added later
 * via the names API.
 */
@Serializable
data class CreateUserRequest(
    val displayName: String? = null,
    val phone: String? = null,
    val dateOfBirth: String? = null,
    val sex: String? = null,
    val city: String? = null,
    val country: String? = null,
)

/**
 * Body for `PUT`/`PATCH /api/v1/users/{id}`. PATCH treats omitted (null) fields as
 * "leave unchanged"; PUT replaces the mutable profile fields wholesale.
 */
@Serializable
data class ProfileRequest(
    val photoUrl: String? = null,
    val dateOfBirth: String? = null,
    val sex: String? = null,
    val city: String? = null,
)

@Serializable
data class UserResponse(
    val id: String,
    val firebaseUid: String?,
    val photoUrl: String?,
    val dateOfBirth: String?,
    val sex: String?,
    val city: String?,
    val country: String,
    val kycVerified: Boolean,
    val isActive: Boolean,
    val names: List<NameDto>,
    val contacts: List<ContactDto>,
    val identities: List<IdentityDto>,
    val capabilities: List<String>,
)

fun User.toResponse(): UserResponse =
    UserResponse(
        id = id.toString(),
        firebaseUid = firebaseUid,
        photoUrl = photoUrl,
        dateOfBirth = dateOfBirth?.toString(),
        sex = sex,
        city = city,
        country = country,
        kycVerified = kycVerified,
        isActive = isActive,
        names =
            names.map {
                NameDto(
                    id = it.id.toString(),
                    type = it.type.name,
                    value = it.value,
                    isActive = it.isActive,
                )
            },
        contacts =
            contacts.map {
                ContactDto(
                    id = it.id.toString(),
                    type = it.type.name,
                    value = it.value,
                    source = it.source.name,
                    status = it.status.name,
                    method = it.method?.name,
                    isPrimary = it.isPrimary,
                    isActive = it.isActive,
                )
            },
        identities =
            identities.map {
                IdentityDto(provider = it.provider.name, providerUid = it.providerUid, isPrimary = it.isPrimary)
            },
        capabilities = capabilities.map { it.name }.sorted(),
    )

/** Slim user shape for search results (player picker, role grants) — no contacts/identities. */
@Serializable
data class UserSummaryResponse(
    val id: String,
    val displayName: String?,
    val capabilities: List<String>,
)

fun User.toSummary(): UserSummaryResponse =
    UserSummaryResponse(
        id = id.toString(),
        displayName = names.firstOrNull { it.type == NameType.DISPLAY && it.isActive }?.value,
        capabilities = capabilities.map { it.name }.sorted(),
    )
