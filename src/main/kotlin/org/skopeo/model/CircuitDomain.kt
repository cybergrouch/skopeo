// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

import java.util.UUID

/**
 * A circuit (#525): an administrator-defined grouping of tournaments (e.g. NORTH, SOUTH). Tournament
 * events reference exactly one circuit; circuits are flexible (admins create/rename/retire them) and
 * seeded with NORTH and SOUTH.
 */
data class Circuit(
    val id: UUID,
    val name: String,
    val isActive: Boolean = true,
    val createdBy: UUID? = null,
)

/** Everything needed to create a circuit. */
data class CreateCircuitCommand(
    val name: String,
    val createdBy: UUID,
)
