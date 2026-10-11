// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository.persistence

import java.time.LocalDateTime
import java.util.UUID

/** A row of `match_replays` (#1145). [replay] is the raw JSON document; the service decodes it. */
data class MatchReplayEntity(
    val matchId: UUID,
    val formatVersion: Int,
    val replay: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)
