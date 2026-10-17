// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.livematch

import org.skopeo.common.contract.ReplayScorer
import org.skopeo.common.dto.match.MatchPublicPlayer
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.User
import org.skopeo.domain.service.user.displayName
import org.skopeo.domain.service.user.isDeleted
import org.skopeo.repository.LiveMatchRepository
import org.skopeo.repository.UserRepository
import java.util.UUID

/**
 * Who scored a live-scored match, as the public surfaces show it (#1183).
 *
 * **Public by decision, like players** — a display name and a public code, never a user id. The display
 * rules are the player ones: a placeholder is flagged, and a deleted account shows as deleted and never
 * by name, so every name here is null for a deleted scorer.
 *
 * "The scorer" in a headline is the **last** one: whoever recorded the most recent action. While a match
 * is live that is the newest log row's `recorded_by`; once it is finished the log may have been swept
 * (#939), so it is the umpire with the latest `match_umpires.last_recorded_at` — the same row, since the
 * credit is folded out of that very log at finalize.
 */
class MatchScorers(
    private val live: LiveMatchRepository = LiveMatchRepository(),
    private val users: UserRepository = UserRepository(),
) {
    /** The final scorer of a finished match, from the durable umpire credit; null when it was not scored live. */
    fun finalScorer(matchId: UUID): MatchPublicPlayer? =
        live
            .umpires(matchId = matchId)
            .maxByOrNull { it.lastRecordedAt }
            ?.let { users.findById(id = it.userId).getOrNull()?.toDomain() }
            ?.let { user ->
                MatchPublicPlayer(
                    displayName = user.shownName(),
                    publicCode = user.publicCode,
                    isPlaceholder = user.placeholder,
                    isDeleted = user.isDeleted(),
                )
            }

    /** Each of [userIds]' public code — what a replay stores to name its scorers. */
    fun publicCodesOf(userIds: Collection<UUID>): Map<UUID, String> =
        users.findAllByIds(ids = userIds.toList()).map { it.toDomain() }.associate { it.id to it.publicCode }

    /** [codes] resolved to the scorers as shown today. A code that no longer resolves is left out. */
    fun resolve(codes: Collection<String>): Map<String, ReplayScorer> =
        codes
            .distinct()
            .mapNotNull { code -> users.findByPublicCode(code = code)?.toDomain()?.let { code to it } }
            .associate { (code, user) ->
                code to ReplayScorer(displayName = user.shownName(), isPlaceholder = user.placeholder, isDeleted = user.isDeleted())
            }
}

/** The name a public surface may show for a scorer: their display name, or nothing at all once deleted. */
internal fun User.shownName(): String? = if (isDeleted()) null else displayName()
