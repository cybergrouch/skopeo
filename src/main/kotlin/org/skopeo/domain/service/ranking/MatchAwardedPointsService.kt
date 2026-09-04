// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.ranking

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import org.skopeo.common.dto.ranking.AwardDerivationResponse
import org.skopeo.common.dto.ranking.AwardedPointsPlayerRow
import org.skopeo.common.dto.ranking.AwardedPointsSummaryResponse
import org.skopeo.common.error.ServiceError
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.entity.ranking.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.RankingPointAward
import org.skopeo.domain.model.User
import org.skopeo.domain.service.club.ClubAccess
import org.skopeo.domain.service.settings.SettingsService
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.domain.service.user.displayName
import org.skopeo.domain.service.user.isDeleted
import org.skopeo.repository.EventRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RankingPointRepository
import org.skopeo.repository.UserRepository
import java.math.BigDecimal
import java.util.UUID
import org.skopeo.domain.mapper.entity.event.toDomain as toEventDomain
import org.skopeo.domain.mapper.entity.match.toDomain as toMatchDomain

/**
 * What one match awarded, per player (#858) — the public match page's points card.
 *
 * Its own class rather than another member of [RankingPointService] because the gate is a different axis
 * entirely: that service authorizes by flat capability (grants and the ledger are ADMINISTRATOR /
 * POINTS_MANAGER work), while this one asks a per-club ownership question through [ClubAccess].
 *
 * **Two audiences, one endpoint.** Amounts, recipients and point classes are public — a points total is
 * already public under the POINTS standings source, and rank and band are public per #64/#114. The
 * *derivation* is not: it carries the band relation, which is rating-adjacent (#583/#654). So the
 * derivation is **omitted from the payload** for a caller not entitled to it rather than hidden by the
 * client, and the same URL serves an anonymous reader and an organizer.
 */
class MatchAwardedPointsService(
    private val matches: MatchRepository = MatchRepository(),
    private val awards: RankingPointRepository = RankingPointRepository(),
    private val users: UserRepository = UserRepository(),
    private val events: EventRepository = EventRepository(),
    private val clubAccess: ClubAccess = ClubAccess(),
    // Only to read the hide-ranking-points flag (#865).
    private val settings: SettingsService = SettingsService(),
    private val derivations: AwardDerivationAssembler = AwardDerivationAssembler(),
) {
    /**
     * The card for the match with public code [code]; anonymous, so [token] is optional.
     *
     * Deliberately **not** filtered by validity: an expired award still records what this match paid, and
     * this is the one read of `ranking_point_awards` that is about history rather than about what counts
     * today. Revoked awards do drop out — a corrected score (#776) revokes and re-issues, and showing both
     * would double-count the same match.
     */
    fun forMatch(
        code: String,
        token: VerifiedFirebaseToken? = null,
    ): Either<ServiceError, AwardedPointsSummaryResponse> =
        either {
            val match =
                ensureNotNull(value = matches.findByPublicCode(code = code)?.toMatchDomain()) {
                    ServiceError.NotFound(message = "Match $code not found")
                }
            val viewer = token?.let { users.findByFirebaseUid(firebaseUid = it.uid)?.toDomain() }
            // Suppressed for an unprivileged viewer while the hide-points flag is on (#865). An empty
            // summary, not a Forbidden: the endpoint is public and the match is real, there is simply
            // nothing this viewer may see — and the client renders no card for an empty list, which is
            // exactly what it does for a match that awarded nothing.
            if (!settings.pointsVisibleTo(viewer = viewer)) {
                return@either AwardedPointsSummaryResponse(rows = emptyList(), totalPoints = "0")
            }
            val rows = awards.listActiveByMatch(matchId = match.id).map { it.toDomain() }
            summariseMatch(
                awards = rows,
                players = users,
                derive = if (maySeeDerivation(viewer = viewer, eventId = match.eventId)) derivations::derive else null,
            )
        }

    /**
     * Whether [viewer] may see *how* the amounts were reached.
     *
     * `mayOrganize || RATER` (the decision on #857): ADMINISTRATOR and RATER globally, a club owner for
     * their own club's matches, and the event's creator. A plain HOST gets nothing from the capability
     * alone — reach over an event is per-club since #789, and this read is no exception.
     *
     * **An eventless match has no club**, so there is no ownership to scope against and `mayOrganize` has
     * nothing to answer. Those fall back to ADMINISTRATOR / RATER, which is handled here rather than
     * discovered later as a null-club crash.
     */
    private fun maySeeDerivation(
        viewer: User?,
        eventId: UUID?,
    ): Boolean {
        if (viewer == null) return false
        // Short-circuiting matters: the global roles are the common privileged case, and reading the event
        // to answer a question already settled would be a query per card for nothing.
        return viewer.capabilities.any { it == Capability.ADMINISTRATOR || it == Capability.RATER } ||
            eventId
                ?.let { events.findById(id = it)?.toEventDomain() }
                ?.let { clubAccess.mayOrganize(caller = viewer, event = it) } == true
    }
}

/**
 * One row per **award**, not per player, highest amount first.
 *
 * That is the difference from the event card's fold: an event sums several awards into one figure per
 * player, whereas a match award is one award — which is what lets a row name its point class and carry
 * its own derivation. A doubles match therefore yields four rows and a singles match two.
 *
 * [derive] is null when the viewer may not see derivations, and the field is then simply absent from the
 * payload; the gate is not re-asked per row.
 *
 * File-scope for the same reason as `summarise`: it is a pure mapping plus one batched identity lookup.
 */
private fun summariseMatch(
    awards: List<RankingPointAward>,
    players: UserRepository,
    derive: ((RankingPointAward) -> AwardDerivationResponse)?,
): AwardedPointsSummaryResponse {
    val usersById = players.findAllByIds(ids = awards.map { it.userId }).map { it.toDomain() }.associateBy { it.id }
    val rows =
        awards
            // Highest first, then by award id so two identical amounts hold a stable order between renders.
            .sortedWith(comparator = compareByDescending<RankingPointAward> { it.points }.thenBy { it.id.toString() })
            .map { award ->
                val user = usersById[award.userId]
                AwardedPointsPlayerRow(
                    userId = award.userId.toString(),
                    publicCode = user?.publicCode,
                    displayName = user?.displayName(),
                    points = award.points.toPlainString(),
                    isPlaceholder = user?.placeholder ?: false,
                    isDeleted = user?.isDeleted() ?: false,
                    awardId = award.id.toString(),
                    pointClass = award.pointClass.name,
                    derivation = derive?.invoke(award),
                )
            }
    return AwardedPointsSummaryResponse(
        rows = rows,
        totalPoints = awards.fold(initial = BigDecimal.ZERO) { sum, row -> sum.add(row.points) }.toPlainString(),
    )
}
