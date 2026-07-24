// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto.circuit

import kotlinx.serialization.Serializable
import org.skopeo.model.Circuit

/** Body for `POST /api/v1/circuits` — an administrator creates a circuit (#525). */
@Serializable
data class CreateCircuitRequest(
    val name: String,
)

/** Body for `PATCH /api/v1/circuits/{id}` — rename a circuit (#525). */
@Serializable
data class UpdateCircuitRequest(
    val name: String,
)

@Serializable
data class CircuitResponse(
    val id: String,
    val name: String,
    val isActive: Boolean,
)

fun Circuit.toResponse(): CircuitResponse =
    CircuitResponse(
        id = id.toString(),
        name = name,
        isActive = isActive,
    )
