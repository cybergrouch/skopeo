// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.datetime

private const val NAME_MAX = 255
private const val TYPE_MAX = 20

// placement_bracket was widened past TYPE_MAX (see V29) for enum names longer than the original type
// allowed. Its longest surviving value is now CHAMPIONSHIP_FINALS (19), the semi-final brackets having
// been retired in #837. match_type shares the width; its longest surviving value is TOURNAMENT (10) since
// the LEAGUE_PLAY / LEAGUE_PLAYOFFS types were removed (#669). The DB columns stay widened — narrowing
// them would buy nothing and cost a migration.
private const val WIDE_TYPE_MAX = 32
private const val ROUND_MAX = 50
private const val PUBLIC_CODE_MAX = 6

// Per-side handicap (#486): 0 < h <= 1.0 in NTRP points, 3 decimals — NUMERIC(4,3).
private const val HANDICAP_PRECISION = 4
private const val HANDICAP_SCALE = 3

internal object TeamsTable : UUIDTable(name = "teams") {
    val name = varchar(name = "name", length = NAME_MAX)
    val teamType = varchar(name = "team_type", length = TYPE_MAX)
    val isTemporary = bool(name = "is_temporary").default(defaultValue = true)
}

internal object TeamUsersTable : UUIDTable(name = "team_users") {
    val teamId = reference(name = "team_id", foreign = TeamsTable, onDelete = ReferenceOption.CASCADE)
    val userId = reference(name = "user_id", foreign = UsersTable, onDelete = ReferenceOption.CASCADE)
    val position = integer(name = "position").nullable()
}

internal object MatchesTable : UUIDTable(name = "matches") {
    val publicCode = varchar(name = "public_code", length = PUBLIC_CODE_MAX)
    val team1Id = reference(name = "team1_id", foreign = TeamsTable, onDelete = ReferenceOption.RESTRICT)
    val team2Id = reference(name = "team2_id", foreign = TeamsTable, onDelete = ReferenceOption.RESTRICT)
    val winnerTeamId = reference(name = "winner_team_id", foreign = TeamsTable, onDelete = ReferenceOption.RESTRICT).nullable()
    val matchFormat = varchar(name = "match_format", length = TYPE_MAX)
    val matchType = varchar(name = "match_type", length = WIDE_TYPE_MAX)
    val matchDate = date(name = "match_date")
    val venue = varchar(name = "venue", length = NAME_MAX).nullable()
    val tournamentName = varchar(name = "tournament_name", length = NAME_MAX).nullable()
    val matchRound = varchar(name = "match_round", length = ROUND_MAX).nullable()
    val status = varchar(name = "status", length = TYPE_MAX)
    val completedAt = datetime(name = "completed_at").nullable()
    val ratedAt = datetime(name = "rated_at").nullable()
    val ratedBy = reference(name = "rated_by", foreign = UsersTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val createdBy = reference(name = "created_by", foreign = UsersTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val recordedBy = reference(name = "recorded_by", foreign = UsersTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val isActive = bool(name = "is_active").default(defaultValue = true)
    val disabledAt = datetime(name = "disabled_at").nullable()
    val eventId = reference(name = "event_id", foreign = EventsTable, onDelete = ReferenceOption.SET_NULL).nullable()

    // Manual same-date ordering tiebreaker for the rating calculation (#331/#332); null = default.
    val calcSequence = integer(name = "calc_sequence").nullable()

    // Per-side rating handicap (#486) in team-mean NTRP units; null = none. Deducted from the side's
    // rating for the delta calc only; range 0 < h <= 1.0 (CHECK in V21). Editable while unrated.
    val team1Handicap = decimal(name = "team1_handicap", precision = HANDICAP_PRECISION, scale = HANDICAP_SCALE).nullable()
    val team2Handicap = decimal(name = "team2_handicap", precision = HANDICAP_PRECISION, scale = HANDICAP_SCALE).nullable()

    // Tournament placement match (#525): flag + which bracket it decides (CHAMPIONSHIP_FINALS / PLATE_FINALS).
    val isPlacementMatch = bool(name = "is_placement_match").default(defaultValue = false)
    val placementBracket = varchar(name = "placement_bracket", length = WIDE_TYPE_MAX).nullable()

    // Score correction after rating (#776): when the score was last corrected + how many times. Null/0 =
    // never corrected. Drives the public "Re-rated" badge; [ratedAt] stays set through a correction.
    val reRatedAt = datetime(name = "re_rated_at").nullable()
    val reRatedCount = integer(name = "re_rated_count").default(defaultValue = 0)
}

internal object MatchSetsTable : UUIDTable(name = "match_sets") {
    val matchId = reference(name = "match_id", foreign = MatchesTable, onDelete = ReferenceOption.CASCADE)
    val setNumber = integer(name = "set_number")
    val team1Games = integer(name = "team1_games")
    val team2Games = integer(name = "team2_games")
    val winnerTeamId = reference(name = "winner_team_id", foreign = TeamsTable, onDelete = ReferenceOption.RESTRICT)
    val hasTiebreak = bool(name = "has_tiebreak").default(defaultValue = false)
}

internal object MatchSetTiebreaksTable : UUIDTable(name = "match_set_tiebreaks") {
    val matchSetId = reference(name = "match_set_id", foreign = MatchSetsTable, onDelete = ReferenceOption.CASCADE)
    val team1Points = integer(name = "team1_points")
    val team2Points = integer(name = "team2_points")
    val winnerTeamId = reference(name = "winner_team_id", foreign = TeamsTable, onDelete = ReferenceOption.RESTRICT)
}
