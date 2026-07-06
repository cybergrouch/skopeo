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
import org.skopeo.model.displayName
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
 * compare the band they were in entering the window with the band they were in at its close, and bucket
 * players by the absolute number of 0.5-wide bands moved. The intent is to confirm that most players
 * stay within their band (hop 0) and to surface the exceptions who jumped. Band labels only — never
 * exact ratings. ADMINISTRATOR only; expected failures are returned as an [Either] left ([ServiceError]).
 */
class ReportService(
    private val users: UserRepository = UserRepository(),
    private val ratings: RatingRepository = RatingRepository(),
) {
    /** One player's net band movement over the window (labels only). */
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
                    // Guard on the current band, but derive the boundary bands from RAW ratings (#257): snap
                    // each endpoint through Level.fromValue so banding matches the rest of the app and a real
                    // crossing is never lost to a missing/stale stored label.
                    rating.currentLevel?.let {
                        val rows = historyByUser[rating.userId].orEmpty()
                        val fromLevel = bandAt(rows = rows, instant = windowOpen, inclusive = false, fallback = rating.currentRating)
                        val toLevel = bandAt(rows = rows, instant = windowClose, inclusive = true, fallback = rating.currentRating)
                        Hop(
                            userId = rating.userId,
                            fromBand = fromLevel.value,
                            toBand = toLevel.value,
                            distance = abs(n = bandIndex(level = toLevel) - bandIndex(level = fromLevel)),
                        )
                    }
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

    /** The player's band as of [instant]: their raw rating there, snapped to a band via [Level.fromValue]. */
    private fun bandAt(
        rows: List<RatingHistoryEntry>,
        instant: LocalDateTime,
        inclusive: Boolean,
        fallback: BigDecimal,
    ): Level =
        Level.fromValue(
            value = ratingAsOf(rows = rows, instant = instant, inclusive = inclusive, fallback = fallback).toPlainString(),
        )

    /**
     * The player's raw rating as of [instant]: the newRating of their latest change on/before it
     * ([inclusive] decides whether a change exactly at the instant counts). With no earlier change, fall
     * back to the rating before their first-ever change (its previousRating), and finally to [fallback]
     * (their current rating — correct for a player who never had a calculated change). The caller snaps the
     * result to a band via [Level.fromValue], so banding is consistent with the rest of the app (#257).
     */
    private fun ratingAsOf(
        rows: List<RatingHistoryEntry>,
        instant: LocalDateTime,
        inclusive: Boolean,
        fallback: BigDecimal,
    ): BigDecimal {
        val latestPrior =
            rows
                .filter { if (inclusive) !it.calculatedAt.isAfter(instant) else it.calculatedAt.isBefore(instant) }
                .maxByOrNull { it.calculatedAt }
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
