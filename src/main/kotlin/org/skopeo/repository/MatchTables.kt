// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.datetime

private const val NAME_MAX = 255
private const val TYPE_MAX = 20
private const val ROUND_MAX = 50

internal object TeamsTable : UUIDTable("teams") {
    val name = varchar("name", NAME_MAX)
    val teamType = varchar("team_type", TYPE_MAX)
    val isTemporary = bool("is_temporary").default(true)
}

internal object TeamUsersTable : UUIDTable("team_users") {
    val teamId = reference("team_id", TeamsTable, onDelete = ReferenceOption.CASCADE)
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val position = integer("position").nullable()
}

internal object MatchesTable : UUIDTable("matches") {
    val team1Id = reference("team1_id", TeamsTable, onDelete = ReferenceOption.RESTRICT)
    val team2Id = reference("team2_id", TeamsTable, onDelete = ReferenceOption.RESTRICT)
    val winnerTeamId = reference("winner_team_id", TeamsTable, onDelete = ReferenceOption.RESTRICT).nullable()
    val matchType = varchar("match_type", TYPE_MAX)
    val matchFormat = varchar("match_format", TYPE_MAX)
    val matchDate = date("match_date")
    val venue = varchar("venue", NAME_MAX).nullable()
    val tournamentName = varchar("tournament_name", NAME_MAX).nullable()
    val matchRound = varchar("match_round", ROUND_MAX).nullable()
    val status = varchar("status", TYPE_MAX)
    val completedAt = datetime("completed_at").nullable()
    val ratedAt = datetime("rated_at").nullable()
    val ratedBy = reference("rated_by", UsersTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val createdBy = reference("created_by", UsersTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val recordedBy = reference("recorded_by", UsersTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val isActive = bool("is_active").default(true)
    val disabledAt = datetime("disabled_at").nullable()
}

internal object MatchSetsTable : UUIDTable("match_sets") {
    val matchId = reference("match_id", MatchesTable, onDelete = ReferenceOption.CASCADE)
    val setNumber = integer("set_number")
    val team1Games = integer("team1_games")
    val team2Games = integer("team2_games")
    val winnerTeamId = reference("winner_team_id", TeamsTable, onDelete = ReferenceOption.RESTRICT)
    val hasTiebreak = bool("has_tiebreak").default(false)
}

internal object MatchSetTiebreaksTable : UUIDTable("match_set_tiebreaks") {
    val matchSetId = reference("match_set_id", MatchSetsTable, onDelete = ReferenceOption.CASCADE)
    val team1Points = integer("team1_points")
    val team2Points = integer("team2_points")
    val winnerTeamId = reference("winner_team_id", TeamsTable, onDelete = ReferenceOption.RESTRICT)
}
