// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.security

/**
 * Authorization roles granted to a user (broad for now; devolvable to fine-grained capabilities later).
 * RATER may set initial ratings and triage rating work (#106); RESEARCHER gates the player-research
 * feature (#107); ADMINISTRATOR implicitly has both.
 *
 * A cross-cutting value type in `common` so every layer — including `model`'s `User` aggregate and API
 * key scopes — can reference it without depending on `model`.
 */
enum class Capability { PLAYER, HOST, CLUB_OWNER, ADMINISTRATOR, RATER, RESEARCHER, POINTS_MANAGER }
