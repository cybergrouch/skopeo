package org.skopeo.dto.capability

import kotlinx.serialization.Serializable
import org.skopeo.model.CapabilityGrant

/** Body for `POST /api/v1/users/{userId}/capabilities` — grant a role. */
@Serializable
data class CapabilityGrantRequest(
    val capability: String,
)

@Serializable
data class CapabilityResponse(
    val capability: String,
    val isActive: Boolean,
    val grantedBy: String? = null,
    val grantedAt: String? = null,
    val revokedBy: String? = null,
    val revokedAt: String? = null,
)

fun CapabilityGrant.toResponse(): CapabilityResponse =
    CapabilityResponse(
        capability = capability.name,
        isActive = isActive,
        grantedBy = grantedBy?.toString(),
        grantedAt = grantedAt?.toString(),
        revokedBy = revokedBy?.toString(),
        revokedAt = revokedAt?.toString(),
    )
