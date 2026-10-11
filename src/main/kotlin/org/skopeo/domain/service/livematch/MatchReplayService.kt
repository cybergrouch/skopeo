// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.livematch

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import arrow.core.right
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import org.skopeo.common.contract.ReplayDocument
import org.skopeo.common.contract.ReplayResult
import org.skopeo.common.contract.ReplaySet
import org.skopeo.common.dto.match.MatchReplayBackfillRequest
import org.skopeo.common.dto.match.MatchReplayBackfillResponse
import org.skopeo.common.error.ServiceError
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.entity.livematch.toLoggedAction
import org.skopeo.domain.mapper.entity.match.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.Match
import org.skopeo.domain.model.TeamSide
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.repository.LiveMatchRepository
import org.skopeo.repository.MatchReplayRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.UserRepository
import java.time.Duration
import java.util.UUID

/**
 * The permanent replay of a live-scored match (#1145).
 *
 * Recorded from the scoring log at finalize, and again — if still missing — just before the retention
 * sweep (#939) deletes that log, so a sweep can never lose a replay that was never written. The backfill
 * covers matches finalized before this existed.
 *
 * **Shown only while it is true.** A replay ends at the result it was recorded with; if the match's result
 * is later edited to something else, the replay no longer leads to the score on the page and [byCode]
 * answers 404. It is kept rather than deleted, so editing the result back brings it back.
 */
class MatchReplayService(
    private val replays: MatchReplayRepository = MatchReplayRepository(),
    private val live: LiveMatchRepository = LiveMatchRepository(),
    private val matches: MatchRepository = MatchRepository(),
    private val users: UserRepository = UserRepository(),
    /** The format stored documents must reach. Injected only so a test can exercise an upgrade. */
    private val currentVersion: Int = ReplayBuilder.CURRENT_VERSION,
) {
    /** Record [matchId]'s replay from its scoring log, replacing any earlier one. False when there is nothing to record. */
    fun recordFromLog(matchId: UUID): Boolean {
        val document = fromLog(matchId = matchId) ?: return false
        save(matchId = matchId, document = document)
        return true
    }

    /** [recordFromLog], but only when no replay is stored yet — what the sweep calls before it deletes. */
    fun ensureRecorded(matchId: UUID): Boolean = replays.find(matchId = matchId) != null || recordFromLog(matchId = matchId)

    /**
     * The replay of the match with public [code], for anyone (#1145). Public like the match itself; hidden
     * match history (#622) does not hide it.
     *
     * A [ServiceError.NotFound] when the match has no replay or its result no longer matches the replay's.
     */
    fun byCode(code: String): Either<ServiceError, ReplayDocument> =
        either {
            val notFound = ServiceError.NotFound(message = "Match $code has no replay")
            val match = ensureNotNull(value = matches.findByPublicCode(code = code)?.toDomain()) { notFound }
            val stored = ensureNotNull(value = replays.find(matchId = match.id)) { notFound }
            val document =
                ensureNotNull(value = current(matchId = match.id, version = stored.formatVersion, json = stored.replay)) { notFound }
            ensure(condition = document.result == recordedResultOf(match = match)) { notFound }
            document
        }

    /** Record every missing replay that a log still allows, and upgrade every outdated one. ADMINISTRATOR only. */
    fun backfill(
        token: VerifiedFirebaseToken,
        request: MatchReplayBackfillRequest,
    ): Either<ServiceError, MatchReplayBackfillResponse> =
        either {
            administrator(token = token).bind()
            val recording =
                replays.unrecordedWithLog().associateWith { matchId -> fromLog(matchId = matchId) }
            val upgrading =
                replays.outdated(currentVersion = currentVersion).associateWith { matchId ->
                    replays.find(matchId = matchId)?.let { ReplayBuilder.rebuild(document = decode(json = it.replay)) }
                }
            if (!request.dryRun) {
                (recording + upgrading).forEach { (matchId, document) -> document?.let { save(matchId = matchId, document = it) } }
            }
            val recorded = recording.values.count { it != null }
            val upgraded = upgrading.values.count { it != null }
            val skipped = recording.size - recorded + upgrading.size - upgraded
            if (!request.dryRun) logger.info { "Replay backfill recorded $recorded, upgraded $upgraded, skipped $skipped" }
            MatchReplayBackfillResponse(dryRun = request.dryRun, recorded = recorded, upgraded = upgraded, skipped = skipped)
        }

    /** The stored document in the current format, upgrading it in place when it is older. */
    private fun current(
        matchId: UUID,
        version: Int,
        json: String,
    ): ReplayDocument? {
        val stored = decode(json = json)
        if (version >= currentVersion) return stored
        return ReplayBuilder.rebuild(document = stored)?.also { save(matchId = matchId, document = it) }
    }

    /** The replay [matchId]'s scoring log describes, with times measured from its first surviving action. */
    private fun fromLog(matchId: UUID): ReplayDocument? {
        val rows = live.log(matchId = matchId)
        val recordedAt = rows.associate { it.sequence to it.recordedAt }
        val surviving = ScoreEngine.surviving(log = rows.map { it.toLoggedAction() })
        val origin = surviving.firstOrNull()?.let { recordedAt.getValue(key = it.sequence) } ?: return null
        return ReplayBuilder.build(
            events =
                surviving.map {
                    TimedScoreEvent(
                        event = it.event,
                        atMs = Duration.between(origin, recordedAt.getValue(key = it.sequence)).toMillis(),
                    )
                },
        )
    }

    private fun save(
        matchId: UUID,
        document: ReplayDocument,
    ) {
        replays.save(matchId = matchId, formatVersion = document.version, replay = JSON.encodeToString(value = document))
    }

    private fun decode(json: String): ReplayDocument = JSON.decodeFromString<ReplayDocument>(string = json)

    private fun administrator(token: VerifiedFirebaseToken): Either<ServiceError, Unit> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)?.toDomain()
        return if (caller?.capabilities?.contains(element = Capability.ADMINISTRATOR) == true) {
            Unit.right()
        } else {
            ServiceError.Forbidden().left()
        }
    }

    private val logger = KotlinLogging.logger {}

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

/** [match]'s recorded result in the replay's form, or null when it has none — which no replay equals. */
internal fun recordedResultOf(match: Match): ReplayResult? {
    val winner =
        when (match.winnerTeamId) {
            match.team1.teamId -> TeamSide.TEAM1
            match.team2.teamId -> TeamSide.TEAM2
            else -> null
        } ?: return null
    return ReplayResult(
        sets =
            match.sets.sortedBy { it.setNumber }.map {
                ReplaySet(
                    team1 = it.team1Games,
                    team2 = it.team2Games,
                    tiebreakTeam1 = it.tiebreakTeam1Points,
                    tiebreakTeam2 = it.tiebreakTeam2Points,
                )
            },
        winner = winner.name,
    )
}
