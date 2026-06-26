// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.skopeo.model.CreateFixtureCommand
import org.skopeo.model.Match
import org.skopeo.model.MatchFormat
import org.skopeo.model.MatchSetResult
import org.skopeo.model.MatchSide
import org.skopeo.model.MatchStatus
import org.skopeo.model.TeamType
import java.time.LocalDateTime
import java.util.UUID

/**
 * Persistence for the match aggregate (teams + team_users + matches + match_sets + tiebreaks).
 * Matches are append-only: created as fixtures, completed with results, then rated separately.
 */
class MatchRepository {
    fun createFixture(command: CreateFixtureCommand): Match =
        transaction {
            val team1 = createTeam(name = command.team1Name, type = command.matchType, userIds = command.team1UserIds)
            val team2 = createTeam(name = command.team2Name, type = command.matchType, userIds = command.team2UserIds)
            val matchId =
                MatchesTable.insertAndGetId {
                    it[team1Id] = team1
                    it[team2Id] = team2
                    it[matchType] = command.matchType.name
                    it[matchFormat] = command.matchFormat.name
                    it[matchDate] = command.matchDate
                    it[status] = MatchStatus.SCHEDULED.name
                    it[venue] = command.venue
                    it[tournamentName] = command.tournamentName
                    it[createdBy] = command.createdBy
                }.value
            loadMatch(id = matchId)!!
        }

    /** Record results on a fixture: persist sets/tiebreaks, set the winner, mark COMPLETED. */
    fun addResult(
        matchId: UUID,
        sets: List<MatchSetResult>,
        winnerTeamId: UUID,
        recordedBy: UUID,
        completedAt: LocalDateTime,
    ): Match? =
        transaction {
            if (loadMatch(id = matchId) == null) return@transaction null
            sets.forEach { set ->
                val hasTb = set.tiebreakTeam1Points != null && set.tiebreakTeam2Points != null
                val setId =
                    MatchSetsTable.insertAndGetId {
                        it[MatchSetsTable.matchId] = matchId
                        it[setNumber] = set.setNumber
                        it[team1Games] = set.team1Games
                        it[team2Games] = set.team2Games
                        it[MatchSetsTable.winnerTeamId] = set.winnerTeamId
                        it[hasTiebreak] = hasTb
                    }.value
                if (hasTb) {
                    MatchSetTiebreaksTable.insert {
                        it[matchSetId] = setId
                        it[team1Points] = set.tiebreakTeam1Points!!
                        it[team2Points] = set.tiebreakTeam2Points!!
                        it[MatchSetTiebreaksTable.winnerTeamId] = set.winnerTeamId
                    }
                }
            }
            MatchesTable.update(where = { MatchesTable.id eq matchId }) {
                it[status] = MatchStatus.COMPLETED.name
                it[MatchesTable.winnerTeamId] = winnerTeamId
                it[MatchesTable.completedAt] = completedAt
                it[MatchesTable.recordedBy] = recordedBy
            }
            loadMatch(id = matchId)
        }

    fun setActive(
        matchId: UUID,
        active: Boolean,
        disabledAt: LocalDateTime?,
    ): Match? =
        transaction {
            val updated =
                MatchesTable.update(where = { MatchesTable.id eq matchId }) {
                    it[isActive] = active
                    it[MatchesTable.disabledAt] = disabledAt
                }
            if (updated == 0) null else loadMatch(id = matchId)
        }

    fun findById(matchId: UUID): Match? = transaction { loadMatch(id = matchId) }

    /** Stamp a match as rating-calculated (the calculation trigger committing it). */
    fun markRated(
        matchId: UUID,
        ratedAt: LocalDateTime,
        ratedBy: UUID,
    ) {
        transaction {
            MatchesTable.update(where = { MatchesTable.id eq matchId }) {
                it[MatchesTable.ratedAt] = ratedAt
                it[MatchesTable.ratedBy] = ratedBy
            }
        }
    }

    /**
     * Active, completed, not-yet-rated matches, oldest completion first (calculation order).
     * When [createdBy] is non-null, scoped to fixtures that user created (HOST oversight).
     */
    fun listPendingCalculation(createdBy: UUID? = null): List<Match> =
        transaction {
            MatchesTable
                .selectAll()
                .where {
                    val base =
                        MatchesTable.isActive and
                            (MatchesTable.status eq MatchStatus.COMPLETED.name) and
                            MatchesTable.ratedAt.isNull()
                    if (createdBy != null) base and (MatchesTable.createdBy eq createdBy) else base
                }.orderBy(MatchesTable.completedAt to SortOrder.ASC)
                .map { loadMatch(id = it[MatchesTable.id].value)!! }
        }

    /**
     * Active fixtures still scheduled and awaiting result entry, regardless of match date.
     * The schedule is only suggestive — a fixture can be played anytime, so it is eligible for
     * results as soon as it exists. When [createdBy] is non-null, scoped to fixtures that user
     * created (HOST oversight). Ordered oldest match date first so overdue fixtures surface on top.
     */
    fun listAwaitingResults(createdBy: UUID? = null): List<Match> =
        transaction {
            MatchesTable
                .selectAll()
                .where {
                    val base =
                        MatchesTable.isActive and
                            (MatchesTable.status eq MatchStatus.SCHEDULED.name)
                    if (createdBy != null) base and (MatchesTable.createdBy eq createdBy) else base
                }.orderBy(MatchesTable.matchDate to SortOrder.ASC)
                .map { loadMatch(id = it[MatchesTable.id].value)!! }
        }

    private fun createTeam(
        name: String,
        type: TeamType,
        userIds: List<UUID>,
    ): UUID {
        val teamId =
            TeamsTable.insertAndGetId {
                it[TeamsTable.name] = name
                it[teamType] = type.name
                it[isTemporary] = true
            }.value
        userIds.forEachIndexed { index, uid ->
            TeamUsersTable.insert {
                it[TeamUsersTable.teamId] = teamId
                it[userId] = uid
                it[position] = index + 1
            }
        }
        return teamId
    }

    private fun loadMatch(id: UUID): Match? {
        val row = MatchesTable.selectAll().where { MatchesTable.id eq id }.singleOrNull() ?: return null
        return Match(
            id = id,
            matchType = TeamType.valueOf(value = row[MatchesTable.matchType]),
            matchFormat = MatchFormat.valueOf(value = row[MatchesTable.matchFormat]),
            matchDate = row[MatchesTable.matchDate],
            status = MatchStatus.valueOf(value = row[MatchesTable.status]),
            team1 = sideOf(teamId = row[MatchesTable.team1Id].value),
            team2 = sideOf(teamId = row[MatchesTable.team2Id].value),
            winnerTeamId = row[MatchesTable.winnerTeamId]?.value,
            sets = setsOf(matchId = id),
            venue = row[MatchesTable.venue],
            tournamentName = row[MatchesTable.tournamentName],
            isActive = row[MatchesTable.isActive],
            completedAt = row[MatchesTable.completedAt],
            ratedAt = row[MatchesTable.ratedAt],
            createdBy = row[MatchesTable.createdBy]?.value,
            recordedBy = row[MatchesTable.recordedBy]?.value,
        )
    }
}

private fun sideOf(teamId: UUID): MatchSide =
    MatchSide(
        teamId = teamId,
        userIds =
            TeamUsersTable
                .selectAll()
                .where { TeamUsersTable.teamId eq teamId }
                .orderBy(TeamUsersTable.position to SortOrder.ASC)
                .map { it[TeamUsersTable.userId].value },
    )

private fun setsOf(matchId: UUID): List<MatchSetResult> =
    MatchSetsTable
        .selectAll()
        .where { MatchSetsTable.matchId eq matchId }
        .orderBy(MatchSetsTable.setNumber to SortOrder.ASC)
        .map { setRow ->
            val setId = setRow[MatchSetsTable.id].value
            val tb = MatchSetTiebreaksTable.selectAll().where { MatchSetTiebreaksTable.matchSetId eq setId }.singleOrNull()
            MatchSetResult(
                setNumber = setRow[MatchSetsTable.setNumber],
                team1Games = setRow[MatchSetsTable.team1Games],
                team2Games = setRow[MatchSetsTable.team2Games],
                winnerTeamId = setRow[MatchSetsTable.winnerTeamId].value,
                tiebreakTeam1Points = tb?.get(expression = MatchSetTiebreaksTable.team1Points),
                tiebreakTeam2Points = tb?.get(expression = MatchSetTiebreaksTable.team2Points),
            )
        }
