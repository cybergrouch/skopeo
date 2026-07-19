// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.event

import org.jetbrains.exposed.sql.transactions.transaction
import org.skopeo.model.AuditAction
import org.skopeo.model.AuditEntityType
import org.skopeo.model.AuditWrite
import org.skopeo.model.Event
import org.skopeo.repository.EventRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RankingPointRepository
import org.skopeo.repository.RatingRepository
import org.skopeo.service.audit.AuditService
import java.time.LocalDateTime
import java.util.UUID

/**
 * Event-scoped rating reversal (#478). The rated-path sibling of [EventFinalizeReverser]: where
 * un-finalize (#477) refuses once any of an event's matches are RATED, "Reverse Ratings" handles exactly
 * that case — it reverses an already-rated event so an erroneous score can be corrected and the event
 * re-finalized. This is destructive to rating state, so [EventService.reverseRatings] has already checked
 * authz (ADMINISTRATOR) and the guards (event finalized + at the rated tip) before calling here.
 *
 * In ONE transaction, mirroring how [org.skopeo.service.rating.RatingCalculationService.commit] wrote
 * these values so this unwinds them consistently:
 *
 *  1. **Restore** each participant to their pre-event rating — the `previous_rating`/`previous_level` of
 *     their EARLIEST in-event rating-history row ([RatingRepository.preEventRatings]).
 *  2. **Supersede** (soft-delete, not hard-delete) the event's own rating-history rows by stamping
 *     `reversed_at` ([RatingRepository.markEventHistoryReversed]); the read paths exclude them.
 *  3. **Revoke** the event's active ranking-point awards (the #477 primitive).
 *  4. **Reset** `rated_at` on the event's matches so they re-enter the pending-calc queue, then restore
 *     each participant's `last_match_date` to the date of their latest still-rated match (null if none).
 *  5. **Clear** the event's finalize flag ([EventRepository.unfinalize]) so the score can be edited and
 *     the event re-finalized.
 *
 * The reversal is audited as EVENT_RATINGS_REVERSED. Kept out of EventService so the awarding/reversal
 * family stays cohesive and testable.
 */
class EventRatingsReverser(
    private val events: EventRepository = EventRepository(),
    private val ratings: RatingRepository = RatingRepository(),
    private val matches: MatchRepository = MatchRepository(),
    private val awards: RankingPointRepository = RankingPointRepository(),
    private val audit: AuditService = AuditService(),
) {
    /** The tallies from one reversal, for the audit detail. */
    data class ReversalSummary(
        val participantsRestored: Int,
        val historyRowsReversed: Int,
        val awardsRevoked: Int,
    )

    /**
     * Reverse [event]'s ratings on behalf of [reversedBy] at [now], returning the tallies. The caller has
     * already confirmed authz + the guards (finalized, at the tip, has rated in-event history).
     */
    fun reverse(
        event: Event,
        reversedBy: UUID,
        now: LocalDateTime,
    ): ReversalSummary {
        val summary =
            transaction {
                // 1. Each participant's pre-event rating, read BEFORE we supersede the rows.
                val preEvent = ratings.preEventRatings(eventId = event.id)

                // 2. Supersede the event's rating-history rows (soft-delete).
                val historyRowsReversed = ratings.markEventHistoryReversed(eventId = event.id, reversedAt = now)

                // 3. Revoke the event's active awards (append a REVOKED marker each), keeping the ledger append-only.
                val active = awards.listActiveByEvent(eventId = event.id)
                active.forEach { award ->
                    awards.revoke(
                        awardId = award.id,
                        revokedBy = reversedBy,
                        reason = "Reversed on rating reversal of event ${event.name}",
                        revokedAt = now,
                    )
                }

                // 4. Reset rated_at on the event's matches, THEN recompute last_match_date from what remains rated.
                matches.clearRatedForEvent(eventId = event.id)
                val latestDates = matches.latestRatedMatchDatesByUsers(userIds = preEvent.keys.toList())

                // 1 (apply). Restore each participant's current rating/level + last_match_date.
                preEvent.values.forEach { pre ->
                    ratings.restoreCurrentRating(
                        userId = pre.userId,
                        rating = pre.previousRating,
                        level = pre.previousLevel,
                        lastMatchDate = latestDates[pre.userId],
                    )
                }

                // 5. Clear the finalize flag (also implicitly restores the reserved-points budget).
                events.unfinalize(id = event.id)

                ReversalSummary(
                    participantsRestored = preEvent.size,
                    historyRowsReversed = historyRowsReversed,
                    awardsRevoked = active.size,
                )
            }
        audit.record(
            write =
                AuditWrite(
                    actorUserId = reversedBy,
                    action = AuditAction.EVENT_RATINGS_REVERSED,
                    entityType = AuditEntityType.EVENT,
                    entityId = event.id,
                    summary =
                        "Reversed ratings for event ${event.name}: restored ${summary.participantsRestored} " +
                            "participant(s), superseded ${summary.historyRowsReversed} history row(s), " +
                            "revoked ${summary.awardsRevoked} award(s)",
                    details =
                        mapOf(
                            "publicCode" to event.publicCode,
                            "type" to event.type.name,
                            "participantsRestored" to summary.participantsRestored.toString(),
                            "historyRowsReversed" to summary.historyRowsReversed.toString(),
                            "awardsRevoked" to summary.awardsRevoked.toString(),
                        ),
                ),
        )
        return summary
    }
}
