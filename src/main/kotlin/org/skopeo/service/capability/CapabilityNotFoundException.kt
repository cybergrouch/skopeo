// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.capability

import org.skopeo.model.Capability
import org.skopeo.service.ResourceNotFoundException
import java.util.UUID

/** The user does not currently hold the capability being revoked — routes map this to 404. */
class CapabilityNotFoundException(
    userId: UUID,
    capability: Capability,
) : ResourceNotFoundException("User $userId does not hold an active $capability capability")
