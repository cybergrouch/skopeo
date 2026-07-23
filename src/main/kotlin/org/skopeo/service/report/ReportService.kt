// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.report

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import org.skopeo.dto.report.BandHopBucket
import org.skopeo.dto.report.BandHopReportResponse
import org.skopeo.dto.report.BandHopUserRow
import org.skopeo.model.Capability
import org.skopeo.model.Level
import org.skopeo.model.RatingHistoryEntry
import org.skopeo.model.ServiceError
import org.skopeo.model.UserRating
import org.skopeo.model.displayName
import org.skopeo.model.isDeleted
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.user.VerifiedFirebaseToken
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
 * Admin reports (#216). The first report is NTRP band hops over a date range: for each rated player,
 * compare the band they were in entering the window with the FARTHEST band they reached during it, and
 * bucket players by the absolute number of 0.5-wide bands moved. Using the farthest excursion (not the
 * window's closing band) means a crossing that later reverses within the window still counts — a player
 * who dips into another band and comes back is a real, qualified hop, not a stayer (#289). The intent is
 * to confirm that most players stay within their band (hop 0) and to surface the exceptions who jumped.
 * Band labels only — never exact ratings. ADMINISTRATOR only; expected failures are returned as an
 * [Either] left ([ServiceError]).
 */
class ReportService(
    private val users: UserRepository = UserRepository(),
    private val ratings: RatingRepository = RatingRepository(),
) {
    /** One player's farthest band excursion during the window, from their entry band (labels only). */
    private data class Hop(
        val userId: UUID,
        val fromBand: String,
        val toBand: String,
        val distance: Int,
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
            val hops =
                ratings.allCurrentRatings().mapNotNull { rating ->
                    hopFor(
                        rating = rating,
                        history = historyByUser[rating.userId].orEmpty(),
                        windowOpen = windowOpen,
                        windowClose = windowClose,
                    )
                }

            val namesById = users.findAllByIds(ids = hops.map { it.userId }).associateBy { it.id }
            val buckets =
                hops
                    .groupBy { it.distance }
                    .toSortedMap()
                    .map { (distance, list) ->
                        BandHopBucket(
                            hopDistance = distance,
                            count = list.size,
                            users =
                                list
                                    .map { hop ->
                                        // Every hop came from a rating row (FK-backed user), so it resolves.
                                        val user = namesById.getValue(key = hop.userId)
                                        BandHopUserRow(
                                            publicCode = user.publicCode,
                                            displayName = user.displayName(),
                                            fromBand = hop.fromBand,
                                            toBand = hop.toBand,
                                            isPlaceholder = user.placeholder,
                                            isDeleted = user.isDeleted(),
                                        )
                                    }.sortedBy { it.publicCode },
                        )
                    }

            val stayed = hops.count { it.distance == 0 }
            BandHopReportResponse(
                startDate = startDate.toString(),
                endDate = endDate.toString(),
                totalPlayers = hops.size,
                stayedCount = stayed,
                jumpedCount = hops.size - stayed,
                buckets = buckets,
            )
        }

    /**
     * One player's band excursion during the window, or null if they have no current band (the only
     * skip). Guards on the current band but derives bands from RAW ratings via [Level.fromValue] (#257):
     * the entry band vs the FARTHEST band reached in-window, so a crossing that later reverses still
     * counts (#289) rather than netting to zero at the window's close.
     */
    private fun hopFor(
        rating: UserRating,
        history: List<RatingHistoryEntry>,
        windowOpen: LocalDateTime,
        windowClose: LocalDateTime,
    ): Hop? {
        if (rating.currentLevel == null) return null
        val fromLevel = entryBand(rows = history, windowOpen = windowOpen, fallback = rating.currentRating)
        val fromIndex = bandIndex(level = fromLevel)
        val peakLevel =
            history
                .filter { !it.calculatedAt.isBefore(windowOpen) && !it.calculatedAt.isAfter(windowClose) }
                .map { Level.fromValue(value = it.newRating.toPlainString()) }
                .maxByOrNull { abs(n = bandIndex(level = it) - fromIndex) }
                ?: fromLevel
        return Hop(
            userId = rating.userId,
            fromBand = fromLevel.value,
            toBand = peakLevel.value,
            distance = abs(n = bandIndex(level = peakLevel) - fromIndex),
        )
    }

    /** The band a player was in ENTERING the window: their raw rating just before it, snapped via [Level.fromValue]. */
    private fun entryBand(
        rows: List<RatingHistoryEntry>,
        windowOpen: LocalDateTime,
        fallback: BigDecimal,
    ): Level = Level.fromValue(value = entryRatingOf(rows = rows, windowOpen = windowOpen, fallback = fallback).toPlainString())

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
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)
        return if (caller == null || !caller.capabilities.contains(element = Capability.ADMINISTRATOR)) {
            ServiceError.Forbidden().left()
        } else {
            caller.id.right()
        }
    }
}
