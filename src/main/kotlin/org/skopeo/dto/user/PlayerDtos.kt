// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto.user

import kotlinx.serialization.Serializable

/**
 * A privacy-conscious player card resolved from a shareable public code (issue #61) — visible to
 * any authenticated user via the deep link. Deliberately omits email/contacts/date-of-birth.
 */
@Serializable
data class PublicPlayerResponse(
    val publicCode: String,
    val displayName: String?,
    val photoUrl: String?,
    val rating: PublicRatingDto?,
    // Set when this profile was marked a duplicate and disabled (#124): the page shows a "merged"
    // notice and [canonical] links to the true account. Active profiles leave these at the defaults.
    val isDisabled: Boolean = false,
    val canonical: OpponentSummary? = null,
)

@Serializable
data class PublicRatingDto(
    val value: String,
    val level: String?,
)

/**
 * One row of a player's match history (issue #65), shown on their own Profile tab and on the
 * shareable public profile alike. Ratings are surfaced only as the published NTRP band
 * ([playerLevelAtMatch] and each participant's [MatchHistoryParticipant.levelAtMatch] — the level
 * at the time the match was rated), never the precise value. [rated] indicates the match has been
 * calculated and contributes to the current rating; scheduled and unrated-completed matches carry
 * null levels. [partners] is empty for singles and holds the teammate(s) for doubles; [opponents]
 * holds the opposing side (one player for singles, two for doubles).
 */
@Serializable
data class PlayerMatchHistoryEntry(
    val matchId: String,
    // The match's shareable public code (#136) — lets the UI link a history row to its public match page.
    val publicCode: String,
    val matchDate: String,
    val status: String,
    val rated: Boolean,
    val result: String?,
    val setScores: List<String>,
    val partners: List<MatchHistoryParticipant>,
    val opponents: List<MatchHistoryParticipant>,
    val playerLevelAtMatch: String?,
)

/**
 * A teammate or opponent on a match-history row (#256) — identified the same privacy-conscious way
 * as a public profile, plus their published NTRP band at the time of the match ([levelAtMatch] is
 * null for scheduled or unrated matches).
 */
@Serializable
data class MatchHistoryParticipant(
    val publicCode: String,
    val displayName: String?,
    val photoUrl: String?,
    val levelAtMatch: String?,
)

/** A related player identified the same privacy-conscious way as a public profile (e.g. a merged card's canonical). */
@Serializable
data class OpponentSummary(
    val publicCode: String,
    val displayName: String?,
    val photoUrl: String?,
)
