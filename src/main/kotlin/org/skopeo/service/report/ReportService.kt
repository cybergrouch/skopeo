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
import org.skopeo.model.RatingHistoryEntry
import org.skopeo.model.ServiceError
import org.skopeo.model.displayName
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.user.VerifiedFirebaseToken
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID
import kotlin.math.roundToInt

private const val BAND_WIDTH = 0.5

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
            // on the band scale and is skipped (the only reachable skip). A rated player's band at each
            // boundary always resolves (their current band is the ultimate fallback), so no null hops.
            val hops =
                ratings.allCurrentRatings().mapNotNull { rating ->
                    rating.currentLevel?.let { currentBand ->
                        val rows = historyByUser[rating.userId].orEmpty()
                        val fromBand = bandAsOf(rows = rows, instant = windowOpen, inclusive = false, fallback = currentBand)
                        val toBand = bandAsOf(rows = rows, instant = windowClose, inclusive = true, fallback = currentBand)
                        Hop(
                            userId = rating.userId,
                            fromBand = fromBand,
                            toBand = toBand,
                            distance = bandDistance(from = fromBand, to = toBand),
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

    /**
     * The player's band as of [instant]: the newLevel of their latest change on/before it ([inclusive]
     * decides whether a change exactly at the instant counts). With no earlier change, fall back to the
     * band before their first-ever change (its previousLevel), and finally to [fallback] (their current
     * band — correct for a player who never had a calculated change).
     */
    private fun bandAsOf(
        rows: List<RatingHistoryEntry>,
        instant: LocalDateTime,
        inclusive: Boolean,
        fallback: String,
    ): String {
        val prior =
            rows.filter { if (inclusive) !it.calculatedAt.isAfter(instant) else it.calculatedAt.isBefore(instant) }
        return prior.maxByOrNull { it.calculatedAt }?.newLevel ?: rows.minByOrNull { it.calculatedAt }?.previousLevel ?: fallback
    }

    /** Absolute number of 0.5-wide bands between two NTRP level labels, e.g. 3.0 → 4.0 = 2 (labels are numeric). */
    private fun bandDistance(
        from: String,
        to: String,
    ): Int = ((to.toBigDecimal() - from.toBigDecimal()).abs().toDouble() / BAND_WIDTH).roundToInt()

    private fun requireAdmin(token: VerifiedFirebaseToken): Either<ServiceError, UUID> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)
        return if (caller == null || !caller.capabilities.contains(element = Capability.ADMINISTRATOR)) {
            ServiceError.Forbidden().left()
        } else {
            caller.id.right()
        }
    }
}
