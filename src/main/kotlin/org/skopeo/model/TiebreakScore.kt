package org.skopeo.model

import kotlinx.serialization.Serializable

/**
 * Represents a tiebreak score in a tennis set.
 *
 * Informational only: this object documents how many points the tiebreak was
 * (e.g. 7-5 or 10-8). The tiebreak is part of the set score, so its winner must be
 * the set winner (enforced by [SetScore]). Tiebreak points are NOT included in the
 * dominance calculation of the match — only games count toward dominance.
 *
 * @property points Map of team ID to points won in the tiebreak
 * @property winnerTeamId Team ID of the tiebreak winner; defaults to the team with the
 *   most points when omitted
 * @property loserTeamId Team ID of the tiebreak loser; defaults to the team with the
 *   fewest points when omitted
 */
@Serializable
data class TiebreakScore(
    val points: Map<String, Int>,
    val winnerTeamId: String = points.maxBy { it.value }.key,
    val loserTeamId: String = points.minBy { it.value }.key,
) {
    init {
        require(points.size == 2) { "Tiebreak must have exactly 2 teams" }
        require(winnerTeamId in points.keys) { "Tiebreak winner '$winnerTeamId' must be one of the teams in the tiebreak" }
        require(loserTeamId in points.keys) { "Tiebreak loser '$loserTeamId' must be one of the teams in the tiebreak" }
        require(loserTeamId != winnerTeamId) { "Tiebreak loser '$loserTeamId' must differ from the tiebreak winner '$winnerTeamId'" }

        val winnerPoints = points[winnerTeamId] ?: 0
        val loserPoints = points[loserTeamId] ?: 0

        require(winnerPoints >= 7) { "Tiebreak winner must have at least 7 points" }
        require(winnerPoints - loserPoints >= 2) { "Tiebreak must be won by at least 2 points" }
    }
}
