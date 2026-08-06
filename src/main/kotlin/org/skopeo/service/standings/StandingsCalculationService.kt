// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.standings

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import org.skopeo.common.error.ServiceError
import org.skopeo.common.security.Capability
import org.skopeo.dto.standings.StandingsCalculationResponse
import org.skopeo.mapper.dto.standings.toResponse
import org.skopeo.mapper.entity.user.toDomain
import org.skopeo.model.AuditAction
import org.skopeo.model.AuditEntityType
import org.skopeo.model.AuditWrite
import org.skopeo.model.GroupStanding
import org.skopeo.model.RankedEntry
import org.skopeo.model.SnapshotSource
import org.skopeo.model.SnapshotStatus
import org.skopeo.model.StandingsBand
import org.skopeo.model.StandingsCalculationOutcome
import org.skopeo.model.StandingsEntryWrite
import org.skopeo.model.User
import org.skopeo.model.UserRating
import org.skopeo.repository.RankingPointRepository
import org.skopeo.repository.StandingsSnapshotRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.audit.AuditService
import org.skopeo.service.rating.RatingAssembler
import org.skopeo.service.user.VerifiedFirebaseToken
import org.skopeo.service.user.displayName
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * The points-based standings recompute trigger (ADMINISTRATOR only, #146 phase 2). It rebuilds the
 * standings snapshot **from the ranking-points ledger** rather than ratings: it reads the awards that
 * count as of now (ACTIVE and in their validity window), sums each active player's points per
 * (band, sex) using the award's own band and sex (band-tagged, decision D2), ranks each group by
 * points descending, and publishes a `source=POINTS` snapshot via [StandingsSnapshotRepository].
 *
 * It mirrors [org.skopeo.service.rating.RatingCalculationService]: a single [calculate] entry with a
 * `dryRun` flag — a dry run returns a full preview and persists nothing; an explicit commit writes a
 * PUBLISHED POINTS snapshot in one transaction and audits the recompute + publish. Once committed, the
 * read path ([StandingsService]) automatically prefers the points snapshot (the rollout flip, design §9).
 *
 * Expected failures are returned as an [Either] left ([ServiceError], issue #115) rather than thrown.
 */
class StandingsCalculationService(
    private val awards: RankingPointRepository = RankingPointRepository(),
    private val snapshots: StandingsSnapshotRepository = StandingsSnapshotRepository(),
    private val users: UserRepository = UserRepository(),
    private val ratings: RatingAssembler = RatingAssembler(),
    private val audit: AuditService = AuditService(),
) {
    /**
     * Recompute the points-based standings as of now. When [dryRun] (the default at the route) returns a
     * preview and persists nothing; otherwise publishes a PUBLISHED POINTS snapshot and audits the run.
     */
    fun calculate(
        token: VerifiedFirebaseToken,
        dryRun: Boolean,
    ): Either<ServiceError, StandingsCalculationResponse> =
        either {
            val adminId = requireAdmin(token = token).bind()
            val now = LocalDateTime.now()
            val groups = recompute(asOf = now)

            if (!dryRun) {
                commit(groups = groups, asOf = now, publishedBy = adminId)
            } else {
                audit.record(
                    write =
                        AuditWrite(
                            actorUserId = adminId,
                            action = AuditAction.STANDINGS_RECALCULATED,
                            entityType = AuditEntityType.STANDINGS,
                            entityId = null,
                            summary = "Previewed points-based standings recompute for ${groups.size} groups",
                            details = recomputeDetails(groups = groups),
                        ),
                )
            }
            StandingsCalculationOutcome(dryRun = dryRun, groups = groups).toResponse()
        }

    /**
     * Recompute the ranked (band, sex) groups from the ledger as of [asOf]: sum each active player's
     * counting awards per (band, sex) using the award's own band/sex tags (band-tagged, D2), then rank
     * each group by points descending with the D8 tie-break.
     *
     * Phase D (#403, decision #2 / §4 band-scoped counting): an award only counts while its band tag
     * equals the player's CURRENT band. An award for a player who has since changed bands is skipped
     * from the sum (it stays owned in the ledger, just uncounted, and resumes counting if the player
     * returns to that band). A player with no current rating counts none of their awards.
     */
    private fun recompute(asOf: LocalDateTime): List<GroupStanding> {
        val counting = awards.activeAsOf(asOf = asOf)
        val activeById =
            users
                .findAllByIds(ids = counting.map { it.userId }.distinct())
                .map { it.toDomain() }
                .filter { it.isActive }
                .associateBy { it.id }
        val countingForActive = counting.filter { activeById.containsKey(key = it.userId) }

        val ratingsById = ratings.findCurrentRatings(userIds = countingForActive.map { it.userId }.distinct())

        // Σ points per (band, sex, userId) — the award carries its own band + sex (fixed at award time),
        // but Phase D only counts it while that band matches the player's CURRENT band (band-scoped, #2).
        val totals = mutableMapOf<GroupKey, MutableMap<UUID, BigDecimal>>()
        countingForActive.forEach { award ->
            if (award.band != ratingsById[award.userId]?.currentLevel) return@forEach
            // The award's band is the player's RAW NTRP level (e.g. "2.0"); bucket it into the standings
            // race via [StandingsBand.of] (#555) — sub-3.0 → "<3.0", 6.0+ → "6.0+". Using the strict
            // requireCode here 500s for any tail band whose level isn't an exact StandingsBand code.
            val key = GroupKey(band = StandingsBand.of(rating = award.band.toBigDecimal()), sex = normalizeSex(sex = award.sex))
            val byUser = totals.getOrPut(key = key) { mutableMapOf() }
            byUser[award.userId] = byUser.getOrElse(key = award.userId) { BigDecimal.ZERO }.add(award.points)
        }

        return totals
            .map { (key, byUser) -> rankGroup(key = key, byUser = byUser, usersById = activeById, ratingsById = ratingsById) }
            .sortedWith(comparator = groupOrder())
    }

    /** One (band, sex) group ranked by points desc, then the tie-break (confidence, then rating, then name). */
    private fun rankGroup(
        key: GroupKey,
        byUser: Map<UUID, BigDecimal>,
        usersById: Map<UUID, User>,
        ratingsById: Map<UUID, UserRating>,
    ): GroupStanding {
        val entries =
            byUser.entries
                .map { (userId, points) ->
                    val user = usersById.getValue(key = userId)
                    // Floor the counted/displayed total at 0 (#469): a manual deduction can drive the net
                    // negative, but a rank should never show below-zero points. The ledger stays truthful —
                    // the signed entries are preserved; only this counted total is clamped.
                    val counted = points.max(BigDecimal.ZERO)
                    val rating = ratingsById[userId]
                    RankInput(user = user, points = counted, rating = rating?.currentRating, confidence = rating?.confidence)
                }.sortedWith(comparator = entryOrder())
                .mapIndexed { index, input ->
                    RankedEntry(
                        rank = index + 1,
                        userId = input.user.id,
                        displayName = input.user.displayName(),
                        publicCode = input.user.publicCode,
                        points = input.points,
                        currentRating = input.rating,
                    )
                }
        return GroupStanding(band = key.band, sex = key.sex, entries = entries)
    }

    private fun commit(
        groups: List<GroupStanding>,
        asOf: LocalDateTime,
        publishedBy: UUID,
    ) {
        val writes =
            groups.flatMap { group ->
                group.entries.map { entry ->
                    StandingsEntryWrite(
                        band = group.band,
                        sex = group.sex,
                        rank = entry.rank,
                        userId = entry.userId,
                        // Source-agnostic ordering value: the points total for the points-based snapshot.
                        orderingValue = entry.points,
                        // D8 tie-break input: the current rating (nullable when the player is unrated).
                        tiebreakRating = entry.currentRating,
                        // Points snapshots have no cheap achieved-at date (design §6.1); left null.
                        achievedAt = null,
                    )
                }
            }
        val snapshotId =
            snapshots.create(
                computedAt = asOf,
                asOf = asOf.toLocalDate(),
                status = SnapshotStatus.PUBLISHED,
                entries = writes,
                source = SnapshotSource.POINTS,
            )
        // Two audit entries per committed run (design §7): the recompute, then the publish.
        audit.record(
            write =
                AuditWrite(
                    actorUserId = publishedBy,
                    action = AuditAction.STANDINGS_RECALCULATED,
                    entityType = AuditEntityType.STANDINGS,
                    entityId = snapshotId,
                    summary = "Recomputed points-based standings for ${groups.size} groups",
                    details = recomputeDetails(groups = groups),
                ),
        )
        audit.record(
            write =
                AuditWrite(
                    actorUserId = publishedBy,
                    action = AuditAction.STANDINGS_PUBLISHED,
                    entityType = AuditEntityType.STANDINGS,
                    entityId = snapshotId,
                    summary = "Published points-based standings snapshot",
                    details = recomputeDetails(groups = groups),
                ),
        )
    }

    /** Summary details shared by the preview/recompute/publish audit entries: group + ranked-player counts. */
    private fun recomputeDetails(groups: List<GroupStanding>): Map<String, String?> =
        mapOf(
            "groups" to groups.size.toString(),
            "players" to groups.sumOf { it.entries.size }.toString(),
        )

    /** One player's rank inputs within a group: points, plus the tie-break keys (rating, confidence). */
    private data class RankInput(
        val user: User,
        val points: BigDecimal,
        val rating: BigDecimal?,
        val confidence: BigDecimal?,
    )

    /**
     * Ranking within a group: higher points first, then the tie-breakers — higher rating **confidence**
     * (#546: the matches-played / recency proxy the Part 6 study endorses, since within-band collisions
     * saturate with population), then higher current rating (a rated player outranks an unrated one via
     * [nullsLast]), then name/publicCode. Each desc key uses [nullsLast] so an absent value sorts last.
     */
    private fun entryOrder(): Comparator<RankInput> =
        compareByDescending<RankInput> { it.points }
            .thenBy(comparator = nullsLast(comparator = compareByDescending { confidence: BigDecimal -> confidence })) { it.confidence }
            .thenBy(comparator = nullsLast(comparator = compareByDescending { rating: BigDecimal -> rating })) { it.rating }
            .thenBy { standingName(user = it.user) }

    /** Group display order: strongest band first (enum ordinal desc), then Men → Women → Unspecified. */
    private fun groupOrder(): Comparator<GroupStanding> =
        compareByDescending<GroupStanding> { it.band.ordinal }
            .thenBy {
                when (it.sex) {
                    "Male" -> 0
                    "Female" -> 1
                    else -> 2
                }
            }

    private fun standingName(user: User): String = user.displayName() ?: user.publicCode

    /** The award's persisted sex tag; the ledger stores "Unspecified" for a sexless target — map it to null. */
    private fun normalizeSex(sex: String): String? = if (sex == "Unspecified") null else sex

    /** ADMINISTRATOR-only access; returns the caller's id (the audit actor). Mirrors ClubService.requireAdmin. */
    private fun requireAdmin(token: VerifiedFirebaseToken): Either<ServiceError, UUID> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)?.toDomain()
        val isAdmin = caller != null && caller.capabilities.contains(element = Capability.ADMINISTRATOR)
        return if (caller == null || !isAdmin) ServiceError.Forbidden().left() else caller.id.right()
    }

    /** The (band, sex) race a set of awards contributes to — the aggregation key. */
    private data class GroupKey(
        val band: StandingsBand,
        val sex: String?,
    )
}
