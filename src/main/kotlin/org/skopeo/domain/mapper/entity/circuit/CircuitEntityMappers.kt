// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.mapper.entity.circuit

import org.skopeo.domain.model.Circuit
import org.skopeo.repository.persistence.CircuitEntity

/**
 * Entity→domain mapper (#633): builds the domain [Circuit] from the raw persistence [CircuitEntity] the
 * repository returns. A flat aggregate, so this is a field-for-field copy. Lives in `mapper.entity` (which
 * may depend on both `persistence` and `model`); the service calls it, since `repository ↛ mapper`.
 */
fun CircuitEntity.toDomain(): Circuit =
    Circuit(
        id = id,
        name = name,
        isActive = isActive,
        createdBy = createdBy,
    )
