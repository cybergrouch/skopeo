// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service

import org.skopeo.domain.model.TeamType

/**
 * The dedicated group-classification component (#719). It owns **all** logic for mapping a player's
 * attributes + the match context to an opaque `group` label; the rating calculator never classifies —
 * it only compares two sides' groups for equality (the binary group-category factor). Keeping every
 * "what group is this?" decision here is what makes future extensions (age brackets, splitting mixed
 * doubles out by pair composition) a localized change that never touches the algorithm.
 *
 * Current strategy:
 * - **Singles** → the player's **sex** (later combined with age brackets). A `null`/blank sex yields a
 *   `null` group, which the calculator treats as the same group → factor 1 (backward compatible).
 * - **Doubles & mixed doubles** → a single shared [DOUBLES_GROUP], so any doubles match matches itself
 *   → factor 1. Mixed doubles is deliberately treated the same as ordinary doubles for now; splitting it
 *   out later is a change here alone.
 */
class GroupClassifier {
    /**
     * Derive the opaque [org.skopeo.domain.model.PlayerProfile.group] label for one player.
     *
     * @param sex the player's self-reported sex ("Male"/"Female"), or null if unspecified.
     * @param format the match's [TeamType] (singles vs. doubles/mixed doubles).
     */
    fun classify(
        sex: String?,
        format: TeamType,
    ): String? =
        when (format) {
            TeamType.SINGLES -> sex?.takeIf { it.isNotBlank() }
            TeamType.DOUBLES, TeamType.MIXED_DOUBLES -> DOUBLES_GROUP
        }

    companion object {
        /** The single shared group every doubles/mixed-doubles player is assigned to today. */
        const val DOUBLES_GROUP: String = "doubles"
    }
}
