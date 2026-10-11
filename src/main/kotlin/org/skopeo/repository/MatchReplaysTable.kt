// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.datetime

/**
 * The permanent replay of a live-scored match (#1145, V66): one JSON document per match.
 *
 * Unlike `live_match_events`, which the retention sweep (#939) deletes, this is kept for good. The
 * document's shape is `ReplayDocument`; this table only knows it as JSON and a format version.
 */
internal object MatchReplaysTable : Table(name = "match_replays") {
    val matchId = reference(name = "match_id", foreign = MatchesTable, onDelete = ReferenceOption.CASCADE)
    val formatVersion = integer(name = "format_version")
    val replay = jsonb(name = "replay")
    val createdAt = datetime(name = "created_at")
    val updatedAt = datetime(name = "updated_at")

    override val primaryKey = PrimaryKey(firstColumn = matchId)
}
