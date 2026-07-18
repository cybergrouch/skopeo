// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import org.jetbrains.exposed.sql.ISqlExpressionBuilder
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inSubQuery
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.skopeo.model.CreateFixtureCommand
import org.skopeo.model.Match
import org.skopeo.model.MatchPublicRef
import org.skopeo.model.MatchSetResult
import org.skopeo.model.MatchSide
import org.skopeo.model.MatchStatus
import org.skopeo.model.MatchType
import org.skopeo.model.ServiceError
import org.skopeo.model.TeamType
import org.skopeo.model.WeightClass
import org.skopeo.model.WeightClassCounts
import org.skopeo.model.WinLossRecord
import org.skopeo.model.weightClass
import java.time.LocalDateTime
import java.util.UUID

// The confidence evaluation window (#459): completed matches within this many days count toward sparsity.
private const val CONFIDENCE_WINDOW_DAYS = 30L

/**
 * Persistence for the match aggregate (teams + team_users + matches + match_sets + tiebreaks).
 * Matches are append-only: created as fixtures, completed with results, then rated separately.
 */
class MatchRepository {
    fun createFixture(command: CreateFixtureCommand): Match =
        transaction {
            val team1 = createTeam(name = command.team1Name, type = command.matchFormat, userIds = command.team1UserIds)
            val team2 = createTeam(name = command.team2Name, type = command.matchFormat, userIds = command.team2UserIds)
            val matchId =
                MatchesTable.insertAndGetId {
                    it[publicCode] = generateUniqueMatchCode()
                    it[team1Id] = team1
                    it[team2Id] = team2
                    it[matchFormat] = command.matchFormat.name
                    it[matchType] = command.matchType.name
                    it[matchDate] = command.matchDate
                    it[status] = MatchStatus.SCHEDULED.name
                    it[venue] = command.venue
                    it[tournamentName] = command.tournamentName
                    it[createdBy] = command.createdBy
                    it[eventId] = command.eventId
                    it[designatedPoints] = command.designatedPoints
                }.value
            loadMatchOrThrow(id = matchId)
        }

    /** Record results on a fixture: persist sets/tiebreaks, set the winner, mark COMPLETED. */
    fun addResult(
        matchId: UUID,
        sets: List<MatchSetResult>,
        winnerTeamId: UUID,
        recordedBy: UUID,
        completedAt: LocalDateTime,
    ): Either<ServiceError, Match> =
        transaction {
            if (loadMatch(id = matchId) == null) {
                return@transaction ServiceError.NotFound(message = "Match $matchId not found").left()
            }
            // Replace any existing sets so re-recording (an edit while unrated) overwrites rather than
            // appends; tiebreak rows cascade-delete with their set.
            MatchSetsTable.deleteWhere { MatchSetsTable.matchId eq matchId }
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
            loadMatchOrThrow(id = matchId).right()
        }

    fun setActive(
        matchId: UUID,
        active: Boolean,
        disabledAt: LocalDateTime?,
    ): Either<ServiceError, Match> =
        transaction {
            val updated =
                MatchesTable.update(where = { MatchesTable.id eq matchId }) {
                    it[isActive] = active
                    it[MatchesTable.disabledAt] = disabledAt
                }
            if (updated == 0) {
                ServiceError.NotFound(message = "Match $matchId not found").left()
            } else {
                loadMatchOrThrow(id = matchId).right()
            }
        }

    /**
     * Set the manual calculation-order tiebreaker (#331/#332): assign calc_sequence = 0,1,2,… to
     * [matchIds] in the given order, in one transaction. The service validates the set first.
     */
    fun reorderCalcSequence(matchIds: List<UUID>) {
        transaction {
            matchIds.forEachIndexed { index, matchId ->
                MatchesTable.update(where = { MatchesTable.id eq matchId }) { it[calcSequence] = index }
            }
        }
    }

    fun findById(matchId: UUID): Either<ServiceError, Match> =
        transaction {
            val match = loadMatch(id = matchId)
            if (match == null) ServiceError.NotFound(message = "Match $matchId not found").left() else match.right()
        }

    /**
     * Resolve a match by its shareable public code (#136); null if absent. Disabled (soft-deleted)
     * matches still resolve (#325): they feed historical rating calculations, and deletion never
     * touches ratings, so their links stay honored for traceability — the page flags them as deleted.
     */
    fun findByPublicCode(code: String): Match? =
        transaction {
            MatchesTable
                .selectAll()
                .where { MatchesTable.publicCode eq code }
                .singleOrNull()
                ?.let { row -> buildMatch(id = row[MatchesTable.id].value, row = row) }
        }

    /** Every active match in an event (#138), newest match date first — for the public event page. */
    fun listByEvent(eventId: UUID): List<Match> =
        transaction {
            MatchesTable
                .selectAll()
                .where { MatchesTable.isActive and (MatchesTable.eventId eq eventId) }
                .orderBy(MatchesTable.matchDate to SortOrder.DESC)
                .map { loadMatch(id = it[MatchesTable.id].value)!! }
        }

    /**
     * Public codes for a set of match ids (#136) — used to resolve match audit targets to a link.
     * Returns code + date for found, active-or-not matches; missing ids are simply absent.
     */
    fun publicRefsByIds(ids: List<UUID>): Map<UUID, MatchPublicRef> =
        if (ids.isEmpty()) {
            emptyMap()
        } else {
            transaction {
                MatchesTable
                    .selectAll()
                    .where { MatchesTable.id inList ids }
                    .associate {
                        it[MatchesTable.id].value to
                            MatchPublicRef(
                                matchId = it[MatchesTable.id].value,
                                publicCode = it[MatchesTable.publicCode],
                                matchDate = it[MatchesTable.matchDate],
                            )
                    }
            }
        }

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
     * Active, completed, not-yet-rated matches in calculation (processing) order (#335). Matches are
     * grouped by event and the groups ordered by each event's *processing key* — its [Event.calcPriority]
     * override if set, else its end date (as an epoch-day number); an eventless ("Open") match keys off
     * its own match date, so it interleaves among the events. Within a group the #331/#332 order holds
     * (match date, then calc_sequence, then completion time). When [createdBy] is non-null the list is
     * scoped to fixtures that user created (HOST oversight).
     */
    fun listPendingCalculation(
        createdBy: UUID? = null,
        eventId: UUID? = null,
    ): List<Match> =
        transaction {
            val matches =
                MatchesTable
                    .selectAll()
                    .where {
                        val base =
                            MatchesTable.isActive and
                                (MatchesTable.status eq MatchStatus.COMPLETED.name) and
                                MatchesTable.ratedAt.isNull()
                        // The rating-queue eligibility (#403): a completed, unrated match queues only if
                        // it is event-less (queues immediately, as before) OR its event is finalized. An
                        // explicit event scope is a pre-finalize organizer preview, so it lists the event's
                        // recorded fixtures without the finalize gate.
                        when {
                            eventId != null -> base and (MatchesTable.eventId eq eventId)
                            createdBy != null -> base and (MatchesTable.createdBy eq createdBy) and queueEligible()
                            else -> base and queueEligible()
                        }
                    }.map { loadMatch(id = it[MatchesTable.id].value)!! }
            sortForCalculation(matches = matches)
        }

    /**
     * The rating-queue eligibility clause (#403): a match qualifies when it is event-less (event_id is
     * null → queues immediately, unchanged) OR its event is finalized (finalized_at is non-null). Built
     * as a membership test against the ids of finalized events, so the queue trigger for an evented match
     * moves from result-upload time to finalize time.
     */
    private fun ISqlExpressionBuilder.queueEligible(): Op<Boolean> =
        MatchesTable.eventId.isNull() or
            (
                MatchesTable.eventId inSubQuery
                    EventsTable.select(columns = listOf(element = EventsTable.id)).where { EventsTable.finalizedAt.isNotNull() }
            )

    /** Each event's processing key: its calc_priority override, else its end date as an epoch day. */
    private fun eventProcessingKeys(eventIds: List<UUID>): Map<UUID, Double> =
        if (eventIds.isEmpty()) {
            emptyMap()
        } else {
            EventsTable
                .selectAll()
                .where { EventsTable.id inList eventIds }
                .associate { row ->
                    row[EventsTable.id].value to
                        (row[EventsTable.calcPriority] ?: row[EventsTable.endDate].toEpochDay().toDouble())
                }
        }

    /** Order matches into the global calculation sequence (#335) — see [listPendingCalculation]. */
    private fun sortForCalculation(matches: List<Match>): List<Match> {
        val keyByEvent = eventProcessingKeys(eventIds = matches.mapNotNull { it.eventId }.distinct())

        // Processing key: an evented match keys off its event; an eventless one off its own match date.
        fun processingKey(match: Match): Double =
            match.eventId?.let { keyByEvent.getValue(key = it) } ?: match.matchDate.toEpochDay().toDouble()
        return matches.sortedWith(
            comparator =
                compareBy(
                    { processingKey(match = it) },
                    // Keep a single event's matches contiguous when two events share a key.
                    { it.eventId?.toString().orEmpty() },
                    { it.matchDate },
                    // An un-dragged match (null calc_sequence) sorts after dragged ones within its date.
                    { it.calcSequence ?: Int.MAX_VALUE },
                    { it.completedAt },
                    { it.id.toString() },
                ),
        )
    }

    /**
     * All of an event's active, completed fixtures — rated or not (#138). Lets the event page keep a
     * rated match on view as a read-only record alongside the recorded-but-unrated ones.
     */
    fun listResultsByEvent(eventId: UUID): List<Match> =
        transaction {
            MatchesTable
                .selectAll()
                .where {
                    MatchesTable.isActive and
                        (MatchesTable.status eq MatchStatus.COMPLETED.name) and
                        (MatchesTable.eventId eq eventId)
                }.orderBy(
                    MatchesTable.matchDate to SortOrder.ASC,
                    MatchesTable.calcSequence to SortOrder.ASC_NULLS_LAST,
                    MatchesTable.completedAt to SortOrder.ASC,
                    MatchesTable.id to SortOrder.ASC,
                )
                .map { loadMatch(id = it[MatchesTable.id].value)!! }
        }

    /**
     * Active fixtures still scheduled and awaiting result entry, regardless of match date.
     * The schedule is only suggestive — a fixture can be played anytime, so it is eligible for
     * results as soon as it exists. When [createdBy] is non-null, scoped to fixtures that user
     * created (HOST oversight). Ordered oldest match date first so overdue fixtures surface on top.
     */
    fun listAwaitingResults(
        createdBy: UUID? = null,
        eventId: UUID? = null,
    ): List<Match> =
        transaction {
            MatchesTable
                .selectAll()
                .where {
                    val base =
                        MatchesTable.isActive and
                            (MatchesTable.status eq MatchStatus.SCHEDULED.name)
                    // An event scope shows every awaiting fixture in the event (any creator); otherwise
                    // a HOST is scoped to their own fixtures.
                    when {
                        eventId != null -> base and (MatchesTable.eventId eq eventId)
                        createdBy != null -> base and (MatchesTable.createdBy eq createdBy)
                        else -> base
                    }
                }.orderBy(
                    MatchesTable.matchDate to SortOrder.ASC,
                    MatchesTable.calcSequence to SortOrder.ASC_NULLS_LAST,
                    MatchesTable.completedAt to SortOrder.ASC,
                    MatchesTable.id to SortOrder.ASC,
                )
                .map { loadMatch(id = it[MatchesTable.id].value)!! }
        }

    /**
     * Every match the user took part in (either side), newest match date first — the basis for their
     * match history. Includes scheduled, completed, and rated fixtures alike, and — for traceability
     * (#325) — soft-deleted ones too: a match feeds historical rating calculations, so it stays in the
     * record even once disabled (the history row flags it as deleted).
     */
    fun listByUser(userId: UUID): List<Match> =
        transaction {
            val teamIds = teamIdsOf(userId = userId)
            if (teamIds.isEmpty()) {
                emptyList()
            } else {
                MatchesTable
                    .selectAll()
                    .where {
                        (MatchesTable.team1Id inList teamIds) or (MatchesTable.team2Id inList teamIds)
                    }.orderBy(MatchesTable.matchDate to SortOrder.DESC)
                    .map { loadMatch(id = it[MatchesTable.id].value)!! }
            }
        }

    /**
     * Decided win–loss records for many users at once (#342), aggregated across singles and doubles.
     * Counts every match with a recorded winner that a user played in (soft-deleted matches included,
     * mirroring [listByUser]); a user sits on exactly one team per match, so each is one win or loss.
     * Users with no decided matches are absent. Own matches only — a canonical account's merged
     * duplicates (#124) are not folded in here, keeping this cheap for a whole search-result page.
     */
    fun winLossByUsers(userIds: List<UUID>): Map<UUID, WinLossRecord> =
        transaction {
            if (userIds.isEmpty()) {
                return@transaction emptyMap()
            }
            // team id → the queried users on that team (doubles partners may both be in the query).
            val usersByTeam =
                TeamUsersTable
                    .selectAll()
                    .where { TeamUsersTable.userId inList userIds }
                    .groupBy(
                        keySelector = { it[TeamUsersTable.teamId].value },
                        valueTransform = { it[TeamUsersTable.userId].value },
                    )
            if (usersByTeam.isEmpty()) {
                return@transaction emptyMap()
            }
            val teamIds = usersByTeam.keys.toList()
            val outcomes =
                MatchesTable
                    .selectAll()
                    .where {
                        ((MatchesTable.team1Id inList teamIds) or (MatchesTable.team2Id inList teamIds)) and
                            MatchesTable.winnerTeamId.isNotNull()
                    }.flatMap { row ->
                        // Guaranteed non-null by the winnerTeamId.isNotNull() filter in the WHERE clause above.
                        val winnerTeam = checkNotNull(value = row[MatchesTable.winnerTeamId]).value
                        val team1 = row[MatchesTable.team1Id].value
                        val team2 = row[MatchesTable.team2Id].value
                        val loserTeam = if (winnerTeam == team1) team2 else team1
                        usersByTeam[winnerTeam].orEmpty().map { it to true } +
                            usersByTeam[loserTeam].orEmpty().map { it to false }
                    }
            outcomes
                .groupBy(keySelector = { it.first }, valueTransform = { it.second })
                .mapValues { (_, results) -> WinLossRecord(wins = results.count { it }, losses = results.count { !it }) }
        }

    /**
     * A single player's COMPLETED matches in the confidence window (#459), split by weight class —
     * the input to the sparsity confidence in `confidenceAt`. Counts every match the user took part
     * in (either team) whose [MatchesTable.matchDate] falls in the last [CONFIDENCE_WINDOW_DAYS] up to
     * [asOf] (inclusive lower bound: a match exactly 30 days back still counts). A one-user shortcut
     * over the batched [weightedMatchCountsInWindow] below.
     */
    fun weightedMatchCountsInWindow(
        userId: UUID,
        asOf: LocalDateTime,
    ): WeightClassCounts = weightedMatchCountsInWindow(userIds = listOf(element = userId), asOf = asOf)[userId] ?: WeightClassCounts()

    /**
     * Windowed weight-class counts (#459) for many players at once — the batched form that backs the
     * list views (standings, seeding, match/history pages) so a page of N players costs **one** query,
     * never N (no N+1). One grouped scan over completed, in-window matches the queried users played in
     * (either team), tallied per (user, weight class). Users with no qualifying matches are absent.
     */
    fun weightedMatchCountsInWindow(
        userIds: List<UUID>,
        asOf: LocalDateTime,
    ): Map<UUID, WeightClassCounts> =
        transaction {
            if (userIds.isEmpty()) {
                return@transaction emptyMap()
            }
            val windowStart = asOf.toLocalDate().minusDays(CONFIDENCE_WINDOW_DAYS)
            val today = asOf.toLocalDate()
            // The queried users' team memberships: team id → the queried users on that team (doubles
            // partners may both be in the query). One scan feeds the single match query below.
            val usersByTeam =
                TeamUsersTable
                    .select(columns = listOf(TeamUsersTable.teamId, TeamUsersTable.userId))
                    .where { TeamUsersTable.userId inList userIds }
                    .groupBy(
                        keySelector = { it[TeamUsersTable.teamId].value },
                        valueTransform = { it[TeamUsersTable.userId].value },
                    )
            if (usersByTeam.isEmpty()) {
                return@transaction emptyMap()
            }
            val teamIds = usersByTeam.keys.toList()
            // One grouped scan over the completed, in-window matches on any of those teams; fan each
            // match out to its queried participants and tally per (user, weight class).
            val counts = mutableMapOf<UUID, MutableMap<WeightClass, Int>>()
            MatchesTable
                .select(columns = listOf(MatchesTable.team1Id, MatchesTable.team2Id, MatchesTable.matchType))
                .where {
                    ((MatchesTable.team1Id inList teamIds) or (MatchesTable.team2Id inList teamIds)) and
                        (MatchesTable.status eq MatchStatus.COMPLETED.name) and
                        (MatchesTable.matchDate greaterEq windowStart) and
                        (MatchesTable.matchDate lessEq today)
                }.forEach { row ->
                    val weightClass = MatchType.valueOf(value = row[MatchesTable.matchType]).weightClass()
                    val participants =
                        usersByTeam[row[MatchesTable.team1Id].value].orEmpty() +
                            usersByTeam[row[MatchesTable.team2Id].value].orEmpty()
                    // A user sits on exactly one side per match; distinct guards a degenerate self-vs-self row.
                    participants.distinct().forEach { uid ->
                        val perUser = counts.getOrPut(key = uid) { mutableMapOf() }
                        perUser[weightClass] = (perUser[weightClass] ?: 0) + 1
                    }
                }
            counts.mapValues { (_, byClass) ->
                WeightClassCounts(
                    tournaments = byClass[WeightClass.TOURNAMENT] ?: 0,
                    leagues = byClass[WeightClass.LEAGUE] ?: 0,
                    openPlays = byClass[WeightClass.OPEN_PLAY] ?: 0,
                )
            }
        }

    /**
     * Active matches between exactly these two players (head-to-head, #188), newest match date first.
     * Singles-oriented: a match counts when one side is [userIdA] and the other is [userIdB] (either
     * way round). Empty when either player has no matches.
     */
    fun listBetweenUsers(
        userIdA: UUID,
        userIdB: UUID,
    ): List<Match> =
        transaction {
            val teamsA = teamIdsOf(userId = userIdA)
            val teamsB = teamIdsOf(userId = userIdB)
            if (teamsA.isEmpty() || teamsB.isEmpty()) {
                emptyList()
            } else {
                MatchesTable
                    .selectAll()
                    .where {
                        // Soft-deleted meetings still count for traceability (#325); ratings are frozen.
                        ((MatchesTable.team1Id inList teamsA) and (MatchesTable.team2Id inList teamsB)) or
                            ((MatchesTable.team1Id inList teamsB) and (MatchesTable.team2Id inList teamsA))
                    }.orderBy(MatchesTable.matchDate to SortOrder.DESC)
                    .map { loadMatch(id = it[MatchesTable.id].value)!! }
            }
        }

    /** The (temporary singles) team ids a user has ever played in — the join basis for match lookups. */
    private fun teamIdsOf(userId: UUID): List<UUID> =
        TeamUsersTable
            .selectAll()
            .where { TeamUsersTable.userId eq userId }
            .map { it[TeamUsersTable.teamId].value }

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

    private fun loadMatch(id: UUID): Match? =
        MatchesTable.selectAll().where { MatchesTable.id eq id }.singleOrNull()?.let { row -> buildMatch(id = id, row = row) }
}

/** Reload a match that is known to exist (e.g. just inserted/updated) — no caller-side null branch. */
private fun loadMatchOrThrow(id: UUID): Match = buildMatch(id = id, row = MatchesTable.selectAll().where { MatchesTable.id eq id }.single())

/** A unique shareable match code (#136), retrying on the rare collision. Must run in a transaction. */
private fun generateUniqueMatchCode(): String =
    PublicCode.generate { code -> MatchesTable.selectAll().where { MatchesTable.publicCode eq code }.any() }

private fun buildMatch(
    id: UUID,
    row: ResultRow,
): Match =
    Match(
        id = id,
        publicCode = row[MatchesTable.publicCode],
        matchFormat = TeamType.valueOf(value = row[MatchesTable.matchFormat]),
        matchType = MatchType.valueOf(value = row[MatchesTable.matchType]),
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
        eventId = row[MatchesTable.eventId]?.value,
        calcSequence = row[MatchesTable.calcSequence],
        designatedPoints = row[MatchesTable.designatedPoints],
    )

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
