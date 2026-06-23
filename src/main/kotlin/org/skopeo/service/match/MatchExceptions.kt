// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.match

import org.skopeo.service.ConflictException
import org.skopeo.service.ResourceNotFoundException
import java.util.UUID

/** No such match — routes map this to 404. */
class MatchNotFoundException(
    id: UUID,
) : ResourceNotFoundException("Match $id not found")

/** A match-state rule was violated (e.g. results already uploaded, disabling a rated match) — 409. */
class MatchConflictException(
    message: String,
) : ConflictException(message)
