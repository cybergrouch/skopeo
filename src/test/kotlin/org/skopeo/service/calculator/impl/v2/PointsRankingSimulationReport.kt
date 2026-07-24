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
 * Output: /tmp/points_ranking.txt and presentations/points_ranking.md (git-ignored). The curated
 * tables live in docs/product/POINTS_RANKING_SIMULATION_STUDY.md.  Run: ./gradlew generatePointsSimulationReport
 */
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

    /** One player's full event timeline over [0, MAX_HORIZON], points scaled and tagged with validity. */
    private fun playerStream(
        skill: SkillClass,
        behavior: BehaviorClass,
        scenario: Scenario,
        rng: Random,
    ): List<Ev> {
        val out = ArrayList<Ev>()
        behavior.openSpacingDays?.let { spacing ->
            var t = rng.nextDouble() * spacing
            while (t <= MAX_HORIZON_DAYS) {
                out.add(
                    element =
                        Ev(
                            time = t,
                            points = openPlayPoints(rng = rng, winRate = skill.winRate) * scenario.scale,
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
    ): Map<Int, DoubleArray> {
        val perHorizon = HORIZONS.associate { (days, _) -> days to DoubleArray(size = POPULATION) }
        assignments.forEachIndexed { idx, (skill, behavior) ->
            val rng = Random(seed = SEED + scenario.label.hashCode() + idx)
            val stream = playerStream(skill = skill, behavior = behavior, scenario = scenario, rng = rng)
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
}

fun main() {
    PointsRankingSimulationReport().generatePointsSimulationReport()
}
