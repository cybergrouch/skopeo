// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.mapper.dto.circuit

import org.skopeo.common.dto.circuit.CircuitResponse
import org.skopeo.domain.model.Circuit

fun Circuit.toResponse(): CircuitResponse =
    CircuitResponse(
        id = id.toString(),
        name = name,
        isActive = isActive,
    )
