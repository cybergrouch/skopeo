// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.dto.match

import kotlinx.serialization.Serializable

/**
 * The fewest games a side needs to win a set (#213). Standard tennis is 6; Skopeo allows a lower
 * floor of 4 so Hosts can run shortened, schedule-driven formats. Tiebreak-decided sets are exempt.
 */
private const val MIN_GAMES_TO_WIN = 4

/**
 * Validate a per-side handicap (#486): when present it must parse and lie in `0 < h <= 1.0`
 * (team-mean NTRP units). Null = no handicap. Throws IllegalArgumentException (mapped to 400).
 */
internal fun validateHandicap(raw: String?) {
    raw?.let {
        val h = requireNotNull(value = it.toDoubleOrNull()) { "handicap must be a valid number, got '$it'" }
        require(value = h > 0.0 && h <= 1.0) { "handicap must be in the range 0 < h <= 1.0, got $it" }
    }
}

/** Body for `POST /api/v1/matches` — create a fixture (no results yet). */
@Serializable
data class CreateFixtureRequest(
    val matchFormat: String,
    val matchType: String,
    val matchDate: String,
    // A side is specified EITHER as raw player ids ([team1]/[team2]) OR as a durable event team ref
    // ([team1Id]/[team2Id], #720) — not both. A team ref resolves to that team's members (in slot order)
    // and requires an [eventId]; the team's size must match the fixture's effective format.
    val team1: List<String> = emptyList(),
    val team2: List<String> = emptyList(),
    // Durable event-team refs (#720); alternative to the raw player-id lists above.
    val team1Id: String? = null,
    val team2Id: String? = null,
    val venue: String? = null,
    val tournamentName: String? = null,
    /** When set, the fixture belongs to this event and both sides must be participants (#138). */
    val eventId: String? = null,
    /**
     * Optional per-side rating handicap (#486) in team-mean NTRP units, `0 < h <= 1.0`; null = none.
     * Deducted from that side's rating for the rating-delta computation only; the delta is applied to the
     * players' true ratings. See RATING_HANDICAP.md.
     */
    val team1Handicap: String? = null,
    val team2Handicap: String? = null,
    /**
     * Tournament placement match (#525): when true this fixture decides a placement, and
     * [placementBracket] says which — "CHAMPIONSHIP_FINALS" (1st/2nd) or "PLATE_FINALS" (3rd/4th).
     */
    val isPlacementMatch: Boolean = false,
    val placementBracket: String? = null,
) {
    init {
        validateHandicap(raw = team1Handicap)
        validateHandicap(raw = team2Handicap)
    }
}

@Serializable
data class SetScoreRequest(
    val team1Games: Int,
    val team2Games: Int,
    val tiebreakTeam1Points: Int? = null,
    val tiebreakTeam2Points: Int? = null,
) {
    init {
        // Shape validation at the boundary (#116): games can never be negative.
        require(value = team1Games >= 0 && team2Games >= 0) { "games must be non-negative" }
        // A set decided on games must be won with at least MIN_GAMES_TO_WIN games (#213). Sets with
        // equal games are decided by the tiebreak (e.g. a match tiebreak) and are exempt from the floor.
        if (team1Games != team2Games) {
            val winnerGames = if (team1Games > team2Games) team1Games else team2Games
            require(value = winnerGames >= MIN_GAMES_TO_WIN) {
                "a set won on games must be won with at least $MIN_GAMES_TO_WIN games"
            }
        }
    }
}

/** Body for `POST /api/v1/matches/{id}/result` — upload the set scores. */
@Serializable
data class MatchResultRequest(
    val sets: List<SetScoreRequest>,
) {
    init {
        // Shape validation at the boundary (#116): a result must report at least one set.
        require(value = sets.isNotEmpty()) { "at least one set is required" }
    }
}

/** Body for `PUT /api/v1/matches/{id}/state` — enable/disable (append-only corrections). */
@Serializable
data class MatchStateRequest(
    val isActive: Boolean,
)

/**
 * Body for `PUT /api/v1/matches/{id}/handicaps` — set (or clear) a fixture's per-side handicaps (#486).
 * Each is a team-mean NTRP-unit value in `0 < h <= 1.0`, or null to clear that side. Only settable while
 * the match is unrated.
 */
@Serializable
data class SetHandicapsRequest(
    val team1Handicap: String? = null,
    val team2Handicap: String? = null,
) {
    init {
        validateHandicap(raw = team1Handicap)
        validateHandicap(raw = team2Handicap)
    }
}

/**
 * Body for `PUT /api/v1/matches/calculation-order` (#331/#332): the desired processing order of a
 * group of same-date matches. Each id is assigned calc_sequence = its index.
 */
@Serializable
data class ReorderMatchesRequest(
    val matchIds: List<String>,
)

@Serializable
data class MatchSideResponse(
    val teamId: String,
    val userIds: List<String>,
)

@Serializable
data class MatchSetResponse(
    val setNumber: Int,
    val team1Games: Int,
    val team2Games: Int,
    val winnerTeamId: String,
    val tiebreakTeam1Points: Int? = null,
    val tiebreakTeam2Points: Int? = null,
)

@Serializable
data class MatchResponse(
    val id: String,
    val publicCode: String,
    val matchFormat: String,
    val matchType: String,
    val matchDate: String,
    val status: String,
    val team1: MatchSideResponse,
    val team2: MatchSideResponse,
    val winnerTeamId: String? = null,
    val sets: List<MatchSetResponse>,
    val venue: String? = null,
    val tournamentName: String? = null,
    val isActive: Boolean,
    val completedAt: String? = null,
    val ratedAt: String? = null,
    val createdBy: String? = null,
    val recordedBy: String? = null,
    val eventId: String? = null,
    // Per-side rating handicap (#486) in team-mean NTRP units; null = none. Shown for transparency.
    val team1Handicap: String? = null,
    val team2Handicap: String? = null,
)

/** One player on the public match page (#136): just a display name + shareable code, no ids/contacts. */
@Serializable
data class MatchPublicPlayer(
    val displayName: String? = null,
    val publicCode: String? = null,
    // True for a login-less, not-yet-claimed placeholder ("dummy") player (#496/#505): the roster
    // renders an "Unclaimed" tag beside the name. Real/claimed players leave it false.
    val isPlaceholder: Boolean = false,
    // True for an admin-soft-deleted account (#518): the roster renders a dominant "Deleted" chip.
    val isDeleted: Boolean = false,
)

/**
 * A caller's upcoming (scheduled, not-yet-played) match for their private profile (#251): the
 * opponent(s) + date, linking to the public match page by [publicCode]. Owner-only.
 */
@Serializable
data class UpcomingMatchResponse(
    val publicCode: String,
    val matchDate: String,
    val matchType: String,
    val venue: String? = null,
    val opponents: List<MatchPublicPlayer>,
)

/** A set's score on the public match page, expressed per side (no internal team ids). */
@Serializable
data class MatchPublicSet(
    val setNumber: Int,
    val team1Games: Int,
    val team2Games: Int,
    val tiebreakTeam1Points: Int? = null,
    val tiebreakTeam2Points: Int? = null,
)

/**
 * Read-only public summary of a match (#136), addressed by its public code. Players are resolved to
 * display name + code (so the page can link to their public profiles); the winner is named by side.
 */
@Serializable
data class MatchPublicResponse(
    // The internal match id, revealed to ADMINISTRATOR viewers only (#776) so the public page's
    // score-correction action can address the match; null for every other viewer.
    val id: String? = null,
    val publicCode: String,
    val matchFormat: String,
    val matchType: String,
    val matchDate: String,
    val status: String,
    // True once the rating calculation has committed this match (#361): lets a list view derive the
    // Scheduled / Awaiting rating / Rated status without the precise rating changes. On the standalone
    // match page the [ratingChanges] carry the detail; in an event's match list they aren't populated.
    val rated: Boolean = false,
    // True once an ADMINISTRATOR has corrected this match's score AFTER it was rated (#776), reversing and
    // re-applying the rating deltas. Public on purpose: a transparency signal that the recorded score
    // changed, shown to everyone rather than only to staff.
    val reRated: Boolean = false,
    // False once the match has been soft-deleted (#325): its link stays honored for traceability, and
    // the public page flags it as deleted.
    val isActive: Boolean = true,
    val team1: List<MatchPublicPlayer>,
    val team2: List<MatchPublicPlayer>,
    // The winning side, named relative to team1/team2: "TEAM1" | "TEAM2" | "NONE".
    val winner: String,
    val sets: List<MatchPublicSet>,
    val venue: String? = null,
    val tournamentName: String? = null,
    // Per-player rating change, present only once the match is rated. The NTRP bands (previous/new
    // level) are shown to everyone; the precise rates are populated only for RATER/ADMINISTRATOR viewers.
    val ratingChanges: List<MatchPublicRatingChange>? = null,
    // Prior meetings between the same two players (#188); null when there are none or the match is not
    // singles. Wins and set scores are oriented to team1/team2 of THIS match.
    val headToHead: MatchPublicHeadToHead? = null,
    // The event this match belongs to (#358), resolved to its shareable code + name so the page can
    // link to the event's public page. Null/omitted for eventless (open-play) matches.
    val event: MatchPublicEvent? = null,
    // Per-side rating handicap (#486), shown transparently to participants; null = none on that side.
    val team1Handicap: String? = null,
    val team2Handicap: String? = null,
)

/**
 * The event a match belongs to (#358), for a link from the public match page to the event's public
 * page. Only the non-sensitive shareable code + display name are exposed.
 */
@Serializable
data class MatchPublicEvent(
    val publicCode: String,
    val name: String,
)

/**
 * Head-to-head record between the two players of a singles match (#188): the win tally and the prior
 * completed meetings, newest first. [team1Wins]/[team2Wins] and each meeting's set scores are oriented
 * to team1/team2 of the match being viewed, so the orientation is stable across rows.
 */
@Serializable
data class MatchPublicHeadToHead(
    val team1Wins: Int,
    val team2Wins: Int,
    val meetings: List<MatchPublicHeadToHeadEntry>,
)

/**
 * One prior meeting in a head-to-head record (#188). [sets] are oriented to team1/team2 of the match
 * being viewed; [winnerPublicCode] is the winning player's code (one of the two), or null if undecided.
 * [matchFormat] (SINGLES/DOUBLES/MIXED_DOUBLES) lets the card show whether the meeting was singles or
 * doubles (#285).
 */
@Serializable
data class MatchPublicHeadToHeadEntry(
    val publicCode: String,
    val matchDate: String,
    val status: String,
    val rated: Boolean,
    val matchFormat: String,
    val sets: List<MatchPublicSet>,
    val winnerPublicCode: String? = null,
)

/**
 * One player's rating change for a rated match (#136). [previousLevel]/[newLevel] are the public NTRP
 * bands (shown to everyone). [previousRating]/[newRating]/[ratingChange] are the precise values (NUMERIC
 * 10,6 → 6 fractional digits) and are null unless the viewer is a RATER or ADMINISTRATOR. [confidence]
 * is the player's *current* rating confidence (#343), a 0..1 decimal string shown as a percentage.
 */
@Serializable
data class MatchPublicRatingChange(
    val publicCode: String? = null,
    val displayName: String? = null,
    val previousLevel: String? = null,
    val newLevel: String? = null,
    val previousRating: String? = null,
    val newRating: String? = null,
    val ratingChange: String? = null,
    val confidence: String? = null,
    // True for a login-less, not-yet-claimed placeholder ("dummy") player (#496/#505): the rating-change
    // row renders an "Unclaimed" tag beside the name. Real/claimed players leave it false.
    val isPlaceholder: Boolean = false,
    // True for an admin-soft-deleted account (#518): the rating-change row renders a dominant "Deleted" chip.
    val isDeleted: Boolean = false,
)
