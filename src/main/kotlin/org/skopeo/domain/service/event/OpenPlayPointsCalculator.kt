// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.event

import org.skopeo.common.contract.BandRelation
import org.skopeo.common.contract.OpenPlayMarginPoints
import org.skopeo.common.contract.OpenPlayPointsConfig
import org.skopeo.domain.model.MatchSetResult
import java.util.UUID

/**
 * Computes open-play ranking points (#525/#553, TOURNAMENTS_CIRCUITS_AND_OPEN_PLAY_POINTS.md §Part B).
 *
 * A pure function of the two teams' **entry bands**, the match's set results, and an admin-configurable
 * [OpenPlayPointsConfig] — no I/O — so it is unit-testable in isolation. Points are scored **per set and
 * summed**. Each set is dominance-scored by **game margin** (winner games − loser games; a tiebreak-only
 * set uses tiebreak points as games) and the band **relation** for that set:
 *  - equal bands → EQUAL
 *  - the higher-banded team won the set → FAVORITE
 *  - the lower-banded team won the set (upset) → UPSET
 *
 * The (relation, margin) cell of the config gives the winner's and loser's points. Loser totals can be
 * negative. The overall match winner is irrelevant — each set stands alone. Replaces the former binary
 * ALP (≥4 loser games) with configurable margin brackets (#553).
 */
internal object OpenPlayPointsCalculator {
    /** Each team's summed points for a match. */
    data class TeamPoints(
        val team1: Int,
        val team2: Int,
    )

    /**
     * Which side is which, for a match being scored.
     *
     * The two travel together because they are one question — *which side is this?* — and because
     * [concedingTeamId] is only meaningful relative to [team1Id]: the calculator infers team2 as "the
     * other one", so a conceding id has no reading without knowing which side team1 is.
     *
     * [concedingTeamId] is `null` for a match that played out, and otherwise the side that retired or
     * defaulted. `Match.concedingTeamId()` computes it. Deliberately not defaulted: a caller that
     * omitted it would skip the retirement rule and pay a retiring player for the set they walked out
     * of (#972), so the compiler asks at every call site instead.
     */
    data class Sides(
        val team1Id: UUID,
        val concedingTeamId: UUID?,
    )

    /**
     * One set's scoring, as the calculator saw it (#862).
     *
     * This exists so a derivation shown to a reader **is** the computation that paid them, not a parallel
     * reimplementation that can drift from it: [compute] is a fold over exactly these rows, so the two
     * cannot disagree about a margin, a relation, or a cell.
     */
    data class SetScoring(
        val setNumber: Int,
        val margin: Int,
        val relation: BandRelation,
        /** True when team1 won this set — which of [winnerPoints]/[loserPoints] each side received. */
        val team1WonSet: Boolean,
        val winnerPoints: Int,
        val loserPoints: Int,
    ) {
        /** What team1 earned from this set. */
        val team1Points: Int get() = if (team1WonSet) winnerPoints else loserPoints

        /** What team2 earned from this set. */
        val team2Points: Int get() = if (team1WonSet) loserPoints else winnerPoints
    }

    /**
     * Score each set, in order — the per-set detail behind a match's points (#862).
     *
     * @param band1 team1's entry band (e.g. "4.0"); [band2] team2's. Compared numerically.
     * @param sides which side is team1, and which (if any) conceded — see [Sides].
     * @param config the admin-configurable margin-bracket schedule.
     */
    fun scoreSets(
        band1: String,
        band2: String,
        sides: Sides,
        sets: List<MatchSetResult>,
        config: OpenPlayPointsConfig,
    ): List<SetScoring> {
        val team1Id = sides.team1Id
        val b1 = band1.toBigDecimal()
        val b2 = band2.toBigDecimal()
        val equalBands = b1.compareTo(other = b2) == 0
        val team1IsHigher = b1 > b2
        return sets.mapIndexed { index, set ->
            // `== team1Id` alone would read a WINNERLESS set (#968) as team2 having won it, and pay
            // them for a set nobody took. Handled explicitly below rather than relying on that compare.
            val team1WonSet = set.winnerTeamId == team1Id
            val higherWonSet = (team1WonSet && team1IsHigher) || (!team1WonSet && !team1IsHigher)
            val relation =
                when {
                    equalBands -> BandRelation.EQUAL
                    higherWonSet -> BandRelation.FAVORITE
                    else -> BandRelation.UPSET
                }
            val margin = marginInSet(set = set, team1WonSet = team1WonSet)
            val cell = config.cell(relation = relation, margin = margin)
            val payable =
                if (set.winnerTeamId == null) {
                    // Nobody won it, so nobody is paid for it — the same answer the retirement rule
                    // reaches for a level abandoned set, arrived at from the other direction.
                    cell.copy(winnerPoints = 0, loserPoints = 0)
                } else {
                    payableForAbandoned(
                        set = set,
                        team1WonSet = team1WonSet,
                        team1Id = team1Id,
                        conceding = sides.concedingTeamId,
                        cell = cell,
                    )
                }
            SetScoring(
                setNumber = index + 1,
                margin = margin,
                relation = relation,
                team1WonSet = team1WonSet,
                winnerPoints = payable.winnerPoints,
                loserPoints = payable.loserPoints,
            )
        }
    }

    /**
     * Each team's total for the match — a fold over [scoreSets], so the totals and the explanation are the
     * same computation (#862).
     */
    fun compute(
        band1: String,
        band2: String,
        sides: Sides,
        sets: List<MatchSetResult>,
        config: OpenPlayPointsConfig,
    ): TeamPoints {
        val scored =
            scoreSets(band1 = band1, band2 = band2, sides = sides, sets = sets, config = config)
        return TeamPoints(
            team1 = scored.sumOf { it.team1Points },
            team2 = scored.sumOf { it.team2Points },
        )
    }

    /** The set's game margin (winner games − loser games); a tiebreak-only set uses tiebreak points as games. */
    private fun marginInSet(
        set: MatchSetResult,
        team1WonSet: Boolean,
    ): Int {
        val winnerGames =
            effectiveGames(
                games = if (team1WonSet) set.team1Games else set.team2Games,
                tiebreak = if (team1WonSet) set.tiebreakTeam1Points else set.tiebreakTeam2Points,
                set = set,
            )
        val loserGames =
            effectiveGames(
                games = if (team1WonSet) set.team2Games else set.team1Games,
                tiebreak = if (team1WonSet) set.tiebreakTeam2Points else set.tiebreakTeam1Points,
                set = set,
            )
        return winnerGames - loserGames
    }

    /** A side's games in a set; a tiebreak-only set (both sides 0 games) uses that side's tiebreak points. */
    private fun effectiveGames(
        games: Int,
        tiebreak: Int?,
        set: MatchSetResult,
    ): Int = if (set.team1Games == 0 && set.team2Games == 0 && tiebreak != null) tiebreak else games

    /**
     * What an **abandoned** set pays (#972) — the retirement rule applied to the schedule cell.
     *
     * For the set play stopped during:
     *
     * - the **conceding side receives nothing** — not even the loser points an ordinary set pays them.
     *   A player must not bank a dominant set and then walk away with its points;
     * - the **opponent receives the normal margin-based winner points, but only if they were ahead on
     *   games**;
     * - level or behind, and **the set pays nobody**.
     *
     * Stated once, which is how it is implemented below: **an abandoned set pays only when the
     * designation and the games agree.** The designated match winner is always the non-conceding side,
     * so "the opponent was also ahead" is exactly "the games-derived set winner is not the conceder".
     *
     * `margin` and `relation` are left alone on purpose. They describe the tennis that was actually
     * played, so the derivation panel (#862) still explains the score honestly even where the payout is
     * zero — an unexplained zero is the failure #862 exists to prevent.
     *
     * The **rating never passes through here**: it reads the games-derived winner directly, because S10
     * rates a retirement on the real score. A player who retires while being outplayed still loses
     * rating for it; this only stops them being *paid* for the set they walked out of.
     *
     * Sets completed before the retirement are not marked [MatchSetResult.abandoned] and fall straight
     * through unchanged.
     */
    private fun payableForAbandoned(
        set: MatchSetResult,
        team1WonSet: Boolean,
        team1Id: UUID,
        conceding: UUID?,
        cell: OpenPlayMarginPoints,
    ): OpenPlayMarginPoints {
        // `conceding == null` means the match ended normally, so nothing here applies. Belt and braces:
        // a set can only be abandoned on a match that named a winner, but a caller that forgets to pass
        // it should under-apply the rule rather than mis-attribute a payout.
        if (!set.abandoned || conceding == null) return cell
        // Did the side that conceded also lead on games? team1 leads iff `team1WonSet`; the conceder is
        // team1 iff `conceding == team1Id`. Equality of those two is exactly "the conceder was ahead".
        val concederWasAhead = team1WonSet == (conceding == team1Id)
        return cell.copy(
            winnerPoints = if (concederWasAhead) 0 else cell.winnerPoints,
            loserPoints = 0,
        )
    }
}
