// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.dto.user

import kotlinx.serialization.Serializable

/**
 * A privacy-conscious player card resolved from a shareable public code (issue #61) — visible to
 * any authenticated user via the deep link. Deliberately omits contacts/date-of-birth; [email] is
 * the one reveal-gated PII field (#630), populated only for the owner or an elevated viewer.
 */
@Serializable
data class PublicPlayerResponse(
    val publicCode: String,
    val displayName: String?,
    val photoUrl: String?,
    val rating: PublicRatingDto?,
    // The player's registered email (#630) — PII, revealed server-side only to the profile owner or a
    // HOST/CLUB_OWNER/RATER/ADMINISTRATOR viewer; null/omitted for other players and anonymous callers.
    val email: String? = null,
    // Set when this profile was marked a duplicate and disabled (#124): the page shows a "merged"
    // notice and [canonical] links to the true account. Active profiles leave these at the defaults.
    val isDisabled: Boolean = false,
    val canonical: OpponentSummary? = null,
    // Set for a login-less, not-yet-claimed placeholder ("dummy") player (#496): the public page shows an
    // "unclaimed" indicator + a "Claim this account" entry point. Real/claimed profiles leave it false.
    val isPlaceholder: Boolean = false,
    // True for an admin-soft-deleted account (#518): the public page shows a dominant "Deleted" chip.
    val isDeleted: Boolean = false,
    // #622: the owner has opted to hide their match history from other players. Lets the owner's own
    // profile view show a "hidden from others" banner; unprivileged viewers get an empty history.
    val matchHistoryHidden: Boolean = false,
)

@Serializable
data class PublicRatingDto(
    // The raw NTRP rating (full precision) — ADMINISTRATOR-only (#583); null for every other viewer,
    // who sees only the band [level] + [confidence] (+ the in-band speedometer).
    val value: String? = null,
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
    // The player's raw NTRP rating at the time of the match — a raw-NTRP reveal (#583/#654), populated
    // only for a viewer who may see raw ratings; null otherwise (band [playerLevelAtMatch] only).
    val playerRatingAtMatch: String? = null,
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
    // #622: true when the owner hid their match history and the viewer is not privileged — the list is
    // deliberately empty (not "no matches"), so the UI can render a "hidden by this player" notice.
    val hidden: Boolean = false,
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
    // The raw NTRP rating (full precision) at the time of the match — a raw-NTRP reveal (#583/#654),
    // populated only for a viewer who may see raw ratings (an admin not previewing as non-admin); null
    // for everyone else, who sees the band [levelAtMatch] only.
    val ratingAtMatch: String? = null,
    // This participant's *current* rating confidence (#343), a 0..1 decimal string shown as a percentage.
    val confidence: String? = null,
    // True for a login-less, not-yet-claimed placeholder ("dummy") player (#496/#505): the history row
    // renders an "Unclaimed" tag beside the name. Real/claimed players leave it false.
    val isPlaceholder: Boolean = false,
    // True for an admin-soft-deleted account (#518): the history row renders a dominant "Deleted" chip.
    val isDeleted: Boolean = false,
)

/** A related player identified the same privacy-conscious way as a public profile (e.g. a merged card's canonical). */
@Serializable
data class OpponentSummary(
    val publicCode: String,
    val displayName: String?,
    val photoUrl: String?,
)

/**
 * A player's win–loss record (#276), aggregated server-side so it is independent of how match history is
 * listed or paginated — and, since #845, **fully assembled**: every figure the card renders is computed
 * here, so the client presents rather than calculates.
 *
 * [singles]/[doubles]/[overall] cover every decided match (a recorded winner). [opponentBands] is a
 * narrower cut — **singles only, and rated matches only** — because classifying an opponent needs both
 * sides' band *at match time*, which exists only once a match has been rated. Its counts therefore do not
 * add up to [singles], by design; the UI states both limits rather than implying a discrepancy.
 */
@Serializable
data class PlayerResultsSummary(
    val singles: ResultsTotals,
    val doubles: ResultsTotals,
    val overall: ResultsTotals,
    /** One entry per [OpponentBand], always all three, in SAME → HIGHER → LOWER order. */
    val opponentBands: List<OpponentBandSeries>,
    /** How many trailing months [OpponentBandSeries.monthly] covers, so the UI can label the window. */
    val monthsWindow: Int,
    /**
     * The largest single-month total across every relation — the **shared y-scale** for the sparklines.
     * Computed here because scaling each panel to its own max would draw 8 matches and 2 the same height
     * and invert the comparison the layout exists to enable.
     */
    val monthlyMax: Int,
)

/** Finished win–loss figures for one cut of a player's matches (#845): nothing left for the UI to derive. */
@Serializable
data class ResultsTotals(
    val played: Int,
    val wins: Int,
    val losses: Int,
    /**
     * Whole-percent win rate, or **null** when nothing is decided — the UI renders null as "n/a" rather
     * than branching on a zero denominator itself.
     */
    val winRate: Int?,
)

/**
 * The viewed player's band at match time versus their opponent's (#845). Distinct from
 * `contract.BandRelation`, which classifies a *result* by who won (EQUAL/FAVORITE/UPSET) — this
 * classifies the *matchup* from the viewed player's side, regardless of outcome.
 */
@Serializable
enum class OpponentBand {
    /** Opponent in the same band. */
    SAME,

    /** Opponent in a higher band than the viewed player. */
    HIGHER,

    /** Opponent in a lower band. */
    LOWER,
}

/**
 * One band relation, carrying both of its visualisations (#845): the totals behind its donut segments and
 * the monthly series behind its sparkline. Bundled per relation so the client maps one object to one
 * panel and reshapes nothing.
 */
@Serializable
data class OpponentBandSeries(
    val relation: OpponentBand,
    val totals: ResultsTotals,
    /**
     * Trailing months, oldest first, **gap-filled** — a month with no play is present with zeroes rather
     * than absent, so an absence renders as a flat gap instead of silently compressing the timeline.
     */
    val monthly: List<ResultsBucket>,
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

/**
 * A player's active ranking points, **grouped by the band they were earned in** (#882).
 *
 * Points count only while their band tag matches the player's current level (#403 decision #2), so a
 * player who changes bands keeps points that no longer count. Reporting a flat list made the profile
 * contradict itself — it showed the awards as live while the Ranking section on the same page said
 * "Unranked". This shape states the relationship instead of leaving the reader to infer it.
 *
 * [currentBand] is null when the player has no rating, in which case nothing counts and every group is
 * latent.
 */
@Serializable
data class PlayerPointsByBandResponse(
    val currentBand: String? = null,
    /** The player's current band: their counting total, **present even when it is zero** — see below. */
    val current: PlayerPointsBandGroup? = null,
    /**
     * Bands the player holds points in but is no longer competing in — promoted *or* demoted. Empty when
     * there are none. Only bands holding points appear; the ladder is not enumerated.
     */
    val latent: List<PlayerPointsBandGroup> = emptyList(),
    /** Every band's points added up, counting and latent alike — what the player has ever been awarded. */
    val totalPoints: String,
)

/**
 * One band's worth of a player's points (#882).
 *
 * [counting] is the server's answer, not the client's inference: only the backend knows the band-scoped
 * counting rule, and having the UI re-derive it is how the profile and the standings drifted apart in the
 * first place.
 *
 * The **current** band's group is emitted even when [totalPoints] is "0" and [awards] is empty, because
 * that is the whole explanation for an unranked player who visibly holds points elsewhere. Latent groups
 * are only emitted when they actually hold something.
 */
@Serializable
data class PlayerPointsBandGroup(
    val band: String,
    val counting: Boolean,
    val totalPoints: String,
    val awards: List<ActivePointsAwardResponse> = emptyList(),
)
