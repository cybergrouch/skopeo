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
    // Computed rating confidence as a 0..1 decimal string (#343); the UI shows it as a percentage.
    // 0 when the rating isn't match-derived (self-rating / override).
    val confidence: String? = null,
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
    // The player's *current* rating confidence (#343), a 0..1 decimal string shown as a percentage.
    val playerConfidence: String? = null,
)

/**
 * A page of a player's match history (#284): the requested slice plus [total], the count of matches
 * matching the (optional) search — for a bounded profile preview and a numbered full-history page.
 */
@Serializable
data class PlayerMatchHistoryPage(
    val items: List<PlayerMatchHistoryEntry>,
    val total: Int,
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
    // This participant's *current* rating confidence (#343), a 0..1 decimal string shown as a percentage.
    val confidence: String? = null,
)

/** A related player identified the same privacy-conscious way as a public profile (e.g. a merged card's canonical). */
@Serializable
data class OpponentSummary(
    val publicCode: String,
    val displayName: String?,
    val photoUrl: String?,
)

/**
 * A player's win–loss record over time (#276), aggregated server-side so it's independent of how
 * match history is listed/paginated. [singles] and [doubles] each hold one bucket per calendar month
 * that has a decided match (a recorded winner), oldest first. MIXED_DOUBLES counts as doubles.
 */
@Serializable
data class PlayerResultsSummary(
    val singles: List<ResultsBucket>,
    val doubles: List<ResultsBucket>,
)

/** Win/loss counts for one calendar month ([period] = "yyyy-MM"), from the viewed player's perspective. */
@Serializable
data class ResultsBucket(
    val period: String,
    val wins: Int,
    val losses: Int,
)

/**
 * A player's current competitive standing (#448) — their rank within their (band, sex) group and the
 * source-appropriate metric backing it (#457) — under the **active** `standings_source`. Rank + band
 * are public (#64/#114), so this renders on the anonymous public profile too. [source] tells the UI
 * which race this is and which metric to show: under POINTS [points] (a decimal string) is the snapshot
 * points total, present for every viewer; under RATING [rating] is the precise current rating, present
 * only for RATER/ADMINISTRATOR or the owner (#186) — omitted (null) for anonymous / other viewers, who
 * then see rank + band only. Only one of [points]/[rating] is set for a given source.
 */
@Serializable
data class PlayerStandingResponse(
    // The band's persisted code (e.g. "4.0") and its UI header label (e.g. "NTRP 4.0 Band Race").
    val band: String,
    val bandLabel: String,
    val sex: String?,
    val rank: Int,
    val source: String,
    val points: String? = null,
    val rating: String? = null,
)

/**
 * One ACTIVE ranking-point award on a player's own (or an admin's) points audit (#448): the [points],
 * their [band], and the [validUntil] expiry, plus a link to the fixture that granted them. [matchCode]
 * is the granting match's shareable code (→ `/matches/:code`); when an award predates the match link
 * (or is a manual grant) it is null and [eventCode] carries the event fallback (→ `/events/:code`).
 */
@Serializable
data class ActivePointsAwardResponse(
    val id: String,
    val points: String,
    val band: String,
    val pointClass: String,
    val validUntil: String,
    val matchCode: String? = null,
    val eventCode: String? = null,
)
