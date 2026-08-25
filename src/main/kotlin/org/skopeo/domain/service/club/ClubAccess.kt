// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.club

import org.skopeo.common.security.Capability
import org.skopeo.domain.model.Event
import org.skopeo.domain.model.User
import org.skopeo.repository.ClubRepository
import java.util.UUID

/**
 * The one place club ownership is consulted for authorization (#789).
 *
 * Event/match authorization used to key purely off *who created the event*, which cut both ways: a HOST
 * could file an event under any club at all, while a club's own co-owner could not touch an event a
 * colleague had created for their club. The rule is now:
 *
 * ```
 * mayOrganize(caller, event) =
 *      ADMINISTRATOR
 *   OR (event.clubId != null AND caller is a named owner of event.clubId)
 *   OR event.createdBy == caller.id
 * ```
 *
 * The creator clause is **grandfathered permanently**, not a transitional shim: it needs no data
 * migration (nobody loses access to an event they filed under a club they don't own), and it is also the
 * whole rule for a clubless ("Open") event, which has no ownership anchor to consult. That is what makes
 * this change a *widening* of the per-event gates — club ownership is an additional allow — with the one
 * *tightening* being [mayFileUnder], the check that `create`/`setClub` never had.
 *
 * HOST and CLUB_OWNER are deliberately identical here (decision 1 on #789): what separates them is what
 * they unlock elsewhere, not their reach over a club's events.
 */
class ClubAccess(
    private val clubs: ClubRepository = ClubRepository(),
) {
    /** Every club id [callerId] is a named owner of; empty when they own none. Used to scope event lists. */
    fun ownedClubIds(callerId: UUID): Set<UUID> = clubs.findOwnedClubIds(userId = callerId).toSet()

    /**
     * Whether [callerId] is a named owner of [clubId]. A null [clubId] — a clubless event, or an event
     * being re-filed as "Open" — is never *owned* by anyone, so this is false; the callers below fold in
     * the administrator and creator clauses.
     */
    fun ownsClub(
        callerId: UUID,
        clubId: UUID?,
    ): Boolean = clubId != null && clubs.findOwnedClubIds(userId = callerId).contains(element = clubId)

    /**
     * The #789 predicate: whether [caller] may organize [event] — read its organizer payload, edit it,
     * manage its roster/teams/seeding, run its matches, and finalize it.
     */
    fun mayOrganize(
        caller: User,
        event: Event,
    ): Boolean =
        caller.capabilities.contains(element = Capability.ADMINISTRATOR) ||
            ownsClub(callerId = caller.id, clubId = event.clubId) ||
            event.createdBy == caller.id

    /**
     * Whether [caller] may FILE an event under [clubId] — the one tightening in #789. Creating (or
     * re-filing) an event under a club is a claim on that club's calendar, so it takes ownership of that
     * club; before #789 only the club's *existence* was checked, so any host could file anywhere.
     *
     * A null [clubId] is the clubless ("Open") case and stays open to any staff caller, as today.
     */
    fun mayFileUnder(
        caller: User,
        clubId: UUID,
    ): Boolean =
        caller.capabilities.contains(element = Capability.ADMINISTRATOR) ||
            ownsClub(callerId = caller.id, clubId = clubId)
}
