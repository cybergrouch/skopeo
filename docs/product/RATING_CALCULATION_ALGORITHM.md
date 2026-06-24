# Rating Calculation Algorithm

How Skopeo turns a match result into rating changes — explained top-down, from the single master formula to every constant in it.

**Reading guide:**

1. [The big picture](#1-the-big-picture) — the algorithm in one sentence and one formula
2. [The four factors](#2-the-four-factors) — what each term in the formula means and how it is computed
3. [Deriving the constants](#3-deriving-the-constants) — where 6.0, 0.5, 0.16, and 2.0 come from
4. [Dominance factor tables](#4-dominance-factor-tables) — pre-computed values for all common tennis scores
5. [The post-processing pipeline](#5-the-post-processing-pipeline) — smoothing, clamping, precision, zero-sum
6. [Worked examples](#6-worked-examples) — full calculations, end to end, plus the complete rating-change table for all test scenarios
7. [Edge cases and known limitations](#7-edge-cases-and-known-limitations)
8. [Implementation map](#8-implementation-map) — where the code lives
9. [Glossary](#9-glossary) — every technical term defined in one place

---

## 1. The Big Picture

**In one sentence:** after every match, the winner gains and the loser loses an amount that grows with *how convincingly* the match was won and *how surprising* the result was — and shrinks toward zero when the result was exactly what the ratings predicted.

This is a **performance-based [Elo](#elo-rating-system) system**. Classical Elo only asks "who won?". Skopeo additionally asks "by how much?" (the [dominance factor](#dominance-factor)) and "was that expected?" (the [scale factor](#scale-factor), built from the [rating gap](#rating-gap)).

### The master formula

Every rating change, for both players, is one multiplication:

```
change = K × dominance × scale × sign
```

| Factor | Question it answers | Range | Detail |
|---|---|---|---|
| `K` | How big is one "step" on the rating scale? | 0.16 | [§2.1](#21-k--the-step-size) |
| `dominance` | How convincingly was the match won? | 0.0 – 1.0 | [§2.2](#22-dominance--how-convincingly) |
| `scale` | How surprising was the result? | 0.0 – ~24 | [§2.3](#23-scale--how-surprising) |
| `sign` | Did this player win or lose? | +1 / −1 | [§2.4](#24-sign--who-gains-who-loses) |

Two consequences worth internalizing before reading further:

- **Any factor at zero kills the change.** A result exactly at expectation (`scale = 0`) changes nothing, no matter how dominant the score.
- **Before boundary clamping the result is [zero-sum](#zero-sum)**: the winner's gain equals the loser's loss, because both players share the same `K × dominance × scale` and differ only in `sign`.

The raw `change` then passes through a short [post-processing pipeline](#5-the-post-processing-pipeline) (optional smoothing → boundary clamping) before becoming the player's new rating.

---

## 2. The Four Factors

### 2.1 `K` — the step size

The **[K-factor](#k-factor)** is the classical Elo concept: a constant that converts the abstract outcome of the formula into points on a concrete rating scale. It is the *maximum* rating change a non-upset match can produce — reached only when both other factors hit 1.0 (equal players, shutout score):

```
max non-upset change = K × 1.0 × 1.0 = K
```

For NTRP, `K = 0.16`, which produces typical changes of ±0.032 (6-4 between equals) up to ±0.160 (6-0 between equals).

A larger K means faster convergence toward true skill but noisier ratings; a smaller K means stable but slow-moving ratings. Why exactly 0.16 — see [§3.3](#33-the-k-factor-016).

### 2.2 `dominance` — how convincingly

The **[dominance factor](#dominance-factor)** measures match closeness. Per set, it is the standard **[efficiency formula](#efficiency-formula)** — *net successes divided by total attempts* — applied to games:

```
setDominance = (gamesWon − gamesLost) / (gamesWon + gamesLost) = netGamesWon / totalGames
```

Read it as: **how many games of net advantage the player earned per game played in that set**. Winning exactly half the games yields zero efficiency (a perfectly even set); winning every game yields 1.0 (maximum efficiency, a shutout).

For the match, dominance is the **average of the per-set dominances**:

```
dominance = (setDominance₁ + setDominance₂ + … + setDominanceₙ) / n
```

Why average sets rather than pool game totals across the match? Because **a game's weight depends on whether its set was won or lost** — sets are the structural unit of tennis, and games from different sets are not interchangeable. Pooling totals would let games from a lost set silently offset games from a won set. Averaging keeps each set's efficiency intact, and the base case is exact: a one-set match's dominance is simply that set's efficiency.

Properties that fall out of this definition for free:

- **Naturally bounded** to [−1, +1] — no artificial cap needed, shutouts cause no division-by-zero.
- **Symmetric**: the loser's dominance is exactly the negative of the winner's, set by set and therefore for the match. (The formula uses the magnitude; `sign` carries direction.)
- **Set-structure aware**: a 6-0, 6-0 sweep averages (1.0 + 1.0)/2 = 1.0, while a 7-6, 6-7, 7-6 marathon averages (0.077 − 0.077 + 0.077)/3 = 0.026 — dropping a set drags the average down.

Pre-computed values for every common tennis score are in [§4](#4-dominance-factor-tables).

### 2.3 `scale` — how surprising

The scale factor converts the **[rating gap](#rating-gap)** between the players into a measure of how much *information* the result carries. The intuition: a result that ratings already predicted teaches us nothing (scale → 0); a result that contradicts the ratings teaches us a lot (scale ≫ 1).

Two preliminary definitions:

```
normalizedGap = |rating₁ − rating₂| / ratingRange      (gap as a fraction of the scale, see §3.1)
threshold     = 0.083                                  (the competitive threshold, see §3.2)
```

The algorithm then takes one of **two paths**:

#### Path A — Expected or competitive (the favorite won, or ratings were equal)

```
scale = max(0, (threshold − normalizedGap) / threshold)
```

A straight line from 1.0 down to 0.0:

| normalizedGap | scale | Meaning |
|---|---|---|
| 0% (equal ratings) | 1.00 | Full performance-based change |
| 4.2% (0.25 NTRP) | 0.49 | About half |
| 8.3% (0.5 NTRP — at threshold) | 0.00 | Result fully expected → no change |
| > 8.3% (any larger gap) | 0.00 | Even more expected → still no change |

When the favorite beats a clearly weaker player, the ratings were already right — there is nothing to correct.

#### Path B — Upset (the underdog won)

```
scale = (normalizedGap / threshold) × upsetMultiplier        where upsetMultiplier = 2.0
```

A straight line growing *with* the gap — the bigger the favorite that fell, the more wrong the ratings were:

| Gap (NTRP) | normalizedGap | scale |
|---|---|---|
| 0.25 | 4.2% | 1.0 |
| 0.5 | 8.3% | 2.0 |
| 1.0 | 16.7% | 4.0 |
| 2.0 | 33.3% | 8.0 |
| 3.0 | 50.0% | 12.0 |

Note the deliberate **asymmetry at the threshold**: at a 0.5 NTRP gap, the favorite winning produces scale 0.0 but the underdog winning produces scale 2.0. Surprising results move ratings; predictable ones don't. Why the multiplier is 2.0 — see [§3.4](#34-the-upset-multiplier-20).

#### Path selection

```kotlin
isUpset = (isWinner && ratingAdvantage < 0) || (!isWinner && ratingAdvantage > 0)
```

Both players always land on the *same* path (one player's upset win is the other's upset loss), so they share the same scale value — preserving the zero-sum property.

### 2.4 `sign` — who gains, who loses

```
sign = +1 if this player won the match, −1 otherwise
```

This is the only factor that differs between the two players, which is what makes the raw changes [zero-sum](#zero-sum): `change₁ + change₂ = 0` before clamping.

---

## 3. Deriving the Constants

None of the algorithm's numbers are arbitrary; each one is derived from the published shape of the NTRP rating system.

### 3.1 The rating range: 6.0

The **[rating range](#rating-range)** is simply *ceiling minus floor* of the NTRP scale:

| System | Floor | Ceiling | Range |
|---|---|---|---|
| NTRP | 1.0 (beginner) | 7.0 (world-class) | 7.0 − 1.0 = **6.0** |

The range is what makes gaps comparable via the [normalized gap](#normalized-gap): expressing a gap as a fraction of the range, rather than as raw points, keeps the threshold and scale factors independent of the scale's absolute width. A 0.5 NTRP gap is 8.3% of the range:

```
0.5 / 6.0 = 0.0833
```

### 3.2 The competitive threshold: 8.3% → 0.5 NTRP

The **[competitive threshold](#competitive-threshold)** marks the gap beyond which a favorite's win is considered fully expected. Its anchor is the NTRP system itself: NTRP publishes levels in **0.5-point steps** (3.5, 4.0, 4.5, …), so one half-level — the smallest officially recognized skill difference — is the natural boundary of "still a competitive match":

```
threshold = half-level / range = 0.5 / 6.0 = 1/12 ≈ 0.083 = 8.3%
```

Because the threshold is expressed as a *percentage of range*, it maps back to:

```
NTRP: 8.3% × 6.0 = 0.5 rating points
```

### 3.3 The K-factor: 0.16

**NTRP's K = 0.16 is the calibration anchor**, chosen so that typical matches produce changes large enough to matter but small enough that no single result swings a rating wildly:

| Scenario | Change |
|---|---|
| Equal players, close match (6-4) | ±0.032 |
| Equal players, shutout (6-0) | ±0.160 |
| 0.5-gap upset, shutout | ±0.321 |
| 1.0-gap upset, shutout | ±0.643 |

At this calibration a player needs a sustained run of strong results — not one lucky match — to move a half-level (0.5).

### 3.4 The upset multiplier: 2.0

When an underdog wins, both ratings are demonstrably wrong, and the evidence is strong — beating a better player is much harder than luck usually allows. The [upset multiplier](#upset-multiplier) of **2.0** makes upset results carry double weight, so miscalibrated ratings converge quickly instead of drifting for dozens of matches. It is a fixed constant regardless of gap size (the gap already enters scale linearly); see [§7](#7-edge-cases-and-known-limitations) for the implications.

### Constants summary

| Constant | Value | Derivation |
|---|---|---|
| `NTRP_RANGE` | 6.0 | 7.0 ceiling − 1.0 floor |
| `COMPETITIVE_THRESHOLD_PCT` | 0.083 | one NTRP half-level: 0.5 / 6.0 = 1/12 |
| `K_FACTOR_NTRP` | 0.16 | calibration anchor (±0.03–0.16 typical changes) |
| upset multiplier | 2.0 | upsets carry double evidence weight |

---

## 4. Dominance Factor Tables

All values from the winner's perspective: per set `setDominance = (W − L) / (W + L)`, and the match dominance is the average across sets.

### Single set

A one-set match's dominance is just that set's efficiency:

| Score | (W−L)/(W+L) | Dominance |
|---|---|---|
| 6-0 | 6/6 | **1.000** |
| 6-1 | 5/7 | **0.714** |
| 6-2 | 4/8 | **0.500** |
| 6-3 | 3/9 | **0.333** |
| 6-4 | 2/10 | **0.200** |
| 7-5 | 2/12 | **0.167** |
| 7-6 | 1/13 | **0.077** |

### Straight-sets matches (best of 3)

Match dominance = average of the two set dominances, for every combination of the seven standard set scores (symmetric — set order doesn't matter):

| | 6-0 | 6-1 | 6-2 | 6-3 | 6-4 | 7-5 | 7-6 |
|---|---|---|---|---|---|---|---|
| **6-0** | 1.000 | 0.857 | 0.750 | 0.667 | 0.600 | 0.583 | 0.538 |
| **6-1** | 0.857 | 0.714 | 0.607 | 0.524 | 0.457 | 0.440 | 0.396 |
| **6-2** | 0.750 | 0.607 | 0.500 | 0.417 | 0.350 | 0.333 | 0.288 |
| **6-3** | 0.667 | 0.524 | 0.417 | 0.333 | 0.267 | 0.250 | 0.205 |
| **6-4** | 0.600 | 0.457 | 0.350 | 0.267 | 0.200 | 0.183 | 0.138 |
| **7-5** | 0.583 | 0.440 | 0.333 | 0.250 | 0.183 | 0.167 | 0.122 |
| **7-6** | 0.538 | 0.396 | 0.288 | 0.205 | 0.138 | 0.122 | 0.077 |

Example reading: a 6-3, 7-5 win → (0.333 + 0.167) / 2 = **0.250**. Identical sets average to the single-set value (the diagonal matches the single-set table).

### Three-set matches (representative scenarios)

A lost set contributes its dominance as a negative term, dragging the average down — three-setters always score below the equivalent straight-sets win:

| Score | Per-set dominance | Average |
|---|---|---|
| 6-0, 0-6, 6-0 | +1.000, −1.000, +1.000 | 0.333 |
| 6-0, 3-6, 6-2 | +1.000, −0.333, +0.500 | 0.389 |
| 6-2, 3-6, 6-3 | +0.500, −0.333, +0.333 | 0.167 |
| 6-4, 4-6, 6-4 | +0.200, −0.200, +0.200 | 0.067 |
| 7-6, 6-7, 7-6 | +0.077, −0.077, +0.077 | 0.026 |

Note that every set carries equal weight in the average — a deciding third set counts the same as the first ([known limitation](#7-edge-cases-and-known-limitations)).

### Quick reference: change between equal players

Between equal-rated players, `scale = 1`, so `change = K × dominance`:

| Score | Dominance | NTRP change |
|---|---|---|
| 6-0, 6-0 | 1.000 | ±0.160 |
| 6-2, 6-2 | 0.500 | ±0.080 |
| 6-4, 6-4 | 0.200 | ±0.032 |
| 7-6, 7-6 | 0.077 | ±0.012 |

---

## 5. The Post-Processing Pipeline

The raw `change` from the master formula passes through three steps, in this order:

```
raw change → (1) smoothing (optional) → (2) boundary clamping → (3) new rating at 6 decimals
```

### 5.1 Smoothing (optional)

When enabled via request `options`, the **[smoothing factor](#smoothing-factor)** blends the new rating with the old one, USTA NTRP Dynamic style:

```
smoothed = (calculated × factor) + (previous × (1 − factor))
```

which simplifies to applying only a fraction of the change:

```
smoothedChange = rawChange × smoothingFactor
```

| Factor | Effect |
|---|---|
| 0.3 | Conservative — 30% of the change applied (established players) |
| 0.5 | USTA standard — 50% applied |
| 0.7 | Aggressive — 70% applied (newer players, faster convergence) |
| 1.0 | No smoothing (default behavior) |

Smoothing scales both players' changes identically, so it **preserves zero-sum**. Full guide: [RATING_SMOOTHING.md](RATING_SMOOTHING.md).

### 5.2 Boundary clamping

New ratings are clamped to the NTRP floor and ceiling, **1.0 – 7.0**.

Clamping is the *only* step that can break zero-sum. Example: a 7.0 NTRP player (at ceiling) wins; their calculated +0.2 clamps to +0.0, but the opponent still loses 0.2. This is intentional — keeping each rating accurate within its scale takes precedence over conserving points globally, and it prevents rating inflation at the edges.

### 5.3 Precision

All arithmetic uses `BigDecimal` at **6 decimal places** (`CALCULATION_SCALE = 6`) — no floating-point rounding error, deterministic results. Ratings, changes, and percent-changes are all serialized as strings at this precision.

---

## 6. Worked Examples

Each example runs the full master formula: `change = K × dominance × scale × sign`.

### Example 1 — Equal players, competitive match

P1 (5.0 NTRP) vs P2 (5.0 NTRP); P1 wins 6-4.

```
normalizedGap = 0.0 / 6.0            = 0.0
dominance     = (6−4) / (6+4)        = 0.2
scale (A)     = (0.083 − 0) / 0.083  = 1.0       (full change — nothing was expected)
change        = 0.16 × 0.2 × 1.0 × (+1) = +0.032

P1: 5.0 → 5.032        P2: 5.0 → 4.968
```

### Example 2 — Gap at threshold, expected win

P1 (4.5 NTRP) vs P2 (4.0 NTRP); P1 wins 6-3. Gap = 0.5, exactly one half-level.

```
normalizedGap = 0.5 / 6.0                = 0.083   (exactly at threshold)
dominance     = (6−3) / (6+3)            = 0.333
scale (A)     = (0.083 − 0.083) / 0.083  = 0.0
change        = 0.16 × 0.333 × 0.0 × (+1) = 0.0

No change — the favorite winning by a normal margin is exactly what the ratings predicted.
```

### Example 3 — Upset

P1 (3.0 NTRP) vs P2 (4.0 NTRP); P1 wins 6-2. Gap = 1.0, underdog wins.

```
normalizedGap = 1.0 / 6.0                  = 0.167
dominance     = (6−2) / (6+2)              = 0.5
scale (B)     = (0.167 / 0.083) × 2.0      = 4.0     (upset path)
change        = 0.16 × 0.5 × 4.0 × (+1)    = +0.32

P1: 3.0 → 3.32         P2: 4.0 → 3.68
```

Compare with Example 2: same K, similar dominance — but the surprising result moves ratings while the expected one doesn't.

### Example 4 — Large gap, dominant expected win

P1 (6.0 NTRP) vs P2 (3.0 NTRP); P1 wins 6-0, 6-0.

```
normalizedGap = 3.0 / 6.0                       = 0.5
dominance     = (1.0 + 1.0) / 2                 = 1.0    (two shutout sets averaged — maximum)
scale (A)     = max(0, (0.083 − 0.5) / 0.083)   = 0.0
change        = 0.16 × 1.0 × 0.0 × (+1)         = 0.0

No change — maximum dominance is irrelevant when the outcome carries no information.
```

### Example 5 — Small gap inside the threshold

P1 (4.3 NTRP) vs P2 (4.0 NTRP); P1 wins 7-5. Gap = 0.3.

```
normalizedGap = 0.3 / 6.0                   = 0.05
dominance     = (7−5) / (7+5)               = 0.167
scale (A)     = (0.083 − 0.05) / 0.083      = 0.398
change        = 0.16 × 0.167 × 0.398 × (+1) = +0.011

P1: 4.3 → 4.311        P2: 4.0 → 3.989
```

### The complete picture — every test scenario

The table below is the output of the `RatingChangeReport` test (`src/test/kotlin/.../impl/v1/RatingChangeReport.kt`), which runs all shared scenarios from `TestScenarios.kt` through the calculator. The scenarios come in five groups:

| Group | Rows | Coverage |
|---|---|---|
| **S** | 24 | Curated sweep of rating levels and gaps |
| **SM** | 18 | Smoothing factors (0.3 / 0.5 / 0.7) |
| **GS** | 35 | Every legal set score (6-0 … 7-6) per representative rating difference |
| **PL** | 600 | *Generated:* every matchup of the published competition levels 2.5–7.0 (all 100 ordered pairs — expected wins, upsets, and equal pairings) at every score from 6-0 to 7-5 |
| **CG** | 72 | *Generated:* competitive matchups between 3.0 and 4.5 with a 0.25 gap (below the 0.5 threshold), both directions, at every score from 6-0 to 7-5 |
| **EX** | 14 | *Generated:* extreme matchup 2.0 vs 7.0 (5.0 gap), both directions, at every legal set score including the 7-6 tiebreak |

The PL, CG, and EX groups are generated in `TestScenarios.kt` with expected deltas computed independently from the master formula (the same 6-decimal BigDecimal precision as the calculator), so the parameterized test suite asserts all 763 scenarios. The table below shows the curated S/SM/GS rows plus the EX extreme-matchup rows; the full 763-row report including PL and CG is regenerated by the command at the end of this section.

**Constants in effect** (from `PerformanceBasedRankingCalculatorImpl`):

```
K_NTRP                    = 0.16
COMPETITIVE_THRESHOLD_PCT = 0.083  (8.3% of range = 0.5 NTRP points)
Upset multiplier          = 2.0
Rating range              = NTRP 1.0–7.0 (6.0)
Smoothing                 = off unless the scenario says otherwise (SM rows)
```

How to read the table: P1 is always the match winner. Each player's column shows their rating change and resulting rating, so the zero-sum property is directly visible — P2's change is the exact negative of P1's (no scenario hits a rating boundary). **⚡** marks a published-level change for at least one player.

| ID | Scenario | NTRP P1 vs P2 | Score | P1 Δ (new) | P2 Δ (new) | Level |
|---|---|---|---|---|---|---|
| S1 | Low: Equal players, dominant | 2.5 vs 2.5 | 6-0 | +0.160000 (2.660000) | -0.160000 (2.340000) | ⚡ |
| S2 | Low: Equal players, close | 2.5 vs 2.5 | 6-4 | +0.032000 (2.532000) | -0.032000 (2.468000) | ⚡ |
| S3 | Low: Below threshold, 0.1 gap | 2.6 vs 2.5 | 6-0 | +0.127871 (2.727871) | -0.127871 (2.372129) | ⚡ |
| S4 | Low: 0.5 gap, expected win | 3.0 vs 2.5 | 6-0 | +0.000000 (3.000000) | +0.000000 (2.500000) |  |
| S5 | Low: 0.5 gap, expected win | 3.0 vs 2.5 | 6-4 | +0.000000 (3.000000) | +0.000000 (2.500000) |  |
| S6 | Low: Below threshold, 0.1 gap | 3.1 vs 3.0 | 6-0 | +0.127871 (3.227871) | -0.127871 (2.872129) | ⚡ |
| S7 | Low: 1.0 gap, expected win | 3.5 vs 2.5 | 6-0 | +0.000000 (3.500000) | +0.000000 (2.500000) |  |
| S8 | Low: 1.0 gap, upset | 2.5 vs 3.5 | 6-0 | +0.642572 (3.142572) | -0.642572 (2.857428) | ⚡ |
| S9 | Low: Below threshold, 0.1 gap | 3.6 vs 3.5 | 6-0 | +0.127871 (3.727871) | -0.127871 (3.372129) | ⚡ |
| S10 | Low: 1.5 gap, expected win | 4.0 vs 2.5 | 6-0 | +0.000000 (4.000000) | +0.000000 (2.500000) |  |
| S11 | Low: Below threshold, 0.1 gap | 4.1 vs 4.0 | 6-0 | +0.127871 (4.227871) | -0.127871 (3.872129) | ⚡ |
| S12 | Mid: 2.0 gap, big upset | 2.5 vs 4.5 | 6-0 | +1.285139 (3.785139) | -1.285139 (3.214861) | ⚡ |
| S13 | Mid: Expected win, dominant | 4.5 vs 4.0 | 6-0 | +0.000000 (4.500000) | +0.000000 (4.000000) |  |
| S14 | Mid: Expected win, close | 4.5 vs 4.0 | 6-4 | +0.000000 (4.500000) | +0.000000 (4.000000) |  |
| S15 | Mid: Upset, dominant | 4.0 vs 4.5 | 6-0 | +0.321284 (4.321284) | -0.321284 (4.178716) | ⚡ |
| S16 | Mid: Upset, close | 4.0 vs 4.5 | 6-4 | +0.064257 (4.064257) | -0.064257 (4.435743) | ⚡ |
| S17 | Mid: Competitive, dominant | 4.5 vs 4.3 | 6-0 | +0.095744 (4.595744) | -0.095744 (4.204256) |  |
| S18 | Mid: Competitive, close | 4.5 vs 4.3 | 6-4 | +0.019149 (4.519149) | -0.019149 (4.280851) |  |
| S19 | Mid: Equal players, dominant | 4.5 vs 4.5 | 6-0 | +0.160000 (4.660000) | -0.160000 (4.340000) | ⚡ |
| S20 | Mid: Equal players, close | 4.5 vs 4.5 | 6-4 | +0.032000 (4.532000) | -0.032000 (4.468000) | ⚡ |
| S21 | Mid: Below threshold, 0.1 gap | 4.6 vs 4.5 | 6-0 | +0.127871 (4.727871) | -0.127871 (4.372129) | ⚡ |
| S22 | High: Large gap, expected win | 5.0 vs 4.0 | 6-0 | +0.000000 (5.000000) | +0.000000 (4.000000) |  |
| S23 | High: Large gap, big upset | 4.0 vs 5.0 | 6-0 | +0.642572 (4.642572) | -0.642572 (4.357428) | ⚡ |
| S24 | High: Below threshold, 0.1 gap | 5.1 vs 5.0 | 6-0 | +0.127871 (5.227871) | -0.127871 (4.872129) | ⚡ |
| SM1 | Smoothing 0.3: Equal, dominant | 4.0 vs 4.0 | 6-0 | +0.048000 (4.048000) | -0.048000 (3.952000) | ⚡ |
| SM2 | Smoothing 0.5: Equal, dominant | 4.0 vs 4.0 | 6-0 | +0.080000 (4.080000) | -0.080000 (3.920000) | ⚡ |
| SM3 | Smoothing 0.7: Equal, dominant | 4.0 vs 4.0 | 6-0 | +0.112000 (4.112000) | -0.112000 (3.888000) | ⚡ |
| SM4 | Smoothing 0.3: Equal, close | 4.0 vs 4.0 | 6-4 | +0.009600 (4.009600) | -0.009600 (3.990400) | ⚡ |
| SM5 | Smoothing 0.5: Equal, close | 4.0 vs 4.0 | 6-4 | +0.016000 (4.016000) | -0.016000 (3.984000) | ⚡ |
| SM6 | Smoothing 0.5: Upset | 4.0 vs 4.5 | 6-0 | +0.160642 (4.160642) | -0.160642 (4.339358) | ⚡ |
| SM7 | Smoothing 0.5: Expected win at threshold | 4.5 vs 4.0 | 6-0 | +0.000000 (4.500000) | +0.000000 (4.000000) |  |
| SM8 | Smoothing 0.5: Competitive gap, dominant | 4.5 vs 4.3 | 6-0 | +0.047872 (4.547872) | -0.047872 (4.252128) |  |
| SM9 | Smoothing 0.3: Competitive gap, close | 4.5 vs 4.3 | 6-4 | +0.005745 (4.505745) | -0.005745 (4.294255) |  |
| SM10 | Smoothing 0.3: Small upset | 4.0 vs 4.5 | 6-4 | +0.019277 (4.019277) | -0.019277 (4.480723) | ⚡ |
| SM11 | Smoothing 0.7: Medium upset | 4.0 vs 4.5 | 6-0 | +0.224899 (4.224899) | -0.224899 (4.275101) | ⚡ |
| SM12 | Smoothing 0.5: Large upset (1.0 gap) | 2.5 vs 3.5 | 6-0 | +0.321286 (2.821286) | -0.321286 (3.178714) | ⚡ |
| SM13 | Smoothing 0.3: Huge upset (2.0 gap) | 2.5 vs 4.5 | 6-0 | +0.385542 (2.885542) | -0.385542 (4.114458) | ⚡ |
| SM14 | Smoothing 0.5: Small gap (0.1), dominant | 4.1 vs 4.0 | 6-0 | +0.063935 (4.163935) | -0.063935 (3.936065) | ⚡ |
| SM15 | Smoothing 0.7: Small gap (0.1), close | 4.6 vs 4.5 | 6-4 | +0.017902 (4.617902) | -0.017902 (4.482098) | ⚡ |
| SM16 | Smoothing 0.5: High rating upset | 4.0 vs 5.0 | 6-0 | +0.321286 (4.321286) | -0.321286 (4.678714) | ⚡ |
| SM17 | Smoothing 0.3: Low rating levels | 2.6 vs 2.5 | 6-0 | +0.038361 (2.638361) | -0.038361 (2.461639) | ⚡ |
| SM18 | Smoothing 0.7: High rating levels | 5.1 vs 5.0 | 6-0 | +0.089510 (5.189510) | -0.089510 (4.910490) | ⚡ |
| GS1 | Scores: Equal players | 4.0 vs 4.0 | 6-0 | +0.160000 (4.160000) | -0.160000 (3.840000) | ⚡ |
| GS2 | Scores: Equal players | 4.0 vs 4.0 | 6-1 | +0.114286 (4.114286) | -0.114286 (3.885714) | ⚡ |
| GS3 | Scores: Equal players | 4.0 vs 4.0 | 6-2 | +0.080000 (4.080000) | -0.080000 (3.920000) | ⚡ |
| GS4 | Scores: Equal players | 4.0 vs 4.0 | 6-3 | +0.053333 (4.053333) | -0.053333 (3.946667) | ⚡ |
| GS5 | Scores: Equal players | 4.0 vs 4.0 | 6-4 | +0.032000 (4.032000) | -0.032000 (3.968000) | ⚡ |
| GS6 | Scores: Equal players | 4.0 vs 4.0 | 7-5 | +0.026667 (4.026667) | -0.026667 (3.973333) | ⚡ |
| GS7 | Scores: Equal players | 4.0 vs 4.0 | 7-6 | +0.012308 (4.012308) | -0.012308 (3.987692) | ⚡ |
| GS8 | Scores: 0.25 gap, expected win | 4.25 vs 4.0 | 6-0 | +0.079678 (4.329678) | -0.079678 (3.920322) | ⚡ |
| GS9 | Scores: 0.25 gap, expected win | 4.25 vs 4.0 | 6-1 | +0.056913 (4.306913) | -0.056913 (3.943087) | ⚡ |
| GS10 | Scores: 0.25 gap, expected win | 4.25 vs 4.0 | 6-2 | +0.039839 (4.289839) | -0.039839 (3.960161) | ⚡ |
| GS11 | Scores: 0.25 gap, expected win | 4.25 vs 4.0 | 6-3 | +0.026559 (4.276559) | -0.026559 (3.973441) | ⚡ |
| GS12 | Scores: 0.25 gap, expected win | 4.25 vs 4.0 | 6-4 | +0.015936 (4.265936) | -0.015936 (3.984064) | ⚡ |
| GS13 | Scores: 0.25 gap, expected win | 4.25 vs 4.0 | 7-5 | +0.013280 (4.263280) | -0.013280 (3.986720) | ⚡ |
| GS14 | Scores: 0.25 gap, expected win | 4.25 vs 4.0 | 7-6 | +0.006129 (4.256129) | -0.006129 (3.993871) | ⚡ |
| GS15 | Scores: 0.25 gap, upset | 4.0 vs 4.25 | 6-0 | +0.160644 (4.160644) | -0.160644 (4.089356) |  |
| GS16 | Scores: 0.25 gap, upset | 4.0 vs 4.25 | 6-1 | +0.114746 (4.114746) | -0.114746 (4.135254) |  |
| GS17 | Scores: 0.25 gap, upset | 4.0 vs 4.25 | 6-2 | +0.080322 (4.080322) | -0.080322 (4.169678) |  |
| GS18 | Scores: 0.25 gap, upset | 4.0 vs 4.25 | 6-3 | +0.053548 (4.053548) | -0.053548 (4.196452) |  |
| GS19 | Scores: 0.25 gap, upset | 4.0 vs 4.25 | 6-4 | +0.032129 (4.032129) | -0.032129 (4.217871) |  |
| GS20 | Scores: 0.25 gap, upset | 4.0 vs 4.25 | 7-5 | +0.026774 (4.026774) | -0.026774 (4.223226) |  |
| GS21 | Scores: 0.25 gap, upset | 4.0 vs 4.25 | 7-6 | +0.012357 (4.012357) | -0.012357 (4.237643) |  |
| GS22 | Scores: 0.5 gap, upset | 4.0 vs 4.5 | 6-0 | +0.321284 (4.321284) | -0.321284 (4.178716) | ⚡ |
| GS23 | Scores: 0.5 gap, upset | 4.0 vs 4.5 | 6-1 | +0.229489 (4.229489) | -0.229489 (4.270511) | ⚡ |
| GS24 | Scores: 0.5 gap, upset | 4.0 vs 4.5 | 6-2 | +0.160642 (4.160642) | -0.160642 (4.339358) | ⚡ |
| GS25 | Scores: 0.5 gap, upset | 4.0 vs 4.5 | 6-3 | +0.107095 (4.107095) | -0.107095 (4.392905) | ⚡ |
| GS26 | Scores: 0.5 gap, upset | 4.0 vs 4.5 | 6-4 | +0.064257 (4.064257) | -0.064257 (4.435743) | ⚡ |
| GS27 | Scores: 0.5 gap, upset | 4.0 vs 4.5 | 7-5 | +0.053547 (4.053547) | -0.053547 (4.446453) | ⚡ |
| GS28 | Scores: 0.5 gap, upset | 4.0 vs 4.5 | 7-6 | +0.024714 (4.024714) | -0.024714 (4.475286) | ⚡ |
| GS29 | Scores: 1.0 gap, upset | 4.0 vs 5.0 | 6-0 | +0.642572 (4.642572) | -0.642572 (4.357428) | ⚡ |
| GS30 | Scores: 1.0 gap, upset | 4.0 vs 5.0 | 6-1 | +0.458980 (4.458980) | -0.458980 (4.541020) | ⚡ |
| GS31 | Scores: 1.0 gap, upset | 4.0 vs 5.0 | 6-2 | +0.321286 (4.321286) | -0.321286 (4.678714) | ⚡ |
| GS32 | Scores: 1.0 gap, upset | 4.0 vs 5.0 | 6-3 | +0.214190 (4.214190) | -0.214190 (4.785810) | ⚡ |
| GS33 | Scores: 1.0 gap, upset | 4.0 vs 5.0 | 6-4 | +0.128514 (4.128514) | -0.128514 (4.871486) | ⚡ |
| GS34 | Scores: 1.0 gap, upset | 4.0 vs 5.0 | 7-5 | +0.107095 (4.107095) | -0.107095 (4.892905) | ⚡ |
| GS35 | Scores: 1.0 gap, upset | 4.0 vs 5.0 | 7-6 | +0.049429 (4.049429) | -0.049429 (4.950571) | ⚡ |
| EX1 | Extreme: 7.0 vs 2.0, expected | 7.0 vs 2.0 | 6-0 | +0.000000 (7.000000) | +0.000000 (2.000000) |  |
| EX2 | Extreme: 7.0 vs 2.0, expected | 7.0 vs 2.0 | 6-1 | +0.000000 (7.000000) | +0.000000 (2.000000) |  |
| EX3 | Extreme: 7.0 vs 2.0, expected | 7.0 vs 2.0 | 6-2 | +0.000000 (7.000000) | +0.000000 (2.000000) |  |
| EX4 | Extreme: 7.0 vs 2.0, expected | 7.0 vs 2.0 | 6-3 | +0.000000 (7.000000) | +0.000000 (2.000000) |  |
| EX5 | Extreme: 7.0 vs 2.0, expected | 7.0 vs 2.0 | 6-4 | +0.000000 (7.000000) | +0.000000 (2.000000) |  |
| EX6 | Extreme: 7.0 vs 2.0, expected | 7.0 vs 2.0 | 7-5 | +0.000000 (7.000000) | +0.000000 (2.000000) |  |
| EX7 | Extreme: 7.0 vs 2.0, expected | 7.0 vs 2.0 | 7-6 | +0.000000 (7.000000) | +0.000000 (2.000000) |  |
| EX8 | Extreme: 2.0 vs 7.0, upset | 2.0 vs 7.0 | 6-0 | +3.212850 (5.212850) | -3.212850 (3.787150) | ⚡ |
| EX9 | Extreme: 2.0 vs 7.0, upset | 2.0 vs 7.0 | 6-1 | +2.294894 (4.294894) | -2.294894 (4.705106) | ⚡ |
| EX10 | Extreme: 2.0 vs 7.0, upset | 2.0 vs 7.0 | 6-2 | +1.606425 (3.606425) | -1.606425 (5.393575) | ⚡ |
| EX11 | Extreme: 2.0 vs 7.0, upset | 2.0 vs 7.0 | 6-3 | +1.070949 (3.070949) | -1.070949 (5.929051) | ⚡ |
| EX12 | Extreme: 2.0 vs 7.0, upset | 2.0 vs 7.0 | 6-4 | +0.642570 (2.642570) | -0.642570 (6.357430) | ⚡ |
| EX13 | Extreme: 2.0 vs 7.0, upset | 2.0 vs 7.0 | 7-5 | +0.535476 (2.535476) | -0.535476 (6.464524) | ⚡ |
| EX14 | Extreme: 2.0 vs 7.0, upset | 2.0 vs 7.0 | 7-6 | +0.247142 (2.247142) | -0.247142 (6.752858) | ⚡ |

Patterns the table makes visible at a glance:

- **Expected wins are free** — every favorite-wins-at-or-beyond-threshold row (S4, S5, S7, S10, S13, S14, S22, SM7) is exactly zero for both players, regardless of score.
- **Upsets dominate the magnitude scale** — a 2.0-gap shutout upset moves ±1.285 (S12), and the extreme 5.0-gap upset (EX8) moves ±3.213 in a single match: the 2.0 jumps past 5.2 while the 7.0 falls below 3.8. Even the narrowest extreme upset (EX14, 7-6) moves ±0.247 — more than a shutout between equals.
- **Zero-sum holds in every row** — P1's gain and P2's loss mirror exactly before clamping.
- **Smoothing scales rows proportionally** — SM2 (factor 0.5) is exactly half of S19's unsmoothed ±0.160.
- **Score margin scales every matchup linearly** — within each GS group the deltas are proportional to dominance: between equals, 6-0 moves ±0.160 down to ±0.012 for 7-6; the same 13× spread holds in every upset group (e.g. 1.0-gap: ±0.643 down to ±0.049).

To regenerate the full report, including published levels:

```bash
./gradlew test --tests "*.RatingChangeReport"
# full fixed-width report (incl. published levels) written to /tmp/rating_change_report.txt
```

### Exhaustive matchup matrix

For the truly complete picture, the `NtrpMatchupMatrixReport` test sweeps **every possible NTRP matchup at every legal set score**: all 13 published levels (1.0–7.0 in 0.5 steps) crossed with themselves and with all 7 single-set scores — 1,183 cells, each verified against the master formula computed from first principles (including boundary clamping at the 1.0/7.0 edges):

```bash
./gradlew test --tests "*.NtrpMatchupMatrixReport"
# one 13×13 delta matrix per set score written to /tmp/ntrp_matchup_matrix.txt
```

What the matrices show at a glance:

- **The diagonal** (equal players) carries the pure performance-based change: K × dominance (e.g. +0.160 for 6-0, +0.012 for 7-6).
- **Above the diagonal** (favorite wins) is *entirely zero*: published levels are 0.5 apart, which is exactly the competitive threshold, so every favorite win between distinct levels is fully expected. Non-zero competitive-path changes only occur for gaps below 0.5 — i.e. between unpublished intermediate ratings.
- **Below the diagonal** (upsets) deltas grow linearly with the gap, up to +3.855 for a 1.0-rated player blanking a 7.0 (before any smoothing).
- **The corners** are the only cells where zero-sum breaks (marked `*`): at 1.0 vs 1.0 the loser is clamped at the floor; at 7.0 vs 7.0 the winner is clamped at the ceiling.

---

## 7. Edge Cases and Known Limitations

### Edge cases (handled by design)

- **Shutouts** — `dominance = (6−0)/(6+0) = 1.0` with no division-by-zero; the normalized definition needs no special case.
- **Near-equal ratings** — gaps of e.g. 0.005 produce `normalizedGap ≈ 0`, `scale ≈ 1.0`: effectively treated as equals, continuously (there is no explicit equality branch).
- **Player at a boundary** — clamping absorbs the change for that player only ([§5.2](#52-boundary-clamping)).
- **Tiebreak sets** — a 7-6 set contributes 7 vs 6 games like any other; the `TiebreakScore` object is informational (it documents the tiebreak points, e.g. 7-5 or 10-8, and must be won by the set winner) and its points are never included in the dominance calculation.

### Known limitations

1. **No historical context** — every match is independent; no trends, streaks, or rating history.
2. **No time decay** — a six-month-old match weighs the same as yesterday's.
3. **No score validation in the calculator** — it trusts the model layer (`MatchScore`/`SetScore` validation) to reject illegal scores like 8-0.
4. **Tiebreak points ignored** — only game counts matter.
5. **Sets weigh equally** — match dominance averages per-set dominance with no extra weight for deciding sets; a clutch third-set win counts the same as the first set.
6. **Fixed upset multiplier** — 2.0 regardless of gap size or score; the gap's magnitude enters only linearly through scale.

Possible future refinements (historical weighting, time decay, set-depth weighting, surface adjustments, graduated upset multiplier) are intentionally out of scope for v1.

---

## 8. Implementation Map

| What | Where |
|---|---|
| Algorithm (all of §1–§3, §5) | `src/main/kotlin/org/skopeo/service/calculator/impl/v1/PerformanceBasedRankingCalculatorImpl.kt` |
| Dominance factor | `calculateDominanceFactor()` in `src/main/kotlin/org/skopeo/model/MatchScore.kt` |
| BigDecimal precision helpers | `src/main/kotlin/org/skopeo/service/calculator/impl/BigDecimalUtils.kt` |
| Audit trail (every step logged) | `src/main/kotlin/org/skopeo/service/calculator/AuditTrail.kt` — see [AUDIT_TRAIL.md](../engineering/architecture/AUDIT_TRAIL.md) |

Key methods in the calculator:

1. `calculate(request)` — entry point; orchestrates, builds audit trail and response.
2. `calculateRatingAdjustments(...)` — the master formula and two-path scale selection.
3. `applyRatingChange(...)` → `applyNTRPChange` — smoothing and boundary clamping.

Test coverage: `PerformanceBasedRankingCalculatorImplTest` (763 NTRP scenarios, mostly generated — see [§6](#the-complete-picture--every-test-scenario)), `MatchScoreTest` (per-set dominance averaging), `RatingChangeReport` (generates the [§6 rating-change table](#the-complete-picture--every-test-scenario)), `NtrpMatchupMatrixReport` (all 1,183 level×score combinations verified against the formula, see [§6](#exhaustive-matchup-matrix)), `RankingCalculationPayloadTest` (exact values), `RankingCalculationApiErrorTest` (boundaries). The calculator is a pure function returning result + audit trail, so all of this is tested without mocks.

---

## 9. Glossary

#### Elo rating system
The classical chess rating method ([Wikipedia](https://en.wikipedia.org/wiki/Elo_rating_system)): after each game, points transfer from loser to winner, with the amount based on how expected the result was. Skopeo extends Elo with margin-of-victory awareness (the dominance factor).

#### K-factor
The Elo constant that sets rating **volatility** — how many points a single match can move. In Skopeo it equals the maximum non-upset change (reached at full dominance between equals). NTRP uses K = 0.16 (the calibration anchor). See [§2.1](#21-k--the-step-size), [§3.3](#33-the-k-factor-016).

#### Rating gap
The absolute difference between the two players' ratings before the match, `|rating₁ − rating₂|`. The gap drives the scale factor: small gaps mean competitive matches (results carry information), large gaps mean predictable matches (a favorite's win carries none). Also called *rating differential*.

#### Rating range
Ceiling minus floor of the NTRP scale: **6.0** (7.0 − 1.0). Used to normalize gaps so the threshold and scale factors are independent of the scale's absolute width. See [§3.1](#31-the-rating-range-60).

#### Normalized gap
The rating gap expressed as a fraction of the rating range: `normalizedGap = gap / range`. A 0.5 NTRP gap normalizes to 8.3%. See [§3.1](#31-the-rating-range-60).

#### Competitive threshold
The normalized gap (8.3% of range ≈ 1/12, i.e. 0.5 NTRP points) beyond which a favorite's win is treated as fully expected and produces zero change. Anchored to one NTRP **half-level** — the smallest published skill increment. It is the counterpart concept to the rating gap: the gap measures how far apart two players are; the threshold defines how far apart they can be while their match still counts as competitive. See [§3.2](#32-the-competitive-threshold-83--05-ntrp).

#### Dominance factor
Match closeness, computed per set as `(gamesWon − gamesLost) / (gamesWon + gamesLost)` — the [efficiency formula](#efficiency-formula) applied to games — and averaged across sets for the match. Ranges 0 (perfectly even) to 1.0 (shutout) for the winner; a lost set enters the average as a negative term. See [§2.2](#22-dominance--how-convincingly) and the [tables in §4](#4-dominance-factor-tables).

#### Efficiency formula
The general statistic *net successes divided by total attempts*: `(successes − failures) / attempts`. The dominance factor is exactly this formula with games as the unit — net games won per game played.

#### Scale factor
The "surprise" term of the master formula, computed from the normalized gap by one of two paths: expected/competitive (shrinks linearly from 1.0 to 0 as the gap approaches the threshold) or upset (grows linearly with the gap, doubled by the upset multiplier). See [§2.3](#23-scale--how-surprising).

#### Upset
A match where the lower-rated player (underdog) beats the higher-rated player (favorite). Upsets are the strongest evidence that ratings are wrong, so they take the high-scale path.

#### Upset multiplier
The constant **2.0** applied to the scale factor on the upset path, making surprising results move ratings twice as hard as the linear gap term alone. See [§3.4](#34-the-upset-multiplier-20).

#### Sign
+1 for the match winner, −1 for the loser. The only per-player factor in the formula; it makes raw changes zero-sum.

#### Zero-sum
The property that the winner's gain equals the loser's loss (`change₁ + change₂ = 0`). Holds for raw and smoothed changes; can be intentionally broken by boundary clamping. See [§5.2](#52-boundary-clamping).

#### Smoothing factor
An optional damping multiplier (USTA NTRP Dynamic style) applied to the raw change before clamping: 0.5 means only half the calculated change is applied. Reduces volatility from single outlier performances. See [§5.1](#51-smoothing-optional) and [RATING_SMOOTHING.md](RATING_SMOOTHING.md).

#### Boundary clamping
Forcing a new rating back inside the NTRP floor/ceiling (1.0–7.0). The final pipeline step and the only one that may break zero-sum.

#### Published level
The discrete, public-facing rating bucket (NTRP in 0.5 steps) derived from the continuous internal rating. The API reports `levelChanged` when a rating change crosses a bucket boundary.

---

## References

- **Elo Rating System**: [Wikipedia](https://en.wikipedia.org/wiki/Elo_rating_system)
- **NTRP**: USTA National Tennis Rating Program (1.0–7.0 scale, 0.5-step published levels)
- **Rating smoothing**: [RATING_SMOOTHING.md](RATING_SMOOTHING.md)
- **Audit trail design**: [AUDIT_TRAIL.md](../engineering/architecture/AUDIT_TRAIL.md)

---

**Document Version**: 3.2 (NTRP-only; removed UTR and the rating-system concept; previously 3.1: per-set dominance averaging; 3.0: top-down restructure, renamed from ALGORITHM_BEHAVIOR.md)
**Last Updated**: 2026-06-10
**Algorithm Version**: Performance-Based Elo v2.1 (Normalized Gap + Per-Set Dominance Averaging)
