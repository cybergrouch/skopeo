// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.livematch

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import io.github.oshai.kotlinlogging.KotlinLogging
import org.skopeo.common.dto.livematch.LiveMatchSweepRequest
import org.skopeo.common.dto.livematch.LiveMatchSweepResponse
import org.skopeo.common.error.ServiceError
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.entity.match.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.repository.LiveMatchRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.UserRepository
import org.skopeo.repository.prunableMatches
import java.time.LocalDateTime

/**
 * Prune the scoring logs of matches that are over (#939).
 *
 * `live_match_events` is **working state**, not a record: the match is the record, and the log exists
 * only as an input for computing a score (`LIVE_MATCH.md` §8a). Finalize deliberately keeps it, so a
 * mis-finalized match can still be inspected — which means something else has to remove it eventually,
 * or the table grows by a few hundred rows per scored match forever.
 *
 * **This must never be pointed at `audit_log`.** That table is the record itself; deleting from it is
 * not tidying up but deciding to stop being able to answer "who did what". The reasoning, and the
 * measurements showing there is nothing to solve there anyway, are in #939.
 *
 * Two safeguards, both deliberate:
 *
 * - **Age is never sufficient.** The match must have a recorded result. A match suspended for weather
 *   may resume days later (#930), and sweeping it would delete a live session mid-match.
 * - **Dry run by default**, matching the rating and standings triggers. Only an explicit
 *   `dryRun = false` deletes.
 */
class LiveMatchSweepService(
    private val live: LiveMatchRepository = LiveMatchRepository(),
    private val matches: MatchRepository = MatchRepository(),
    private val users: UserRepository = UserRepository(),
    private val broadcast: LiveScoreBroadcaster = NoOpLiveScoreBroadcaster,
    private val clock: () -> LocalDateTime = LocalDateTime::now,
) {
    fun sweep(
        token: VerifiedFirebaseToken,
        request: LiveMatchSweepRequest,
    ): Either<ServiceError, LiveMatchSweepResponse> =
        either {
            administrator(token = token).bind()
            val days = (request.retentionDays ?: DEFAULT_RETENTION_DAYS).coerceAtLeast(minimumValue = 1)
            val cutoff = clock().minusDays(days.toLong())
            val prunable = live.prunableMatches(before = cutoff)

            var rows = 0
            if (!request.dryRun) {
                prunable.forEach { matchId ->
                    rows += live.log(matchId = matchId).size
                    // The whole document goes, which also clears any field since removed from the
                    // payload (matchId #938, serverId #943) on a document nobody has rewritten.
                    matches.findById(matchId = matchId).getOrNull()?.toDomain()?.let {
                        broadcast.discard(publicCode = it.publicCode)
                    }
                    // Safe because the umpire credit was folded into match_umpires at finalize (#929):
                    // disposal loses the keystrokes, never the attribution.
                    live.discardLog(matchId = matchId)
                }
                logger.info { "Live-score sweep removed $rows rows across ${prunable.size} matches" }
            }

            LiveMatchSweepResponse(
                dryRun = request.dryRun,
                retentionDays = days,
                prunedMatches = prunable.size,
                prunedRows = rows,
            )
        }

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
        /**
         * How long a finished match's log is kept, in days.
         *
         * **91 days, chosen to match the default award-validity window.** A disputed result is worth
         * inspecting for as long as the ranking points it produced are still live, and tying the two
         * together means the number has a reason rather than being a round figure someone liked. If
         * the award window ever changes, this should follow it.
         */
        const val DEFAULT_RETENTION_DAYS = 91
    }
}
