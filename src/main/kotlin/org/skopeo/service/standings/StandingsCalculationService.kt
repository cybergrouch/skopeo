// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.standings

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import org.skopeo.model.AuditAction
import org.skopeo.model.AuditEntityType
import org.skopeo.model.AuditWrite
import org.skopeo.model.Capability
import org.skopeo.model.ServiceError
import org.skopeo.model.SnapshotSource
import org.skopeo.model.SnapshotStatus
import org.skopeo.model.StandingsBand
import org.skopeo.model.StandingsEntryWrite
import org.skopeo.model.User
import org.skopeo.model.UserRating
import org.skopeo.model.displayName
import org.skopeo.repository.RankingPointRepository
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.StandingsSnapshotRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.audit.AuditService
import org.skopeo.service.user.VerifiedFirebaseToken
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
    private val ratings: RatingRepository = RatingRepository(),
    private val audit: AuditService = AuditService(),
) {
    /** One ranked player within a (band, sex) group of the recompute: their points total and current rating. */
    data class RankedEntry(
        val rank: Int,
        val userId: UUID,
        val displayName: String?,
        val publicCode: String,
        val points: BigDecimal,
        val currentRating: BigDecimal?,
    )

    /** One (band, sex) race of the recompute, ranked by points descending. */
    data class GroupStanding(
        val band: StandingsBand,
        val sex: String?,
        val entries: List<RankedEntry>,
    )

    /** The recompute outcome: the ranked groups plus whether this was a dry run (no persist) or a commit. */
    data class CalculationOutcome(
        val dryRun: Boolean,
        val groups: List<GroupStanding>,
    )

    /**
     * Recompute the points-based standings as of now. When [dryRun] (the default at the route) returns a
     * preview and persists nothing; otherwise publishes a PUBLISHED POINTS snapshot and audits the run.
     */
    fun calculate(
        token: VerifiedFirebaseToken,
        dryRun: Boolean,
    ): Either<ServiceError, CalculationOutcome> =
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
            CalculationOutcome(dryRun = dryRun, groups = groups)
        }

    /**
     * Recompute the ranked (band, sex) groups from the ledger as of [asOf]: sum each active player's
     * counting awards per (band, sex) using the award's own band/sex tags (band-tagged, D2), then rank
     * each group by points descending with the D8 tie-break.
     */
    private fun recompute(asOf: LocalDateTime): List<GroupStanding> {
        val counting = awards.activeAsOf(asOf = asOf)
        val activeById = users.findAllByIds(ids = counting.map { it.userId }.distinct()).filter { it.isActive }.associateBy { it.id }
        val countingForActive = counting.filter { activeById.containsKey(key = it.userId) }

        // Σ points per (band, sex, userId) — the award carries its own band + sex (fixed at award time).
        val totals = mutableMapOf<GroupKey, MutableMap<UUID, BigDecimal>>()
        countingForActive.forEach { award ->
            val key = GroupKey(band = StandingsBand.requireCode(code = award.band), sex = normalizeSex(sex = award.sex))
            val byUser = totals.getOrPut(key = key) { mutableMapOf() }
            byUser[award.userId] = byUser.getOrElse(key = award.userId) { BigDecimal.ZERO }.add(award.points)
        }

        val ratingsById = ratings.findCurrentRatings(userIds = countingForActive.map { it.userId }.distinct())
        return totals
            .map { (key, byUser) -> rankGroup(key = key, byUser = byUser, usersById = activeById, ratingsById = ratingsById) }
            .sortedWith(comparator = groupOrder())
    }

    /** One (band, sex) group ranked by points desc, then the D8 tie-break (higher rating, then name). */
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
                    Triple(first = user, second = points, third = ratingsById[userId]?.currentRating)
                }.sortedWith(comparator = entryOrder())
                .mapIndexed { index, (user, points, rating) ->
                    RankedEntry(
                        rank = index + 1,
                        userId = user.id,
                        displayName = user.displayName(),
                        publicCode = user.publicCode,
                        points = points,
                        currentRating = rating,
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

    /**
     * Ranking within a group (D8): higher points first, then higher current rating (a rated player
     * outranks an unrated one via [nullsLast]), then name/publicCode. Rating desc = ascending on the
     * negated value, so [nullsLast] puts an absent rating last regardless of direction.
     */
    private fun entryOrder(): Comparator<Triple<User, BigDecimal, BigDecimal?>> =
        compareByDescending<Triple<User, BigDecimal, BigDecimal?>> { it.second }
            .thenBy(comparator = nullsLast(comparator = compareByDescending { rating: BigDecimal -> rating })) { it.third }
            .thenBy { standingName(user = it.first) }

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
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)
        val isAdmin = caller != null && caller.capabilities.contains(element = Capability.ADMINISTRATOR)
        return if (caller == null || !isAdmin) ServiceError.Forbidden().left() else caller.id.right()
    }

    /** The (band, sex) race a set of awards contributes to — the aggregation key. */
    private data class GroupKey(
        val band: StandingsBand,
        val sex: String?,
    )
}
