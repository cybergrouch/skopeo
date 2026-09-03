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
 * had `private val STAFF_ROLES = HOST_OR_ADMIN` preserved the exact collision this file exists to remove.
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
 * `UserService`'s gate on player search and id-resolution — **HOST or ADMINISTRATOR, with no CLUB_OWNER.**
 *
 * Named for exactly what it contains rather than folded into [MATCH_MANAGEMENT_ROLES], because those are
 * not the same set and quietly making them so would be an authorization change smuggled into a rename.
 *
 * Whether the omission is deliberate is **an open question, tracked in #867**: a CLUB_OWNER without HOST
 * can reach the New Event form and event manager (#789) but gets 403 from the player picker inside them.
 * Resolve it there; this constant exists to keep the discrepancy visible until someone does.
 */
val HOST_OR_ADMIN: Set<Capability> =
    setOf(Capability.HOST, Capability.ADMINISTRATOR)
