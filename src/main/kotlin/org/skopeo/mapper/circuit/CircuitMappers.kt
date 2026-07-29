// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.mapper.circuit

import org.skopeo.dto.circuit.CircuitResponse
import org.skopeo.model.Circuit

fun Circuit.toResponse(): CircuitResponse =
    CircuitResponse(
        id = id.toString(),
        name = name,
        isActive = isActive,
    )
