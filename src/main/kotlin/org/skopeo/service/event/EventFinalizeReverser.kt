// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.event

import org.jetbrains.exposed.sql.transactions.transaction
import org.skopeo.model.AuditAction
import org.skopeo.model.AuditEntityType
import org.skopeo.model.AuditWrite
import org.skopeo.model.Event
import org.skopeo.repository.EventRepository
import org.skopeo.repository.RankingPointRepository
import org.skopeo.service.audit.AuditService
import java.time.LocalDateTime
import java.util.UUID

/**
 * Un-finalize reversal (#477). The counterpart to [EventFinalizeAwarder]: a small collaborator of
 * [EventService.unfinalize] that undoes a finalize's side effects. In one transaction it revokes every
 * ACTIVE ranking-point award the finalize produced (via [RankingPointRepository.revoke], which keeps
 * the ledger append-only by adding REVOKED markers rather than deleting rows) and clears the event's
 * finalize flag ([EventRepository.unfinalize]) — which also implicitly restores the reserved-points
 * budget, since it only counts fixtures where finalized_at IS NULL. The reversal is audited as
 * EVENT_UNFINALIZED. Kept out of EventService so the awarding/reversal pair stays cohesive and testable.
 */
class EventFinalizeReverser(
    private val events: EventRepository = EventRepository(),
    private val awards: RankingPointRepository = RankingPointRepository(),
    private val audit: AuditService = AuditService(),
) {
    /**
     * Reverse [event]'s finalize on behalf of [revokedBy]: revoke its active awards + clear the flag in
     * one transaction, then audit. The caller ([EventService.unfinalize]) has already checked authz and
     * the guards (event finalized, no rated matches). Returns the number of awards revoked.
     */
    fun reverse(
        event: Event,
        revokedBy: UUID,
        now: LocalDateTime,
    ): Int {
        val revokedCount =
            transaction {
                val active = awards.listActiveByEvent(eventId = event.id)
                active.forEach { award ->
                    awards.revoke(
                        awardId = award.id,
                        revokedBy = revokedBy,
                        reason = "Reversed on un-finalize of event ${event.name}",
                        revokedAt = now,
                    )
                }
                events.unfinalize(id = event.id)
                active.size
            }
        audit.record(
            write =
                AuditWrite(
                    actorUserId = revokedBy,
                    action = AuditAction.EVENT_UNFINALIZED,
                    entityType = AuditEntityType.EVENT,
                    entityId = event.id,
                    summary = "Un-finalized event ${event.name}, revoking $revokedCount award(s)",
                    details =
                        mapOf(
                            "publicCode" to event.publicCode,
                            "type" to event.type.name,
                            "awardsRevoked" to revokedCount.toString(),
                        ),
                ),
        )
        return revokedCount
    }
}
