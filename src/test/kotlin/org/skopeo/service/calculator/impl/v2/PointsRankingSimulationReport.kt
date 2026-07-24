// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.calculator.impl.v2

import java.io.File
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Monte Carlo study of the ranking-POINTS design (#525) — a runnable report, not a test.
 *
 * The open-play + tournament points formulas are **design-only** (see
 * docs/product/TOURNAMENTS_CIRCUITS_AND_OPEN_PLAY_POINTS.md); this report encodes them standalone
 * (exactly like the rating sims encode the rating algorithm) and measures the **expected
 * steady-state leaderboard score** — the sum of a player's still-valid (un-expired) points at a
 * random instant in steady state — for a grid of player archetypes and points-validity settings.
 *
 * Two independent player axes:
 *  - **Skill class** (win rate on open play, chance of a tournament placement): Below / Even / Above.
 *  - **Behaviour class** (attendance cadence): open-play-only, tournaments-only, balanced, heavy-open.
 *
 * Validity swept: open play **1 vs 2 months**; tournament **3 / 6 / 12 months**.
 *
 * Modelling assumptions (documented in the study; the relative comparisons are robust to them):
 *  - Open play is single-set. Per match the player is EQUAL-band with p=0.40, the higher-band
 *    ("favorite") with p=0.30, or the lower-band ("underdog") with p=0.30. Win/loss is a Bernoulli
 *    draw at the class win rate, independent of the band relation. On a loss in an unequal match the
 *    loser clears the ALP games threshold (≥4) with p=0.50.
 *  - A tournament yields a placement (points) with probability = the class placement chance; given a
 *    placement it is 1st/2nd/3rd/4th with p = 0.10/0.20/0.30/0.40. Points use the SANCTIONED table
 *    (80/60/40/30); unsanctioned is exactly half, so every sanctioned figure below halves for it.
 *  - Events occur at a fixed cadence (randomness is in outcomes, not timing); the score is read at a
 *    uniformly-random instant in a steady window well past the longest validity.
 *
 * Part 2 (#530) adds population point-spread & collisions over time; Part 3 (#530) evaluates an
 * alternative open-play scheme — game-margin Fibonacci, fib(2+margin) to the set winner — head-to-head
 * against the current band-difference scheme on the same population.
 *
 * Output: /tmp/points_ranking.txt and presentations/points_ranking.md (git-ignored). The curated
 * tables live in docs/product/POINTS_RANKING_SIMULATION_STUDY.md.  Run: ./gradlew generatePointsSimulationReport
 *
 * A large, self-contained simulation report (test-only), like the sibling *Report classes; the size
 * and a couple of wide scenario helpers are inherent to the tabulation — hence the class-level suppressions.
 */
@Suppress("LargeClass", "LongParameterList")
class PointsRankingSimulationReport {
    companion object {
        private const val SEED = 20_260_724L
        private const val TRIALS = 40_000

        // Timeline (days). Horizon is 3 years; the score is sampled in the final year so every
        // validity window (≤ 12 months) is fully warmed.
        private const val HORIZON_DAYS = 1_095.0
        private const val STEADY_WINDOW_DAYS = 365.0

        // Event cadence (days between events).
        private const val WEEKLY = 7.0
        private const val TWICE_WEEKLY = 3.5
        private const val EVERY_TWO_MONTHS = 61.0

        // Validity windows (days). One month is taken as 30/31 days; two months as 61.
        private const val V_1_MONTH = 30
        private const val V_2_MONTHS = 61
        private const val V_3_MONTHS = 91
        private const val V_6_MONTHS = 183
        private const val V_12_MONTHS = 365

        // Default validity policy used for the combined-archetype table.
        private const val DEFAULT_OPEN_VALIDITY = V_2_MONTHS
        private const val DEFAULT_TOURNEY_VALIDITY = V_6_MONTHS

        // Open-play band-relation mix (player vs opponent).
        private const val P_EQUAL = 0.40
        private const val P_PLAYER_HIGHER = 0.30 // remainder (0.30) = player is the lower band
        private const val P_ALP = 0.50 // loser clears the ≥4-games ALP threshold

        // Generalized open-play parameters (from the design doc's parameter table).
        private const val WIN_EQUAL = 3.0
        private const val WIN_FAVORITE = 2.0
        private const val WIN_UPSET = 5.0
        private const val RLP_EQUAL = 0.0
        private const val RLP_FAVORITE = 1.0
        private const val RLP_UPSET = -2.0
        private const val ALP_AWARD = 1.0

        // Tournament placement points (sanctioned) for 1st..4th, and the placement distribution.
        private val SANCTIONED_PLACEMENT = doubleArrayOf(80.0, 60.0, 40.0, 30.0)
        private val PLACEMENT_DIST = doubleArrayOf(0.10, 0.20, 0.30, 0.40)

        // Best-case points per event, for the absolute ceiling (open: an upset win; tournament: 1st).
        private const val OPEN_MAX_PER_MATCH = WIN_UPSET
        private val TOURNEY_MAX_PER_EVENT = SANCTIONED_PLACEMENT.first()

        private val PERCENTILES = listOf(5, 50, 95)
        private const val PERCENT = 100.0
        private const val EVENT_SAMPLES = 400_000 // for the per-event expectation table

        // --- #530: population point-spread & collisions over time ---
        // A fixed population is simulated from day 0; each player's still-valid score is read at a
        // series of horizons. Spread (SD/IQR/range) and collisions (players sharing an exact integer
        // total) are measured per horizon to see whether variance keeps growing or freezes at the plateau.
        private const val POPULATION = 2_000
        private const val MAX_HORIZON_DAYS = 1_095.0
        private const val V_36_MONTHS = 1_095
        private const val P25 = 25
        private const val P75 = 75
        private const val H_1YR = 365
        private const val H_3YR = 1_095

        // Horizons (days → label) at which the population's spread is measured.
        private val HORIZONS =
            listOf(30 to "1mo", 61 to "2mo", 122 to "4mo", 244 to "8mo", 365 to "1yr", 730 to "2yr", 1_095 to "3yr")

        // Population mix (weights over SkillClass.entries and BehaviorClass.entries order). Documented
        // in the study; the relative spread/collision findings are robust to the exact split.
        private val SKILL_WEIGHTS = doubleArrayOf(0.30, 0.40, 0.30) // Below / Even / Above
        private val BEHAVIOR_WEIGHTS = doubleArrayOf(0.30, 0.10, 0.40, 0.20) // open-only / tourney-only / balanced / heavy

        // Per-player seed offsets so each scenario/population draw is independent yet reproducible.
        private const val POPULATION_SEED = 900L

        // --- #530 Part 3: alternative game-margin Fibonacci open-play scheme ---
        // The set WINNER gets fib(2 + margin), margin = winnerGames − loserGames clamped at 6; the
        // loser (and a draw) get 0. fib(2+m) for m = 0..6 → [1,2,3,5,8,13,21], with m = 0 (a draw)
        // forced to 0. Indexed by margin (0 = draw). No bands, no ALP, no negatives.
        @Suppress("MagicNumber") // the fib(2+margin) award table, indexed by margin 0..6 (0 = draw = 0 pts)
        private val FIB_MARGIN_POINTS = doubleArrayOf(0.0, 2.0, 3.0, 5.0, 8.0, 13.0, 21.0)

        // Winner set-score margin mix over legal scores: 7-6→1, {6-4,7-5}→2, 6-3→3, 6-2→4, 6-1→5, 6-0→6.
        // Weights are indexed by (margin − 1), i.e. margins 1..6. A fixed, documented mix (not skill-dependent).
        @Suppress("MagicNumber") // documented set-score margin distribution (margins 1..6)
        private val MARGIN_WEIGHTS = doubleArrayOf(0.18, 0.25, 0.20, 0.15, 0.12, 0.10)
        private const val MAX_MARGIN = 6

        // Part 3 reuses the baseline scenario (×1, 2mo/6mo) so only the open-play point function differs
        // between the two schemes; the Fibonacci run uses a distinct seed tag for independent draws.
        private const val FIB_SEED_TAG = 4_242

        // --- #539 Part 4: combined levers (Fibonacci margin + longer validity + ×100 fixed-point) ---
        private const val SCALE_100 = 100.0

        // A finer-grained "dominance sub-tier" bonus added to the Fibonacci-margin award. At ×1 these
        // fractions round away (2.25 → 2, no new distinct totals); only a ×100 fixed-point scale keeps
        // them as distinct integers (225/250/275) — the demonstration of what fixed-point actually buys.
        @Suppress("MagicNumber") // dominance sub-tier fractional bonuses
        private val FRACTIONAL_TIERS = doubleArrayOf(0.0, 0.25, 0.50, 0.75)
        private const val PART4_SEED_TAG = 5_252

        // --- #542 Part 5: band-scoped collisions & band movement ---
        // Ranking points are band-tagged (#525): the standings race is per NTRP band, so collisions
        // only matter WITHIN a band cohort, and a band move resets the current-band race (old points go
        // dormant). Each player is assigned an NTRP band from a documented, realistic distribution; the
        // spread/collision metrics are then recomputed per band cohort and contrasted with the pooled
        // (all-players-one-race) numbers, and again under a rare band-movement model.
        @Suppress("MagicNumber") // NTRP bands 2.5..5.5 in 0.5 steps
        private val BANDS = doubleArrayOf(2.5, 3.0, 3.5, 4.0, 4.5, 5.0, 5.5)

        // Realistic recreational mix, weighted toward the 3.0–4.5 middle (sums to 1.0). Documented in
        // the study; the relative band-scoped vs pooled findings are robust to the exact split.
        @Suppress("MagicNumber") // documented NTRP-band population weights
        private val BAND_WEIGHTS = doubleArrayOf(0.08, 0.18, 0.24, 0.22, 0.15, 0.09, 0.04)
        private const val BAND_SEED = 1_300L

        // Band movement: ~8% of players move band per month (≈ the 8/100-in-the-first-month observation);
        // on a move the current-band total resets to 0 and the old-band points go dormant. A move steps
        // one band up or down with equal probability (clamped to the band range).
        private const val DAYS_PER_MONTH = 30.0
        private const val BAND_MOVE_MONTHLY_PROB = 0.08
        private const val BAND_STEP_UP_PROB = 0.5
        private const val PART5_SEED_TAG = 6_262
        private const val PART5_MOVE_SEED_TAG = 7_272
    }

    private enum class SkillClass(val label: String, val winRate: Double, val placementChance: Double) {
        BELOW(label = "Below 50%", winRate = 0.35, placementChance = 0.35),
        EVEN(label = "Even 50%", winRate = 0.50, placementChance = 0.50),
        ABOVE(label = "Above 50%", winRate = 0.65, placementChance = 0.65),
    }

    private enum class BehaviorClass(
        val label: String,
        val openSpacingDays: Double?,
        val tourneySpacingDays: Double?,
    ) {
        OPEN_ONLY(label = "Open play only (1×/wk)", openSpacingDays = WEEKLY, tourneySpacingDays = null),
        TOURNEY_ONLY(label = "Tournaments only (1 / 2 mo)", openSpacingDays = null, tourneySpacingDays = EVERY_TWO_MONTHS),
        BALANCED(label = "1×/wk open + 1 / 2 mo tourney", openSpacingDays = WEEKLY, tourneySpacingDays = EVERY_TWO_MONTHS),
        HEAVY_OPEN(label = "2×/wk open + 1 / 2 mo tourney", openSpacingDays = TWICE_WEEKLY, tourneySpacingDays = EVERY_TWO_MONTHS),
    }

    private data class Stats(val mean: Double, val sd: Double, val percentiles: Map<Int, Double>)

    /** One event on a player's timeline: when it happened, the points it carries, and its validity (days). */
    private data class Ev(val time: Double, val points: Double, val validity: Int)

    /** Spread + collision metrics for one population snapshot (#530). */
    private data class SpreadStats(
        val mean: Double,
        val sd: Double,
        val p25: Double,
        val p75: Double,
        val min: Double,
        val max: Double,
        val collisionPct: Double,
        val distinctCount: Int,
    )

    /** A ceiling/variance scenario (#530): a point scale plus the open/tournament validity windows. */
    private data class Scenario(
        val label: String,
        val scale: Double,
        val openValidity: Int,
        val tourneyValidity: Int,
    )

    /** Band-scoped spread/collisions (#542): per-band stats + sizes, plus the population-weighted collision rate. */
    private data class BandScoped(
        val perBand: Map<Int, SpreadStats>,
        val perBandSize: Map<Int, Int>,
        val aggregateCollisionPct: Double,
    )

    /**
     * One horizon snapshot under band movement (#542). Arrays are indexed by player. [fixedBand] is the
     * initial band (no-movement band-scoping); [currentBand] is the band in effect at the horizon
     * (with-movement band-scoping). [pooledScore] is the full active score; [movedScore] is the
     * current-band score (only points earned since the last band move at-or-before the horizon).
     */
    private data class MovementSnapshot(
        val fixedBand: IntArray,
        val currentBand: IntArray,
        val pooledScore: DoubleArray,
        val movedScore: DoubleArray,
    )

    // Baseline is the current design; the others explore raising the ceiling toward ~10k and growing
    // variance. Scaling alone raises the ceiling but not the collision rate; long validity accumulates
    // points so variance keeps growing and collisions fall. "Recommended" combines both to reach ~10k.
    private val scenarios =
        listOf(
            Scenario(label = "Baseline (×1, 2mo/6mo)", scale = 1.0, openValidity = V_2_MONTHS, tourneyValidity = V_6_MONTHS),
            Scenario(label = "Scaled ×30 (2mo/6mo)", scale = 30.0, openValidity = V_2_MONTHS, tourneyValidity = V_6_MONTHS),
            Scenario(label = "Long validity (×1, 12mo/36mo)", scale = 1.0, openValidity = V_12_MONTHS, tourneyValidity = V_36_MONTHS),
            Scenario(label = "Recommended (×10, 12mo/36mo)", scale = 10.0, openValidity = V_12_MONTHS, tourneyValidity = V_36_MONTHS),
        )

    fun generatePointsSimulationReport() {
        val text = render()
        println(message = text)
        File("/tmp/points_ranking.txt").writeText(text = text)
        val mdFile = File("presentations/points_ranking.md")
        mdFile.parentFile?.mkdirs()
        mdFile.writeText(text = text)
        println(message = "\nResults written to /tmp/points_ranking.txt and ${mdFile.path}")
    }

    // --- Points formula (standalone encoding of the design) ---

    private fun alp(rng: Random): Double = if (rng.nextDouble() < P_ALP) ALP_AWARD else 0.0

    /** Points to the player from one single-set open-play match, from the player's perspective. */
    private fun openPlayPoints(
        rng: Random,
        winRate: Double,
    ): Double {
        val relation = rng.nextDouble()
        val won = rng.nextDouble() < winRate
        return when {
            relation < P_EQUAL -> if (won) WIN_EQUAL else RLP_EQUAL
            relation < P_EQUAL + P_PLAYER_HIGHER -> if (won) WIN_FAVORITE else RLP_UPSET + alp(rng = rng)
            else -> if (won) WIN_UPSET else RLP_FAVORITE + alp(rng = rng)
        }
    }

    /**
     * Alternative open-play scheme (#530 Part 3): the set winner gets fib(2 + margin) from
     * [FIB_MARGIN_POINTS]; the loser (a lost set) gets 0. Win/loss is the class win rate; the winning
     * margin is drawn from [MARGIN_WEIGHTS]. No bands, no ALP, no negatives.
     */
    private fun fibMarginOpenPlayPoints(
        rng: Random,
        winRate: Double,
    ): Double {
        if (rng.nextDouble() >= winRate) return 0.0
        val draw = rng.nextDouble()
        var acc = 0.0
        var margin = MAX_MARGIN
        for (i in MARGIN_WEIGHTS.indices) {
            acc += MARGIN_WEIGHTS[i]
            if (draw < acc) {
                margin = i + 1
                break
            }
        }
        return FIB_MARGIN_POINTS[margin]
    }

    /**
     * #539: the Fibonacci-margin award plus a finer-grained dominance sub-tier bonus in {0,.25,.5,.75}.
     * These sub-integer increments are lost at ×1 (they round away) and only add distinct totals under a
     * ×100 fixed-point scale — the demonstration of what fixed-point buys on top of a diverse set.
     */
    private fun fibMarginFractionalOpenPlayPoints(
        rng: Random,
        winRate: Double,
    ): Double {
        val base = fibMarginOpenPlayPoints(rng = rng, winRate = winRate)
        if (base <= 0.0) return 0.0
        return base + FRACTIONAL_TIERS[rng.nextInt(until = FRACTIONAL_TIERS.size)]
    }

    /** Points to the player from one tournament (0 if they did not place). */
    private fun tournamentPoints(
        rng: Random,
        placementChance: Double,
    ): Double {
        if (rng.nextDouble() >= placementChance) return 0.0
        val draw = rng.nextDouble()
        var acc = 0.0
        var result = SANCTIONED_PLACEMENT.last()
        for (i in PLACEMENT_DIST.indices) {
            acc += PLACEMENT_DIST[i]
            if (draw < acc) {
                result = SANCTIONED_PLACEMENT[i]
                break
            }
        }
        return result
    }

    // --- Steady-state score sampling ---

    /**
     * Event times (with points) on a fixed cadence, read at the snapshot. A random phase offset in
     * [0, spacing) places the snapshot at a uniformly-random point on the event grid, so the expected
     * number of events inside a window of length V is exactly V / spacing (no boundary undercount).
     */
    private fun openEventsInWindow(
        rng: Random,
        winRate: Double,
        spacingDays: Double,
        snapshot: Double,
        maxValidity: Int,
    ): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        var eventTime = snapshot - rng.nextDouble() * spacingDays
        while (eventTime > snapshot - maxValidity && eventTime >= 0.0) {
            out.add(element = eventTime to openPlayPoints(rng = rng, winRate = winRate))
            eventTime -= spacingDays
        }
        return out
    }

    private fun tourneyEventsInWindow(
        rng: Random,
        placementChance: Double,
        spacingDays: Double,
        snapshot: Double,
        maxValidity: Int,
    ): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        var eventTime = snapshot - rng.nextDouble() * spacingDays
        while (eventTime > snapshot - maxValidity && eventTime >= 0.0) {
            out.add(element = eventTime to tournamentPoints(rng = rng, placementChance = placementChance))
            eventTime -= spacingDays
        }
        return out
    }

    private fun activeSum(
        events: List<Pair<Double, Double>>,
        snapshot: Double,
        validityDays: Int,
    ): Double = events.filter { (time, _) -> time > snapshot - validityDays }.sumOf { (_, points) -> points }

    /** Steady-state score for one component (open OR tournament) at several validity cutoffs, paired per trial. */
    private fun sweepComponent(
        seedOffset: Long,
        validities: List<Int>,
        drawEvents: (Random, Double) -> List<Pair<Double, Double>>,
    ): Map<Int, Stats> {
        val rng = Random(seed = SEED + seedOffset)
        val samples = validities.associateWith { DoubleArray(size = TRIALS) }
        repeat(times = TRIALS) { trial ->
            val snapshot = HORIZON_DAYS - rng.nextDouble() * STEADY_WINDOW_DAYS
            val events = drawEvents(rng, snapshot)
            validities.forEach { v -> samples.getValue(key = v)[trial] = activeSum(events = events, snapshot = snapshot, validityDays = v) }
        }
        return validities.associateWith { v -> statsOf(values = samples.getValue(key = v)) }
    }

    private fun openSweep(
        skill: SkillClass,
        spacingDays: Double,
        seedOffset: Long,
    ): Map<Int, Stats> =
        sweepComponent(
            seedOffset = seedOffset,
            validities = listOf(V_1_MONTH, V_2_MONTHS),
            drawEvents = {
                    rng,
                    snapshot,
                ->
                openEventsInWindow(
                    rng = rng,
                    winRate = skill.winRate,
                    spacingDays = spacingDays,
                    snapshot = snapshot,
                    maxValidity = V_2_MONTHS,
                )
            },
        )

    private fun tourneySweep(
        skill: SkillClass,
        seedOffset: Long,
    ): Map<Int, Stats> =
        sweepComponent(
            seedOffset = seedOffset,
            validities = listOf(V_3_MONTHS, V_6_MONTHS, V_12_MONTHS),
            drawEvents = {
                    rng,
                    snapshot,
                ->
                tourneyEventsInWindow(
                    rng = rng,
                    placementChance = skill.placementChance,
                    spacingDays = EVERY_TWO_MONTHS,
                    snapshot = snapshot,
                    maxValidity = V_12_MONTHS,
                )
            },
        )

    /** Combined total (open + tournament) for an archetype under the default validity policy, jointly sampled. */
    private fun archetypeTotal(
        skill: SkillClass,
        behavior: BehaviorClass,
        seedOffset: Long,
    ): Stats {
        val rng = Random(seed = SEED + seedOffset)
        val finals = DoubleArray(size = TRIALS)
        repeat(times = TRIALS) { trial ->
            val snapshot = HORIZON_DAYS - rng.nextDouble() * STEADY_WINDOW_DAYS
            var score = 0.0
            behavior.openSpacingDays?.let { spacing ->
                val events =
                    openEventsInWindow(
                        rng = rng,
                        winRate = skill.winRate,
                        spacingDays = spacing,
                        snapshot = snapshot,
                        maxValidity = DEFAULT_OPEN_VALIDITY,
                    )
                score += events.sumOf { (_, points) -> points }
            }
            behavior.tourneySpacingDays?.let { spacing ->
                val events =
                    tourneyEventsInWindow(
                        rng = rng,
                        placementChance = skill.placementChance,
                        spacingDays = spacing,
                        snapshot = snapshot,
                        maxValidity = DEFAULT_TOURNEY_VALIDITY,
                    )
                score += events.sumOf { (_, points) -> points }
            }
            finals[trial] = score
        }
        return statsOf(values = finals)
    }

    private fun expectedPerEvent(
        seedOffset: Long,
        draw: (Random) -> Double,
    ): Double {
        val rng = Random(seed = SEED + seedOffset)
        var total = 0.0
        repeat(times = EVENT_SAMPLES) { total += draw(rng) }
        return total / EVENT_SAMPLES
    }

    private fun statsOf(values: DoubleArray): Stats {
        val mean = values.average()
        val variance = values.sumOf { v -> (v - mean) * (v - mean) } / values.size
        val sorted = values.sorted()
        val percentiles = PERCENTILES.associateWith { p -> sorted[(p / PERCENT * (sorted.size - 1)).roundToInt()] }
        return Stats(mean = mean, sd = sqrt(x = variance), percentiles = percentiles)
    }

    // --- Rendering ---

    private fun fmt(value: Double): String = String.format(Locale.US, "%.1f", value)

    private fun row(cells: List<String>): String = cells.joinToString(prefix = "| ", separator = " | ", postfix = " |\n")

    private fun meanSd(stats: Stats): String = "${fmt(value = stats.mean)} ± ${fmt(value = stats.sd)}"

    private fun caption(stats: Stats): String {
        val p5 = fmt(value = stats.percentiles.getValue(key = 5))
        val p50 = fmt(value = stats.percentiles.getValue(key = 50))
        val p95 = fmt(value = stats.percentiles.getValue(key = 95))
        return "${fmt(value = stats.mean)} ($p5/$p50/$p95)"
    }

    private fun analyticCap(
        behavior: BehaviorClass,
        expectedOpen: Double,
        expectedTourney: Double,
    ): Double {
        var cap = 0.0
        behavior.openSpacingDays?.let { spacing -> cap += DEFAULT_OPEN_VALIDITY / spacing * expectedOpen }
        behavior.tourneySpacingDays?.let { spacing -> cap += DEFAULT_TOURNEY_VALIDITY / spacing * expectedTourney }
        return cap
    }

    private fun absoluteCeiling(behavior: BehaviorClass): Double {
        var ceiling = 0.0
        behavior.openSpacingDays?.let { spacing -> ceiling += ceil(x = DEFAULT_OPEN_VALIDITY / spacing) * OPEN_MAX_PER_MATCH }
        behavior.tourneySpacingDays?.let { spacing -> ceiling += ceil(x = DEFAULT_TOURNEY_VALIDITY / spacing) * TOURNEY_MAX_PER_EVENT }
        return ceiling
    }

    private fun render(): String {
        val skills = SkillClass.entries
        val expectedOpen =
            skills.associateWith {
                    skill ->
                expectedPerEvent(seedOffset = 100L + skill.ordinal) { rng -> openPlayPoints(rng = rng, winRate = skill.winRate) }
            }
        val expectedTourney =
            skills.associateWith {
                    skill ->
                expectedPerEvent(
                    seedOffset = 200L + skill.ordinal,
                ) { rng -> tournamentPoints(rng = rng, placementChance = skill.placementChance) }
            }
        val archetype =
            BehaviorClass.entries.associateWith {
                    behavior ->
                skills.associateWith {
                        skill ->
                    archetypeTotal(skill = skill, behavior = behavior, seedOffset = 700L + behavior.ordinal * 10 + skill.ordinal)
                }
            }

        val assignments = assignPopulation()
        val allScenarioScores = scenarios.associate { it.label to scenarioScores(scenario = it, assignments = assignments) }
        val baseline = allScenarioScores.getValue(key = scenarios.first().label)

        return buildString {
            append("# Points ranking — Monte Carlo results\n\n")
            append("_Seed $SEED, $TRIALS trials per cell. Sanctioned tournament table; unsanctioned = half._\n\n")
            append(section1(skills = skills, expectedOpen = expectedOpen, expectedTourney = expectedTourney))
            append(section2(skills = skills))
            append(section3(skills = skills))
            append(section4(skills = skills, archetype = archetype))
            append(section5(skills = skills, archetype = archetype, expectedOpen = expectedOpen, expectedTourney = expectedTourney))
            append(section6(baseline = baseline))
            append(section7(all = allScenarioScores))
            append(section8(assignments = assignments))
            append(section9(assignments = assignments))
            append(part5(assignments = assignments, baseline = baseline))
        }
    }

    /** #542 Part 5: band-scoped collisions (§10–11) and band movement (§12) on the shared population. */
    private fun part5(
        assignments: List<Pair<SkillClass, BehaviorClass>>,
        baseline: Map<Int, DoubleArray>,
    ): String {
        val bands = assignBands()
        // Legitimate recipe (#542): Fibonacci-margin open play + long validity (12mo/36mo), ×1.
        // No finer increment and no fixed-point scaling — both were disqualified in Part 4 (the finer
        // increment separates by random noise, and ×100 was only introduced to carry it), so band
        // scoping is measured on the honest, merit-based scheme.
        val longScenario =
            Scenario(label = "Fib long validity", scale = 1.0, openValidity = V_12_MONTHS, tourneyValidity = V_36_MONTHS)
        val longScores =
            scenarioScores(
                scenario = longScenario,
                assignments = assignments,
                openFn = ::fibMarginOpenPlayPoints,
                seedTag = PART5_SEED_TAG,
            )
        val movement =
            movementRun(
                scenario = longScenario,
                assignments = assignments,
                bands = bands,
                openFn = ::fibMarginOpenPlayPoints,
                seedTag = PART5_MOVE_SEED_TAG,
            )
        return section10(bands = bands, pooledBaseline = baseline, longScores = longScores) +
            section11(bands = bands, longScores = longScores) +
            section12(movement = movement)
    }

    private fun p4Row(
        label: String,
        s: SpreadStats,
    ): String =
        row(
            cells =
                listOf(
                    label,
                    fmt(value = s.sd),
                    "${fmt(value = s.min)}–${fmt(value = s.max)}",
                    s.distinctCount.toString(),
                    "${fmt(value = s.collisionPct)}%",
                ),
        )

    /** 3-year spread/collision stats for a Fibonacci-based scenario (the #539 combined-levers building block). */
    private fun part4At3yr(
        assignments: List<Pair<SkillClass, BehaviorClass>>,
        scale: Double,
        openValidity: Int,
        tourneyValidity: Int,
        openFn: (Random, Double) -> Double,
        seedTag: Int,
    ): SpreadStats {
        val scenario = Scenario(label = "part4", scale = scale, openValidity = openValidity, tourneyValidity = tourneyValidity)
        val scores = scenarioScores(scenario = scenario, assignments = assignments, openFn = openFn, seedTag = seedTag)
        return spreadStats(scores = scores.getValue(key = H_3YR))
    }

    /**
     * #539 Part 4: combine the three recommended levers at the 3-year (fully-warmed) horizon and show,
     * honestly, which one moves collisions. The ×1-long and ×100-long rows share a seed tag, so ×100 is
     * a pure relabel; the fractional row adds finer increments that only ×100 can preserve.
     */
    private fun section9(assignments: List<Pair<SkillClass, BehaviorClass>>): String {
        val short =
            part4At3yr(
                assignments = assignments,
                scale = 1.0,
                openValidity = V_2_MONTHS,
                tourneyValidity = V_6_MONTHS,
                openFn = ::fibMarginOpenPlayPoints,
                seedTag = PART4_SEED_TAG + 1,
            )
        val long =
            part4At3yr(
                assignments = assignments,
                scale = 1.0,
                openValidity = V_12_MONTHS,
                tourneyValidity = V_36_MONTHS,
                openFn = ::fibMarginOpenPlayPoints,
                seedTag = PART4_SEED_TAG + 2,
            )
        val long100 =
            part4At3yr(
                assignments = assignments,
                scale = SCALE_100,
                openValidity = V_12_MONTHS,
                tourneyValidity = V_36_MONTHS,
                openFn = ::fibMarginOpenPlayPoints,
                seedTag = PART4_SEED_TAG + 2,
            )
        val frac100 =
            part4At3yr(
                assignments = assignments,
                scale = SCALE_100,
                openValidity = V_12_MONTHS,
                tourneyValidity = V_36_MONTHS,
                openFn = ::fibMarginFractionalOpenPlayPoints,
                seedTag = PART4_SEED_TAG + 4,
            )
        return buildString {
            append("\n## 9. Combined levers — Fibonacci margin + longer validity + ×100 fixed-point (#539)\n\n")
            append(
                "_$POPULATION players, at the 3-year (fully-warmed) horizon. All rows use the Fibonacci-margin " +
                    "open-play scheme; they differ only in validity, scale, and (last row) a finer increment. " +
                    "Longer validity = open 12 mo / tourney 36 mo. Range = min–max player total._\n\n",
            )
            append(row(cells = listOf("Scenario", "sd", "range", "distinct totals", "collision %")))
            append(row(cells = listOf("---", "---:", "---:", "---:", "---:")))
            append(p4Row(label = "Fibonacci, short validity (2mo/6mo), ×1", s = short))
            append(p4Row(label = "Fibonacci, long validity (12mo/36mo), ×1", s = long))
            append(p4Row(label = "Fibonacci, long validity, ×100 (relabel)", s = long100))
            append(p4Row(label = "Fibonacci + finer increment, long, ×100", s = frac100))
        }
    }

    /** #530 Part 3: the current band scheme vs the game-margin Fibonacci scheme on the same population. */
    private fun section8(assignments: List<Pair<SkillClass, BehaviorClass>>): String {
        val baseline = scenarios.first()
        val band = scenarioScores(scenario = baseline, assignments = assignments)
        val fib =
            scenarioScores(scenario = baseline, assignments = assignments, openFn = ::fibMarginOpenPlayPoints, seedTag = FIB_SEED_TAG)
        return buildString {
            append("\n## 8. Alternative open-play scheme — game-margin Fibonacci vs band difference (#530)\n\n")
            append(
                "_Same $POPULATION-player population & default policy (×1 scale, open 2 mo / tourney 6 mo); " +
                    "only the open-play point function differs. Band = current design (increments {−2,0,1,2,3,5}); " +
                    "Fib = fib(2+margin) to the set winner (increments {2,3,5,8,13,21}). Range = min–max player total._\n\n",
            )
            append(
                row(
                    cells =
                        listOf(
                            "Horizon",
                            "band sd", "band range", "band distinct", "band coll%",
                            "fib sd", "fib range", "fib distinct", "fib coll%",
                        ),
                ),
            )
            append(row(cells = listOf("---", "---:", "---:", "---:", "---:", "---:", "---:", "---:", "---:")))
            HORIZONS.forEach { (days, label) ->
                val b = spreadStats(scores = band.getValue(key = days))
                val f = spreadStats(scores = fib.getValue(key = days))
                append(
                    row(
                        cells =
                            listOf(
                                label,
                                fmt(value = b.sd),
                                "${fmt(value = b.min)}–${fmt(value = b.max)}",
                                b.distinctCount.toString(),
                                "${fmt(value = b.collisionPct)}%",
                                fmt(value = f.sd),
                                "${fmt(value = f.min)}–${fmt(value = f.max)}",
                                f.distinctCount.toString(),
                                "${fmt(value = f.collisionPct)}%",
                            ),
                    ),
                )
            }
        }
    }

    private fun section1(
        skills: List<SkillClass>,
        expectedOpen: Map<SkillClass, Double>,
        expectedTourney: Map<SkillClass, Double>,
    ): String =
        buildString {
            append("## 1. Expected points per event\n\n")
            append(row(cells = listOf("Skill class", "Open-play match (avg pts)", "Tournament (avg pts, sanctioned)")))
            append(row(cells = listOf("---", "---:", "---:")))
            skills.forEach { skill ->
                val open = fmt(value = expectedOpen.getValue(key = skill))
                val tourney = fmt(value = expectedTourney.getValue(key = skill))
                append(row(cells = listOf(skill.label, open, tourney)))
            }
        }

    private fun section2(skills: List<SkillClass>): String =
        buildString {
            append("\n## 2. Open-play steady-state score (mean active points)\n\n")
            append(row(cells = listOf("Skill class", "Cadence", "1-month validity", "2-month validity", "2mo ÷ 1mo")))
            append(row(cells = listOf("---", "---", "---:", "---:", "---:")))
            val cadences = listOf("1×/wk" to WEEKLY, "2×/wk" to TWICE_WEEKLY)
            skills.forEachIndexed { i, skill ->
                cadences.forEachIndexed { j, (label, spacing) ->
                    val res = openSweep(skill = skill, spacingDays = spacing, seedOffset = 300L + i * 10 + j)
                    val m1 = res.getValue(key = V_1_MONTH).mean
                    val m2 = res.getValue(key = V_2_MONTHS).mean
                    append(row(cells = listOf(skill.label, label, fmt(value = m1), fmt(value = m2), "${fmt(value = m2 / m1)}×")))
                }
            }
        }

    private fun section3(skills: List<SkillClass>): String =
        buildString {
            append("\n## 3. Tournament steady-state score (mean ± sd active points, 1 tournament / 2 months)\n\n")
            append(row(cells = listOf("Skill class", "3-month", "6-month", "12-month", "12mo ÷ 3mo")))
            append(row(cells = listOf("---", "---:", "---:", "---:", "---:")))
            skills.forEachIndexed { i, skill ->
                val res = tourneySweep(skill = skill, seedOffset = 500L + i)
                val s3 = res.getValue(key = V_3_MONTHS)
                val s6 = res.getValue(key = V_6_MONTHS)
                val s12 = res.getValue(key = V_12_MONTHS)
                val ratio = "${fmt(value = s12.mean / s3.mean)}×"
                append(row(cells = listOf(skill.label, meanSd(stats = s3), meanSd(stats = s6), meanSd(stats = s12), ratio)))
            }
        }

    private fun section4(
        skills: List<SkillClass>,
        archetype: Map<BehaviorClass, Map<SkillClass, Stats>>,
    ): String =
        buildString {
            append("\n## 4. Combined steady-state score — default policy (open play 2 mo, tournament 6 mo)\n\n")
            append("Mean total leaderboard points, with p5 / median / p95 in parentheses.\n\n")
            append(row(cells = listOf("Behaviour class", "Below 50%", "Even 50%", "Above 50%")))
            append(row(cells = listOf("---", "---:", "---:", "---:")))
            BehaviorClass.entries.forEach { behavior ->
                val cells = skills.map { skill -> caption(stats = archetype.getValue(key = behavior).getValue(key = skill)) }
                append(row(cells = listOf(behavior.label, cells[0], cells[1], cells[2])))
            }
        }

    private fun section5(
        skills: List<SkillClass>,
        archetype: Map<BehaviorClass, Map<SkillClass, Stats>>,
        expectedOpen: Map<SkillClass, Double>,
        expectedTourney: Map<SkillClass, Double>,
    ): String =
        buildString {
            append("\n## 5. Is the score capped? Yes — it plateaus (default policy)\n\n")
            append("Points expire, so the active total plateaus at Cap ≈ rate × E[pts/event] × validity. ")
            append("The Monte Carlo mean matches the closed-form estimate; the ceiling is the all-wins upper bound.\n\n")
            append(row(cells = listOf("Behaviour class", "Skill", "MC mean (expected cap)", "Analytic rate·μ·V", "Absolute ceiling")))
            append(row(cells = listOf("---", "---", "---:", "---:", "---:")))
            BehaviorClass.entries.forEach { behavior ->
                val ceiling = fmt(value = absoluteCeiling(behavior = behavior))
                skills.forEach { skill ->
                    val mc = fmt(value = archetype.getValue(key = behavior).getValue(key = skill).mean)
                    val analytic =
                        fmt(
                            value =
                                analyticCap(
                                    behavior = behavior,
                                    expectedOpen = expectedOpen.getValue(key = skill),
                                    expectedTourney = expectedTourney.getValue(key = skill),
                                ),
                        )
                    append(row(cells = listOf(behavior.label, skill.label, mc, analytic, ceiling)))
                }
            }
        }

    // --- #530: population spread & collisions over time ---

    /** Assign the population to (skill, behaviour) archetypes by the documented weights (seeded). */
    private fun assignPopulation(): List<Pair<SkillClass, BehaviorClass>> {
        val rng = Random(seed = SEED + POPULATION_SEED)
        return List(size = POPULATION) {
            pick(rng = rng, items = SkillClass.entries, weights = SKILL_WEIGHTS) to
                pick(rng = rng, items = BehaviorClass.entries, weights = BEHAVIOR_WEIGHTS)
        }
    }

    private fun <T> pick(
        rng: Random,
        items: List<T>,
        weights: DoubleArray,
    ): T {
        val draw = rng.nextDouble()
        var acc = 0.0
        val hit =
            items.indices.firstOrNull { i ->
                acc += weights[i]
                draw < acc
            }
        return items[hit ?: items.lastIndex]
    }

    /**
     * One player's full event timeline over [0, MAX_HORIZON], points scaled and tagged with validity.
     * [openFn] is the open-play point function — the current band scheme by default; Part 3 passes the
     * Fibonacci-margin scheme to compare the two on the same population.
     */
    private fun playerStream(
        skill: SkillClass,
        behavior: BehaviorClass,
        scenario: Scenario,
        rng: Random,
        openFn: (Random, Double) -> Double = ::openPlayPoints,
    ): List<Ev> {
        val out = ArrayList<Ev>()
        behavior.openSpacingDays?.let { spacing ->
            var t = rng.nextDouble() * spacing
            while (t <= MAX_HORIZON_DAYS) {
                out.add(
                    element =
                        Ev(
                            time = t,
                            points = openFn(rng, skill.winRate) * scenario.scale,
                            validity = scenario.openValidity,
                        ),
                )
                t += spacing
            }
        }
        behavior.tourneySpacingDays?.let { spacing ->
            var t = rng.nextDouble() * spacing
            while (t <= MAX_HORIZON_DAYS) {
                out.add(
                    element =
                        Ev(
                            time = t,
                            points = tournamentPoints(rng = rng, placementChance = skill.placementChance) * scenario.scale,
                            validity = scenario.tourneyValidity,
                        ),
                )
                t += spacing
            }
        }
        return out
    }

    private fun activeScoreAt(
        stream: List<Ev>,
        horizon: Int,
    ): Double = stream.filter { it.time <= horizon && it.time > horizon - it.validity }.sumOf { it.points }

    /** Per-horizon population scores for a scenario; each player is streamed once (seeded per player+scenario). */
    private fun scenarioScores(
        scenario: Scenario,
        assignments: List<Pair<SkillClass, BehaviorClass>>,
        openFn: (Random, Double) -> Double = ::openPlayPoints,
        seedTag: Int = scenario.label.hashCode(),
    ): Map<Int, DoubleArray> {
        val perHorizon = HORIZONS.associate { (days, _) -> days to DoubleArray(size = POPULATION) }
        assignments.forEachIndexed { idx, (skill, behavior) ->
            val rng = Random(seed = SEED + seedTag + idx)
            val stream = playerStream(skill = skill, behavior = behavior, scenario = scenario, rng = rng, openFn = openFn)
            HORIZONS.forEach { (days, _) -> perHorizon.getValue(key = days)[idx] = activeScoreAt(stream = stream, horizon = days) }
        }
        return perHorizon
    }

    private fun pct(
        sorted: List<Double>,
        p: Int,
    ): Double = sorted[(p / PERCENT * (sorted.size - 1)).roundToInt()]

    /** Spread + collision metrics: collisions = players sharing an exact integer total with someone else. */
    private fun spreadStats(scores: DoubleArray): SpreadStats {
        val sorted = scores.sorted()
        val mean = scores.average()
        val variance = scores.sumOf { v -> (v - mean) * (v - mean) } / scores.size
        val counts = scores.asList().groupingBy { it.roundToInt() }.eachCount()
        val collided = counts.values.filter { it > 1 }.sum()
        return SpreadStats(
            mean = mean,
            sd = sqrt(x = variance),
            p25 = pct(sorted = sorted, p = P25),
            p75 = pct(sorted = sorted, p = P75),
            min = sorted.first(),
            max = sorted.last(),
            collisionPct = collided.toDouble() / scores.size * PERCENT,
            distinctCount = counts.size,
        )
    }

    // --- #542: band model, band-scoped metrics & band movement ---

    /** Assign each player an NTRP band index (into [BANDS]) from [BAND_WEIGHTS] (seeded, independent). */
    private fun assignBands(): IntArray {
        val rng = Random(seed = SEED + BAND_SEED)
        return IntArray(size = POPULATION) { pick(rng = rng, items = BANDS.indices.toList(), weights = BAND_WEIGHTS) }
    }

    private fun bandLabel(index: Int): String = String.format(Locale.US, "%.1f", BANDS[index])

    /** Players tied on an exact integer total with at least one other player. */
    private fun collidedCount(scores: DoubleArray): Int =
        scores.asList().groupingBy { it.roundToInt() }.eachCount().values.filter { it > 1 }.sum()

    /**
     * Recompute spread/collisions PER band cohort (group by band, ties only within a cohort) plus a
     * population-weighted aggregate collision rate = total within-cohort collided players / population.
     */
    private fun bandScoped(
        scores: DoubleArray,
        bands: IntArray,
    ): BandScoped {
        val cohorts =
            scores.indices
                .groupBy { bands[it] }
                .toSortedMap()
                .mapValues { (_, idx) -> DoubleArray(size = idx.size) { i -> scores[idx[i]] } }
        val collided = cohorts.values.sumOf { arr -> collidedCount(scores = arr) }
        return BandScoped(
            perBand = cohorts.mapValues { (_, arr) -> spreadStats(scores = arr) },
            perBandSize = cohorts.mapValues { (_, arr) -> arr.size },
            aggregateCollisionPct = collided.toDouble() / scores.size * PERCENT,
        )
    }

    /** A player's band-move timeline over [0, MAX_HORIZON]: (moveTime, newBandIndex), oldest first. */
    private fun bandMoves(
        rng: Random,
        initialBand: Int,
    ): List<Pair<Double, Int>> {
        val moves = ArrayList<Pair<Double, Int>>()
        var band = initialBand
        val months = (MAX_HORIZON_DAYS / DAYS_PER_MONTH).toInt()
        for (m in 1..months) {
            if (rng.nextDouble() < BAND_MOVE_MONTHLY_PROB) {
                val step = if (rng.nextDouble() < BAND_STEP_UP_PROB) 1 else -1
                band = (band + step).coerceIn(minimumValue = 0, maximumValue = BANDS.lastIndex)
                moves.add(element = (m * DAYS_PER_MONTH) to band)
            }
        }
        return moves
    }

    /** The most-recent move at-or-before [horizon]: (lastMoveTime, currentBand); 0.0/initial band if none. */
    private fun stateAt(
        moves: List<Pair<Double, Int>>,
        initialBand: Int,
        horizon: Double,
    ): Pair<Double, Int> {
        val last = moves.lastOrNull { it.first <= horizon }
        return (last?.first ?: 0.0) to (last?.second ?: initialBand)
    }

    /** Active current-band score: still-valid points earned strictly after the last band move. */
    private fun currentBandScoreAt(
        stream: List<Ev>,
        horizon: Int,
        lastMoveTime: Double,
    ): Double =
        stream
            .filter { it.time <= horizon && it.time > horizon - it.validity && it.time > lastMoveTime }
            .sumOf { it.points }

    /**
     * Per-horizon snapshot of the population under band movement. Each player is streamed once (matched
     * to the no-movement draw), then band moves cut the current-band race. Both the pooled (no-reset)
     * and moved (reset-on-move) scores are captured so the two are compared on the same draw.
     */
    private fun movementRun(
        scenario: Scenario,
        assignments: List<Pair<SkillClass, BehaviorClass>>,
        bands: IntArray,
        openFn: (Random, Double) -> Double,
        seedTag: Int,
    ): Map<Int, MovementSnapshot> {
        val snaps =
            HORIZONS.associate { (days, _) ->
                days to
                    MovementSnapshot(
                        fixedBand = bands.copyOf(),
                        currentBand = IntArray(size = POPULATION),
                        pooledScore = DoubleArray(size = POPULATION),
                        movedScore = DoubleArray(size = POPULATION),
                    )
            }
        assignments.forEachIndexed { idx, (skill, behavior) ->
            val rng = Random(seed = SEED + seedTag + idx)
            val stream = playerStream(skill = skill, behavior = behavior, scenario = scenario, rng = rng, openFn = openFn)
            val moves = bandMoves(rng = rng, initialBand = bands[idx])
            HORIZONS.forEach { (days, _) ->
                val snap = snaps.getValue(key = days)
                val (lastMove, currentBand) = stateAt(moves = moves, initialBand = bands[idx], horizon = days.toDouble())
                snap.currentBand[idx] = currentBand
                snap.pooledScore[idx] = activeScoreAt(stream = stream, horizon = days)
                snap.movedScore[idx] = currentBandScoreAt(stream = stream, horizon = days, lastMoveTime = lastMove)
            }
        }
        return snaps
    }

    private fun section6(baseline: Map<Int, DoubleArray>): String =
        buildString {
            append("\n## 6. Population point-spread & collisions over time (baseline, default policy)\n\n")
            append(
                "_$POPULATION players (skill 30/40/30, behaviour 30/10/40/20); " +
                    "collision % = share of players tied on an exact integer total._\n\n",
            )
            append(row(cells = listOf("Horizon", "mean", "sd", "IQR p25–p75", "min–max", "collision %", "distinct totals")))
            append(row(cells = listOf("---", "---:", "---:", "---:", "---:", "---:", "---:")))
            HORIZONS.forEach { (days, label) ->
                val s = spreadStats(scores = baseline.getValue(key = days))
                append(
                    row(
                        cells =
                            listOf(
                                label,
                                fmt(value = s.mean),
                                fmt(value = s.sd),
                                "${fmt(value = s.p25)}–${fmt(value = s.p75)}",
                                "${fmt(value = s.min)}–${fmt(value = s.max)}",
                                "${fmt(value = s.collisionPct)}%",
                                s.distinctCount.toString(),
                            ),
                    ),
                )
            }
        }

    private fun section7(all: Map<String, Map<Int, DoubleArray>>): String =
        buildString {
            append("\n## 7. Raising the ceiling & growing variance — scenario comparison\n\n")
            append(
                "_Max / sd / collision % at the 1-year and 3-year horizons. Scaling raises the ceiling but not " +
                    "the collision rate; long validity accumulates points so variance grows and collisions fall._\n\n",
            )
            append(row(cells = listOf("Scenario", "max @1yr", "sd @1yr", "coll% @1yr", "max @3yr", "sd @3yr", "coll% @3yr")))
            append(row(cells = listOf("---", "---:", "---:", "---:", "---:", "---:", "---:")))
            scenarios.forEach { sc ->
                val y1 = spreadStats(scores = all.getValue(key = sc.label).getValue(key = H_1YR))
                val y3 = spreadStats(scores = all.getValue(key = sc.label).getValue(key = H_3YR))
                append(
                    row(
                        cells =
                            listOf(
                                sc.label,
                                fmt(value = y1.max),
                                fmt(value = y1.sd),
                                "${fmt(value = y1.collisionPct)}%",
                                fmt(value = y3.max),
                                fmt(value = y3.sd),
                                "${fmt(value = y3.collisionPct)}%",
                            ),
                    ),
                )
            }
        }

    private fun bandMixTable(bands: IntArray): String =
        buildString {
            append("Population NTRP-band mix (seeded, weighted toward 3.0–4.5):\n\n")
            append(row(cells = listOf("NTRP band", "players", "share")))
            append(row(cells = listOf("---", "---:", "---:")))
            BANDS.indices.forEach { b ->
                val n = bands.count { it == b }
                append(row(cells = listOf(bandLabel(index = b), n.toString(), "${fmt(value = n.toDouble() / POPULATION * PERCENT)}%")))
            }
        }

    private fun collisionCompareTable(
        bands: IntArray,
        pooledBaseline: Map<Int, DoubleArray>,
        longScores: Map<Int, DoubleArray>,
    ): String =
        buildString {
            append("\nCollision % — pooled (all players, one race) vs band-scoped (within each band cohort):\n\n")
            append(
                row(
                    cells =
                        listOf(
                            "Horizon",
                            "pooled (current)",
                            "band-scoped (current)",
                            "pooled (long)",
                            "band-scoped (long)",
                        ),
                ),
            )
            append(row(cells = listOf("---", "---:", "---:", "---:", "---:")))
            HORIZONS.forEach { (days, label) ->
                val cp = spreadStats(scores = pooledBaseline.getValue(key = days)).collisionPct
                val cb = bandScoped(scores = pooledBaseline.getValue(key = days), bands = bands).aggregateCollisionPct
                val kp = spreadStats(scores = longScores.getValue(key = days)).collisionPct
                val kb = bandScoped(scores = longScores.getValue(key = days), bands = bands).aggregateCollisionPct
                append(
                    row(cells = listOf(label, "${fmt(value = cp)}%", "${fmt(value = cb)}%", "${fmt(value = kp)}%", "${fmt(value = kb)}%")),
                )
            }
        }

    /** #542: same population/draw, collisions counted per band cohort vs pooled across horizons. */
    private fun section10(
        bands: IntArray,
        pooledBaseline: Map<Int, DoubleArray>,
        longScores: Map<Int, DoubleArray>,
    ): String =
        buildString {
            append("\n## 10. Band-scoped vs pooled collisions (#542)\n\n")
            append(
                "_Points are band-tagged (#525): the standings race runs per NTRP band, so a collision only " +
                    "matters WITHIN a band cohort. Grouping the same $POPULATION-player draw by band and counting ties " +
                    "only inside each cohort gives the band-scoped rate; the pooled rate treats everyone as one race. " +
                    "'Current' = the band-difference scheme (×1, 2mo/6mo); 'long' = the legitimate recipe — " +
                    "Fibonacci-margin + long validity (12mo/36mo), ×1, with no finer increment and no fixed-point " +
                    "(both disqualified in Part 4)._\n\n",
            )
            append(bandMixTable(bands = bands))
            append(collisionCompareTable(bands = bands, pooledBaseline = pooledBaseline, longScores = longScores))
        }

    /** #542: per-band cohort detail at the 3-year horizon under the legitimate long-validity recipe. */
    private fun section11(
        bands: IntArray,
        longScores: Map<Int, DoubleArray>,
    ): String =
        buildString {
            append("\n## 11. Per-band cohort detail at 3-yr — Fibonacci-margin, long validity (#542)\n\n")
            append(
                "_The legitimate recipe (Fibonacci-margin + long validity, ×1) at the fully-warmed 3-year horizon, " +
                    "broken out by band cohort. Smaller cohorts have fewer players chasing the same distinct totals, so " +
                    "within-band collisions run well below the pooled rate for the same recipe._\n\n",
            )
            append(row(cells = listOf("NTRP band", "players", "sd", "min–max", "distinct totals", "collision %")))
            append(row(cells = listOf("---", "---:", "---:", "---:", "---:", "---:")))
            val bs = bandScoped(scores = longScores.getValue(key = H_3YR), bands = bands)
            bs.perBand.forEach { (band, s) ->
                append(
                    row(
                        cells =
                            listOf(
                                bandLabel(index = band),
                                bs.perBandSize.getValue(key = band).toString(),
                                fmt(value = s.sd),
                                "${fmt(value = s.min)}–${fmt(value = s.max)}",
                                s.distinctCount.toString(),
                                "${fmt(value = s.collisionPct)}%",
                            ),
                    ),
                )
            }
        }

    /** #542: rare band movement resets the current-band race; band-scoped collisions with vs without moves. */
    private fun section12(movement: Map<Int, MovementSnapshot>): String =
        buildString {
            append("\n## 12. Band movement — resetting the current-band race (#542)\n\n")
            append(
                "_Fibonacci-margin, long-validity recipe (×1). " +
                    "~${fmt(value = BAND_MOVE_MONTHLY_PROB * PERCENT)}% of players move band per " +
                    "month; on a move the current-band total resets to 0 and the old-band points go dormant (they only " +
                    "reactivate on a move back). 'Moved players' = share whose band at the horizon differs from their " +
                    "start. Band-scoped collisions grouped by CURRENT band; means show how much the reset trims the " +
                    "active total._\n\n",
            )
            append(
                row(
                    cells =
                        listOf(
                            "Horizon",
                            "moved players",
                            "band-scoped (no move)",
                            "band-scoped (with move)",
                            "mean (with move)",
                            "mean (no move)",
                        ),
                ),
            )
            append(row(cells = listOf("---", "---:", "---:", "---:", "---:", "---:")))
            HORIZONS.forEach { (days, label) ->
                val snap = movement.getValue(key = days)
                val moved = snap.currentBand.indices.count { snap.currentBand[it] != snap.fixedBand[it] }.toDouble() / POPULATION * PERCENT
                val noMove = bandScoped(scores = snap.pooledScore, bands = snap.fixedBand).aggregateCollisionPct
                val withMove = bandScoped(scores = snap.movedScore, bands = snap.currentBand).aggregateCollisionPct
                append(
                    row(
                        cells =
                            listOf(
                                label,
                                "${fmt(value = moved)}%",
                                "${fmt(value = noMove)}%",
                                "${fmt(value = withMove)}%",
                                fmt(value = snap.movedScore.average()),
                                fmt(value = snap.pooledScore.average()),
                            ),
                    ),
                )
            }
        }
}

fun main() {
    PointsRankingSimulationReport().generatePointsSimulationReport()
}
