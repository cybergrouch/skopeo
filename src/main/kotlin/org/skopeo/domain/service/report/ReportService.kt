// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.report

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import org.skopeo.common.dto.report.BandHopBucket
import org.skopeo.common.dto.report.BandHopReportResponse
import org.skopeo.common.dto.report.BandHopUserRow
import org.skopeo.common.error.ServiceError
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.Level
import org.skopeo.domain.model.RatingHistoryEntry
import org.skopeo.domain.model.User
import org.skopeo.domain.model.UserRating
import org.skopeo.domain.service.rating.RatingAssembler
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.domain.service.user.displayName
import org.skopeo.domain.service.user.isDeleted
import org.skopeo.repository.UserRepository
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID
import kotlin.math.abs

/** NTRP bands are 0.5 wide; the scale floor is 1.0. Band index n = (minRating − 1.0) / 0.5. */
private val BAND_WIDTH = BigDecimal("0.5")
private val NTRP_FLOOR = BigDecimal("1.0")

/**
 * Admin reports (#216/#724). The first report is NTRP band hops over a date range: for each rated player,
 * compare the band they were in entering the window against TWO endpoints and bucket players by the
 * absolute number of 0.5-wide bands moved for each. The EXCURSION metric uses the FARTHEST band reached
 * in-window, so a crossing that later reverses still counts — a player who dips into another band and
 * comes back is a real, qualified hop (#289). The NET metric uses the window's CLOSING band, so that same
 * round-tripper reads net 0, a stayer (#724). Reporting both keeps the transient-crossing signal while
 * also answering whether the player ended where they started. The intent is to confirm that most players
 * stay within their band (hop 0) and to surface the exceptions who jumped. Band labels only — never exact
 * ratings. ADMINISTRATOR only; expected failures are returned as an [Either] left ([ServiceError]).
 */
class ReportService(
    private val users: UserRepository = UserRepository(),
    private val ratings: RatingAssembler = RatingAssembler(),
) {
    /** One player's band movement during the window: both the farthest excursion and the net (labels only). */
    private data class Hop(
        val userId: UUID,
        val fromBand: String,
        val excursionToBand: String,
        val excursionDistance: Int,
        val netToBand: String,
        val netDistance: Int,
    )

    fun bandHops(
        token: VerifiedFirebaseToken,
        startDate: LocalDate,
        endDate: LocalDate,
    ): Either<ServiceError, BandHopReportResponse> =
        either {
            requireAdmin(token = token).bind()
            ensure(condition = !endDate.isBefore(startDate)) {
                ServiceError.Validation(message = "endDate cannot be before startDate")
            }
            // The band a player was in ENTERING the window (strictly before it) vs at its CLOSE, so a
            // change dated within [startDate, endDate] counts as movement during the window.
            val windowOpen = startDate.atStartOfDay()
            val windowClose = endDate.atTime(LocalTime.MAX)

            val historyByUser = ratings.allHistory().groupBy { it.userId }

            // Population: every player with a current band on record. A player with none can't be placed
            // on the band scale and is skipped (the only reachable skip). A rated player's rating at each
            // boundary always resolves (their current rating is the ultimate fallback), so no null hops.
            val allHops =
                ratings.allCurrentRatings().mapNotNull { rating ->
                    hopFor(
                        rating = rating,
                        history = historyByUser[rating.userId].orEmpty(),
                        windowOpen = windowOpen,
                        windowClose = windowClose,
                    )
                }

            val namesById = users.findAllByIds(ids = allHops.map { it.userId }).map { it.toDomain() }.associateBy { it.id }
            // Exclude soft-deleted accounts (#550) before bucketing/counting, so a deleted account never
            // appears in the report and the totals/percentages reflect only live accounts.
            val hops = allHops.filter { namesById[it.userId]?.isDeleted() == false }
            assemble(hops = hops, namesById = namesById, startDate = startDate, endDate = endDate)
        }

    /** Bucket the [hops] by BOTH metrics (excursion + net) into the response; each row carries both (#724). */
    private fun assemble(
        hops: List<Hop>,
        namesById: Map<UUID, User>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): BandHopReportResponse {
        // Each row carries BOTH metrics; the same row is reused across both bucketings (#724).
        val rowById =
            hops.associate { hop ->
                // Every hop came from a rating row (FK-backed user), so it resolves.
                val user = namesById.getValue(key = hop.userId)
                hop.userId to
                    BandHopUserRow(
                        publicCode = user.publicCode,
                        displayName = user.displayName(),
                        fromBand = hop.fromBand,
                        excursionToBand = hop.excursionToBand,
                        excursionDistance = hop.excursionDistance,
                        netToBand = hop.netToBand,
                        netDistance = hop.netDistance,
                        isPlaceholder = user.placeholder,
                        isDeleted = user.isDeleted(),
                    )
            }
        val bucketsBy = { distanceOf: (Hop) -> Int ->
            hops
                .groupBy(keySelector = distanceOf)
                .toSortedMap()
                .map { (distance, list) ->
                    BandHopBucket(
                        hopDistance = distance,
                        count = list.size,
                        users = list.map { rowById.getValue(key = it.userId) }.sortedBy { it.publicCode },
                    )
                }
        }
        val excursionStayed = hops.count { it.excursionDistance == 0 }
        val netStayed = hops.count { it.netDistance == 0 }
        return BandHopReportResponse(
            startDate = startDate.toString(),
            endDate = endDate.toString(),
            totalPlayers = hops.size,
            excursionStayedCount = excursionStayed,
            excursionJumpedCount = hops.size - excursionStayed,
            excursionBuckets = bucketsBy { it.excursionDistance },
            netStayedCount = netStayed,
            netJumpedCount = hops.size - netStayed,
            netBuckets = bucketsBy { it.netDistance },
        )
    }

    /**
     * One player's band movement during the window, or null if they have no current band (the only skip).
     * Guards on the current band but derives bands from RAW ratings via [Level.fromValue] (#257). Reports
     * BOTH the EXCURSION (entry band vs the FARTHEST band reached in-window — a crossing that later
     * reverses still counts, #289) and the NET (entry band vs the window's CLOSING band — the same
     * round-tripper nets to zero, #724).
     */
    private fun hopFor(
        rating: UserRating,
        history: List<RatingHistoryEntry>,
        windowOpen: LocalDateTime,
        windowClose: LocalDateTime,
    ): Hop? {
        if (rating.currentLevel == null) return null
        val entryRating = entryRatingOf(rows = history, windowOpen = windowOpen, fallback = rating.currentRating)
        val fromLevel = Level.fromValue(value = entryRating.toPlainString())
        val fromIndex = bandIndex(level = fromLevel)
        val peakLevel =
            history
                .filter { !it.calculatedAt.isBefore(windowOpen) && !it.calculatedAt.isAfter(windowClose) }
                .map { Level.fromValue(value = it.newRating.toPlainString()) }
                .maxByOrNull { abs(n = bandIndex(level = it) - fromIndex) }
                ?: fromLevel
        // The band at the window CLOSE: the newRating of the latest change at or before it, else the entry
        // rating (a player with no in-window change closes where they entered — a net stayer).
        val closingRating =
            history.filter { !it.calculatedAt.isAfter(windowClose) }.maxByOrNull { it.calculatedAt }?.newRating ?: entryRating
        val closingLevel = Level.fromValue(value = closingRating.toPlainString())
        return Hop(
            userId = rating.userId,
            fromBand = fromLevel.value,
            excursionToBand = peakLevel.value,
            excursionDistance = abs(n = bandIndex(level = peakLevel) - fromIndex),
            netToBand = closingLevel.value,
            netDistance = abs(n = bandIndex(level = closingLevel) - fromIndex),
        )
    }

    /**
     * The player's raw rating entering the window: the newRating of their latest change strictly before
     * [windowOpen]. With no earlier change, fall back to the rating before their first-ever change (its
     * previousRating), and finally to [fallback] (their current rating — correct for a player who never
     * had a calculated change). Snapped to a band by the caller via [Level.fromValue] (#257).
     */
    private fun entryRatingOf(
        rows: List<RatingHistoryEntry>,
        windowOpen: LocalDateTime,
        fallback: BigDecimal,
    ): BigDecimal {
        val latestPrior = rows.filter { it.calculatedAt.isBefore(windowOpen) }.maxByOrNull { it.calculatedAt }
        // newRating/previousRating are non-null, so an explicit branch (vs a ?. chain) avoids a dead
        // "present row but null rating" branch that could never be covered.
        if (latestPrior != null) return latestPrior.newRating
        val earliest = rows.minByOrNull { it.calculatedAt }
        return if (earliest != null) earliest.previousRating else fallback
    }

    /** A band's index on the NTRP scale: (minRating − 1.0) / 0.5, e.g. 1.0 → 0, 3.0 → 4, 4.0 → 6. */
    private fun bandIndex(level: Level): Int = (level.minRating.toBigDecimal() - NTRP_FLOOR).divide(BAND_WIDTH).intValueExact()

    private fun requireAdmin(token: VerifiedFirebaseToken): Either<ServiceError, UUID> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)?.toDomain()
        return if (caller == null || !caller.capabilities.contains(element = Capability.ADMINISTRATOR)) {
            ServiceError.Forbidden().left()
        } else {
            caller.id.right()
        }
    }
}
