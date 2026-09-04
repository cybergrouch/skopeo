// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.ranking

import org.skopeo.common.contract.OpenPlayPointsConfig
import org.skopeo.common.dto.ranking.AwardDerivationResponse
import org.skopeo.common.dto.ranking.AwardPlacementDerivation
import org.skopeo.common.dto.ranking.AwardSetDerivation
import org.skopeo.domain.mapper.entity.match.toDomain
import org.skopeo.domain.model.Match
import org.skopeo.domain.model.PlacementBracket
import org.skopeo.domain.model.PointClass
import org.skopeo.domain.model.RankingPointAward
import org.skopeo.domain.service.event.OpenPlayPointsCalculator
import org.skopeo.domain.service.settings.PointsScheduleHistory
import org.skopeo.repository.ClubRepository
import org.skopeo.repository.EventRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.domain.mapper.entity.club.toDomain as toClubDomain
import org.skopeo.domain.mapper.entity.event.toDomain as toEventDomain

/** Zero-based placement-schedule indices, mirroring EventFinalizeAwarder's (#525). */
private const val PLACE_FIRST = 1
private const val PLACE_SECOND = 2

/**
 * Rebuilds how one award's amount was reached (#862) — the shared payload behind the Points Management
 * popup and the public match card (#858).
 *
 * **Derived, not stored.** The inputs are the schedule version the award records, the two band strings it
 * records, and the match's own set scores. Nothing is recomputed from *today's* schedule, which is the
 * whole reason versioning had to come first: current rates against a historical amount would produce a
 * confident explanation whose numbers do not add up to the figure beside it.
 *
 * The per-set arithmetic goes through [OpenPlayPointsCalculator.scoreSets] — the same function
 * `compute` folds over to pay the award. So a derivation cannot disagree with what was paid about a margin,
 * a band relation, or a cell.
 *
 * **An award that cannot be explained says so.** Anything written before #862 added the version and band
 * columns comes back `recorded = false` with a reason. Guessing would be worse than the gap.
 */
class AwardDerivationAssembler(
    private val matches: MatchRepository = MatchRepository(),
    private val events: EventRepository = EventRepository(),
    private val clubs: ClubRepository = ClubRepository(),
    private val schedules: PointsScheduleHistory = PointsScheduleHistory(),
) {
    fun derive(award: RankingPointAward): AwardDerivationResponse {
        val base =
            AwardDerivationResponse(
                awardId = award.id.toString(),
                points = award.points.toPlainString(),
                pointClass = award.pointClass.name,
                scheduleVersion = award.pointsScheduleVersion,
                recorded = false,
            )
        return when (award.pointClass) {
            // A manual or external grant was never computed from a schedule; the reason IS the derivation.
            PointClass.EXTERNAL -> base.copy(recorded = true, reason = award.reason ?: "Manual grant")
            PointClass.ANNUAL_TOURNAMENT -> tournamentDerivation(award = award, base = base)
            PointClass.OPEN_PLAY, PointClass.FULL_MATCH -> perSetDerivation(award = award, base = base)
        }
    }

    /**
     * A tournament award is either a placement (paid for a placing) or a non-placement fixture paid on the
     * per-set schedule (#836) — the point class alone cannot tell them apart, so the match decides.
     */
    private fun tournamentDerivation(
        award: RankingPointAward,
        base: AwardDerivationResponse,
    ): AwardDerivationResponse {
        val match = award.matchId?.let { matches.findById(matchId = it).getOrNull()?.toDomain() }
        return if (match?.isPlacementMatch == true) {
            placementDerivation(award = award, match = match, base = base)
        } else {
            perSetDerivation(award = award, base = base)
        }
    }

    private fun placementDerivation(
        award: RankingPointAward,
        match: Match,
        base: AwardDerivationResponse,
    ): AwardDerivationResponse {
        val schedule = schedules.schedulesAtVersion(version = award.pointsScheduleVersion).tournament
        val bracket = match.placementBracket
        // Single exit: whichever input is missing, the answer is the same shape — an unexplained award
        // that still reports its amount.
        return when {
            schedule == null -> base.copy(unavailableReason = SCHEDULE_GONE)
            bracket == null -> base.copy(unavailableReason = "This fixture records no placement bracket.")
            else -> {
                // Which place this award paid: the winner takes the bracket's higher place, the loser the
                // lower. Read from the match rather than stored, because the placing is a property of the
                // fixture (#837).
                val wonMatch = match.winnerTeamId != null && playerWon(award = award, match = match)
                val place =
                    when (bracket) {
                        PlacementBracket.CHAMPIONSHIP_FINALS -> if (wonMatch) PLACE_FIRST else PLACE_SECOND
                        PlacementBracket.PLATE_FINALS -> if (wonMatch) PLACE_THIRD else PLACE_FOURTH
                    }
                val sanctioned = sanctionedFor(award = award)
                base.copy(
                    recorded = true,
                    placement =
                        AwardPlacementDerivation(
                            place = place,
                            sanctioned = sanctioned,
                            scheduleAmount = schedule.schedule(sanctioned = sanctioned).getOrElse(index = place - 1) { 0 },
                        ),
                )
            }
        }
    }

    /**
     * Per-set derivation, for open play, Full Match, and a tournament's non-placement fixtures (#836).
     *
     * Needs all three of: the schedule version's open-play table, the two recorded band strings, and the
     * match with its sets. Any one missing means the award predates #862 or its match is gone — reported,
     * never approximated.
     */
    private fun perSetDerivation(
        award: RankingPointAward,
        base: AwardDerivationResponse,
    ): AwardDerivationResponse {
        val teamBand = award.teamBand
        val opponentBand = award.opponentBand
        val match = award.matchId?.let { matches.findById(matchId = it).getOrNull()?.toDomain() }
        val config = schedules.schedulesAtVersion(version = award.pointsScheduleVersion).openPlay
        // Single exit. The order of these branches is the order a reader would ask them in: were the
        // inputs recorded at all, is the fixture still here, is the schedule still here.
        return when {
            teamBand == null || opponentBand == null -> base.copy(unavailableReason = BANDS_NOT_RECORDED)
            match == null -> base.copy(unavailableReason = MATCH_GONE)
            config == null -> base.copy(unavailableReason = SCHEDULE_GONE)
            else ->
                explainSets(
                    award = award,
                    base = base,
                    inputs =
                        PerSetInputs(match = match, teamBand = teamBand, opponentBand = opponentBand, config = config),
                )
        }
    }

    /**
     * The per-set arithmetic itself, once every input is known to be present.
     *
     * Split from [perSetDerivation] so that function is nothing but the "can this be explained" decision —
     * the two were one body whose guards and arithmetic had to be read together to see either.
     */
    private fun explainSets(
        award: RankingPointAward,
        base: AwardDerivationResponse,
        inputs: PerSetInputs,
    ): AwardDerivationResponse {
        val match = inputs.match
        val config = inputs.config
        // Score in the MATCH's own team order, then read the recipient's figures out of the result.
        //
        // Relabelling the input instead — passing the recipient's team as `team1Id` — is wrong and quietly
        // so: each set's `team1Games`/`team2Games` are recorded in the match's order, so the calculator
        // would read the opponent's games as the recipient's and produce a NEGATIVE margin. Perspective
        // belongs on the way out, not the way in.
        val recipientIsTeam1 = award.userId in match.team1.userIds
        val scored =
            OpenPlayPointsCalculator.scoreSets(
                band1 = if (recipientIsTeam1) inputs.teamBand else inputs.opponentBand,
                band2 = if (recipientIsTeam1) inputs.opponentBand else inputs.teamBand,
                team1Id = match.team1.teamId,
                sets = match.sets,
                config = config,
            )
        val sets =
            scored.map { set ->
                val setResult = match.sets.firstOrNull { it.setNumber == set.setNumber }
                AwardSetDerivation(
                    setNumber = set.setNumber,
                    score = setResult?.let { scoreOf(set = it, playerSideIsTeam1 = recipientIsTeam1) }.orEmpty(),
                    margin = set.margin,
                    relation = set.relation.name,
                    // Both flipped to the recipient's side of the net, so "won" and "my points" read as
                    // they experienced the match rather than as team1 did.
                    wonSet = set.team1WonSet == recipientIsTeam1,
                    winnerPoints = set.winnerPoints,
                    loserPoints = set.loserPoints,
                    pointsForThisPlayer = if (recipientIsTeam1) set.team1Points else set.team2Points,
                )
            }
        // The award's own reason is carried through (#881): a zero produced by the calibration clamp is not
        // what the schedule arithmetic below computes, so without it the derivation would contradict the
        // amount beside it — the exact failure #862 exists to prevent.
        return base.copy(
            recorded = true,
            sets = sets,
            teamBand = inputs.teamBand,
            opponentBand = inputs.opponentBand,
            reason = award.reason,
        )
    }

    /** The set score from the recipient's side first, e.g. "6-4". */
    private fun scoreOf(
        set: org.skopeo.domain.model.MatchSetResult,
        playerSideIsTeam1: Boolean,
    ): String =
        if (playerSideIsTeam1) {
            "${set.team1Games}-${set.team2Games}"
        } else {
            "${set.team2Games}-${set.team1Games}"
        }

    /** Whether the award's recipient was on the winning side of [match]. */
    private fun playerWon(
        award: RankingPointAward,
        match: Match,
    ): Boolean {
        val side = if (award.userId in match.team1.userIds) match.team1 else match.team2
        return match.winnerTeamId == side.teamId
    }

    /** Whether the awarding event's club had tournaments sanctioned — the ×10 + 200 column (#525). */
    private fun sanctionedFor(award: RankingPointAward): Boolean {
        val event = award.eventId?.let { events.findById(id = it)?.toEventDomain() } ?: return false
        return clubs.findById(id = event.clubId)?.toClubDomain()?.tournamentsSanctioned ?: false
    }

    private companion object {
        const val PLACE_THIRD = 3
        const val PLACE_FOURTH = 4
        const val BANDS_NOT_RECORDED =
            "This award predates the change that records how amounts are derived, so its inputs were never stored."
        const val SCHEDULE_GONE = "The schedule version this award was paid under is no longer available."
        const val MATCH_GONE = "The granting fixture is no longer available."
    }
}

/**
 * The four inputs a per-set derivation needs, once [AwardDerivationAssembler] has established that all of
 * them are present. Bundled rather than passed loose: they are always established together and always
 * consumed together, so a caller that has one has all four.
 */
private data class PerSetInputs(
    val match: Match,
    val teamBand: String,
    val opponentBand: String,
    val config: OpenPlayPointsConfig,
)
