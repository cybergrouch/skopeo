// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.security

/**
 * Authorization roles granted to a user (broad for now; devolvable to fine-grained capabilities later).
 * RATER may set initial ratings and triage rating work (#106); RESEARCHER gates the player-research
 * feature (#107); SCORER may umpire a live match (#911); ACCOUNT_MANAGER runs the Account Management
 * surfaces — invites, restoring deleted accounts, duplicate rectification (#1002); ADMINISTRATOR
 * implicitly has all of them.
 *
 * A cross-cutting value type in `common` so every layer — including `model`'s `User` aggregate and API
 * key scopes — can reference it without depending on `model`.
 *
 * **Adding a value here is not enough on its own.** `user_capabilities.chk_capability` enumerates the
 * permitted strings, so a grant of a new role is rejected by the database until a migration widens it —
 * `V60__account_manager_capability.sql` is the most recent example, and `CapabilityServiceTest` asserts
 * that every entry below can actually be granted, so the omission fails a test rather than production.
 *
 * **PLAYER is not a staff role**, and [org.skopeo.common.security.STAFF_ROLES] is defined by subtracting
 * it from these entries. Every signed-in user holds PLAYER, so anything derived from this enum that
 * forgets the subtraction grants the whole product to everybody.
 */
enum class Capability { PLAYER, HOST, CLUB_OWNER, ADMINISTRATOR, RATER, RESEARCHER, POINTS_MANAGER, SCORER, ACCOUNT_MANAGER }
