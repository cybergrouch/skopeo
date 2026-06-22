package org.skopeo.dto.name

import kotlinx.serialization.Serializable
import org.skopeo.model.Name

/** Body for `POST /api/v1/users/{userId}/names` — add a name (type DISPLAY replaces the display name). */
@Serializable
data class NameCreateRequest(
    val type: String,
    val value: String,
)

/** Body for `PUT /api/v1/users/{userId}/names/{id}/state` — enable or disable a name. */
@Serializable
data class NameStateRequest(
    val isActive: Boolean,
)

@Serializable
data class NameResponse(
    val id: String,
    val userId: String,
    val type: String,
    val value: String,
    val isActive: Boolean,
    val disabledAt: String? = null,
)

fun Name.toResponse(): NameResponse =
    NameResponse(
        id = id.toString(),
        userId = userId.toString(),
        type = type.name,
        value = value,
        isActive = isActive,
        disabledAt = disabledAt?.toString(),
    )
