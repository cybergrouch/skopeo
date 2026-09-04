// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.security

/*
 * The capability sets services authorize against (#866) — one definition each.
 *
 * They used to be `private val`s in whichever service needed them, which meant `{HOST, CLUB_OWNER,
 * ADMINISTRATOR}` was declared seven times under six names, and — worse — `STAFF_ROLES` named two
 * DIFFERENT sets in different files, with nothing forcing them to agree. `CapabilityRolesTest` now fails if
 * two sets ever share a name while differing in members.
 *
 * `common` is a leaf package under `LayeredArchitectureTest`, so every service can read this without a new
 * dependency.
 *
 * **These answer the flat question only** — "does this caller hold role X". Per-club ownership is a separate
 * axis and lives in `service/club/ClubAccess`, which CLAUDE.md makes the ONE place `club_owners` is
 * consulted for authorization (#789). Do not fold ownership in here; a set of capabilities cannot express
 * "owner of *this* club", and pretending otherwise would quietly kill that rule.
 *
 * **No local aliases.** Every service now imports and uses these names directly. Aliasing was tried first
 * and reverted: keeping `private val STAFF_ROLES = MATCH_MANAGEMENT_ROLES` in one service while another
 * had `private val STAFF_ROLES = setOf(HOST, ADMINISTRATOR)` preserved the exact collision this file
 * exists to remove.
 * A caller-specific name is only worth it when it tells the reader something the shared name does not, and
 * "staff" told them something false.
 */

/**
 * Match management: run fixtures, organize events, manage rosters, seed (#789).
 *
 * Named to match the **web** side (`canManageMatches` in `auth/capabilities.ts`) and CLAUDE.md's own
 * vocabulary, so the same rule reads the same on both sides of the stack. Previously spelled
 * `CLUB_STAFF_ROLES`, `STAFF_ROLES`, `CIRCUIT_STAFF_ROLES`, `SEEDING_ROLES`, `MATCH_MANAGEMENT_ROLES` and
 * `TEAM_STAFF_ROLES`.
 *
 * Note this is the *capability* half. A host or club owner still has to pass `ClubAccess.mayOrganize` for a
 * specific event.
 */
val MATCH_MANAGEMENT_ROLES: Set<Capability> =
    setOf(Capability.HOST, Capability.CLUB_OWNER, Capability.ADMINISTRATOR)

/**
 * Club owner or administrator — the roles exempt from the event-expiry gate (#310) and permitted to
 * sanction a club. Previously `OWNER_OR_ADMIN`, `EXPIRY_EXEMPT_ROLES` (twice) and
 * `TEAM_EXPIRY_EXEMPT_ROLES`.
 *
 * Named for the roles rather than one caller's use of them, because three of the four old names described
 * expiry exemption while the fourth described sanctioning — the same set doing different jobs.
 */
val CLUB_OWNER_OR_ADMIN: Set<Capability> =
    setOf(Capability.CLUB_OWNER, Capability.ADMINISTRATOR)

/**
 * Who may see a player's registered email (#630) — match management plus raters.
 *
 * **Composed, not re-listed**, so adding a role to match management cannot leave this behind. Deliberately
 * does *not* include POINTS_MANAGER: managing points is no reason to see someone's email. That is why this
 * set and [PLAYER_POINTS_VIEW_ROLES] stay separate despite differing by one member — they answer different
 * questions and would drift into each other if merged.
 */
val EMAIL_VIEW_ROLES: Set<Capability> = MATCH_MANAGEMENT_ROLES + Capability.RATER

/**
 * Who still sees ranking-point figures when the "hide ranking points from players" flag is on (#865) —
 * match management, raters and points managers.
 *
 * Composed for the same reason as [EMAIL_VIEW_ROLES]. POINTS_MANAGER belongs here and not there: a points
 * manager has an operational reason to see points and none to see an email address.
 */
val PLAYER_POINTS_VIEW_ROLES: Set<Capability> =
    MATCH_MANAGEMENT_ROLES + Capability.RATER + Capability.POINTS_MANAGER

/**
 * Who may look a player up by name or resolve one by id (#867) — match management, plus the three roles
 * whose own tools are built on searching for a person: points managers, raters and researchers.
 *
 * **This replaced `HOST_OR_ADMIN` = {HOST, ADMINISTRATOR}, which was an oversight** (#867). #789 gave a
 * named club owner the organizer surfaces — the New Event form and the event manager — and both render a
 * player picker that calls this. So a CLUB_OWNER who did not *also* hold HOST was offered a picker that
 * answered 403: the UI was granted and the call it depends on was not. Nothing implies HOST from
 * CLUB_OWNER; adding a club owner is an ADMINISTRATOR action that grants CLUB_OWNER alone.
 *
 * Composed from [MATCH_MANAGEMENT_ROLES] rather than re-listed, so a role added to match management
 * cannot leave the picker behind again — which is precisely how the gap arose.
 *
 * Searching for a person is **not** the same permission as seeing what the search returns about them: a
 * registered email needs [EMAIL_VIEW_ROLES] and a points figure needs [PLAYER_POINTS_VIEW_ROLES], both
 * enforced separately on the way out. Being able to find someone is the weaker right, which is why this
 * is the widest of the three sets.
 */
val PLAYER_SEARCH_ROLES: Set<Capability> =
    MATCH_MANAGEMENT_ROLES + Capability.POINTS_MANAGER + Capability.RATER + Capability.RESEARCHER

/**
 * Who may operate the Points Management surfaces (#472) — a points manager or an administrator.
 *
 * ADMINISTRATOR is listed because it is implicitly a points manager everywhere else in the product; the
 * tab's own gating says the same thing.
 */
val POINTS_MANAGEMENT_ROLES: Set<Capability> =
    setOf(Capability.POINTS_MANAGER, Capability.ADMINISTRATOR)
