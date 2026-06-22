package org.skopeo.dto.contact

import kotlinx.serialization.Serializable
import org.skopeo.model.Contact

/** Body for `POST /api/v1/users/{userId}/contacts` — add an EMAIL or PHONE. */
@Serializable
data class ContactCreateRequest(
    val type: String,
    val value: String,
    val isPrimary: Boolean = true,
)

/** Body for `PATCH /api/v1/users/{userId}/contacts/{id}` — change the address (resets verification). */
@Serializable
data class ContactUpdateRequest(
    val value: String,
    val isPrimary: Boolean = true,
)

/**
 * Body for `PUT /api/v1/users/{userId}/contacts/{id}/verification` — the ADMINISTRATOR
 * action. [method] defaults to ADMIN_OVERRIDE for a VERIFIED status.
 */
@Serializable
data class VerificationRequest(
    val status: String,
    val method: String? = null,
)

@Serializable
data class ContactResponse(
    val id: String,
    val userId: String,
    val type: String,
    val value: String,
    val source: String,
    val status: String,
    val method: String? = null,
    val isPrimary: Boolean,
    val verifiedAt: String? = null,
    val verifiedBy: String? = null,
)

fun Contact.toResponse(): ContactResponse =
    ContactResponse(
        id = id.toString(),
        userId = userId.toString(),
        type = type.name,
        value = value,
        source = source.name,
        status = status.name,
        method = method?.name,
        isPrimary = isPrimary,
        verifiedAt = verifiedAt?.toString(),
        verifiedBy = verifiedBy?.toString(),
    )
