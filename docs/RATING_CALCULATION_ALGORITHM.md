# Rating Calculation Algorithm

How Skopeo turns a match result into rating changes — explained top-down, from the single master formula to every constant in it.

**Reading guide:**

1. [The big picture](#1-the-big-picture) — the algorithm in one sentence and one formula
2. [The four factors](#2-the-four-factors) — what each term in the formula means and how it is computed
3. [Deriving the constants](#3-deriving-the-constants) — where 6.0, 15.0, 0.5, 1.25, 0.16, 0.4, and 2.0 come from
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

Every rating change, for both players, in both rating systems, is one multiplication:

```
change = K × dominance × scale × sign
```

| Factor | Question it answers | Range | Detail |
|---|---|---|---|
| `K` | How big is one "step" on this rating scale? | 0.16 (NTRP) / 0.4 (UTR) | [§2.1](#21-k--the-step-size) |
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

| System | K | Typical changes it produces |
|---|---|---|
| NTRP | 0.16 | ±0.032 (6-4 between equals) up to ±0.160 (6-0 between equals) |
| UTR | 0.40 | ±0.080 up to ±0.400 (same scenarios) |

A larger K means faster convergence toward true skill but noisier ratings; a smaller K means stable but slow-moving ratings. Why exactly 0.16 and 0.40 — see [§3.3](#33-the-k-factors-016-and-04).

### 2.2 `dominance` — how convincingly

The **[dominance factor](#dominance-factor)** measures match closeness from total games won and lost:

```
dominance = (gamesWon − gamesLost) / (gamesWon + gamesLost)
```

This is the standard **[efficiency formula](#efficiency-formula)** — *net successes divided by total attempts* — applied to games:

```
dominance = netGamesWon / totalGames
```

Read it as: **how many games of net advantage the player earned per game played**. Winning exactly half the games yields zero efficiency (a perfectly even match); winning every game yields 1.0 (maximum efficiency, a shutout).

Properties that fall out of this definition for free:

- **Naturally bounded** to [−1, +1] — no artificial cap needed, shutouts cause no division-by-zero.
- **Symmetric**: the loser's dominance is exactly the negative of the winner's. (The formula uses the magnitude; `sign` carries direction.)
- **Multi-set aware**: games are totalled across all sets, so a 6-0, 6-0 sweep (12/12 games) scores 1.0 while a 7-6, 6-7, 7-6 marathon (20/39 games) scores 0.026.

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

None of the algorithm's numbers are arbitrary; each one is derived from the published shape of the two rating systems.

### 3.1 The rating ranges: 6.0 and 15.0

The **[rating range](#rating-range)** is simply *ceiling minus floor* of each system's scale:

| System | Floor | Ceiling | Range |
|---|---|---|---|
| NTRP | 1.0 (beginner) | 7.0 (world-class) | 7.0 − 1.0 = **6.0** |
| UTR | 1.0 | 16.0 (top professional) | 16.0 − 1.0 = **15.0** |

The range is what makes gaps comparable *across* systems via the [normalized gap](#normalized-gap): a 0.5 NTRP gap and a 1.25 UTR gap are the *same* relative skill difference, because both are 8.3% of their respective ranges:

```
0.5 / 6.0   = 0.0833
1.25 / 15.0 = 0.0833
```

Without normalization, the algorithm would treat a 1-point UTR gap (small) the same as a 1-point NTRP gap (huge).

### 3.2 The competitive threshold: 8.3% → 0.5 NTRP / 1.25 UTR

The **[competitive threshold](#competitive-threshold)** marks the gap beyond which a favorite's win is considered fully expected. Its anchor is the NTRP system itself: NTRP publishes levels in **0.5-point steps** (3.5, 4.0, 4.5, …), so one half-level — the smallest officially recognized skill difference — is the natural boundary of "still a competitive match":

```
threshold = half-level / range = 0.5 / 6.0 = 1/12 ≈ 0.083 = 8.3%
```

Because the threshold is expressed as a *percentage of range*, it transfers to UTR automatically:

```
NTRP: 8.3% × 6.0  = 0.5  rating points
UTR:  8.3% × 15.0 = 1.25 rating points
```

### 3.3 The K-factors: 0.16 and 0.4

**NTRP's K = 0.16 is the calibration anchor**, chosen so that typical matches produce changes large enough to matter but small enough that no single result swings a rating wildly:

| Scenario | Change |
|---|---|
| Equal players, close match (6-4) | ±0.032 |
| Equal players, shutout (6-0) | ±0.160 |
| 0.5-gap upset, shutout | ±0.321 |
| 1.0-gap upset, shutout | ±0.643 |

At this calibration a player needs a sustained run of strong results — not one lucky match — to move a half-level (0.5).

**UTR's K is then derived, not chosen.** To keep volatility identical *as a percentage of range*, K scales by the range ratio:

```
K_UTR = K_NTRP × (UTR range / NTRP range) = 0.16 × (15.0 / 6.0) = 0.16 × 2.5 = 0.4
```

So UTR ratings move 2.5× more points per match than NTRP — but the same *fraction of the scale*. This 2.5× ratio is verified by the `RatingChangeReport` test suite.

### 3.4 The upset multiplier: 2.0

When an underdog wins, both ratings are demonstrably wrong, and the evidence is strong — beating a better player is much harder than luck usually allows. The [upset multiplier](#upset-multiplier) of **2.0** makes upset results carry double weight, so miscalibrated ratings converge quickly instead of drifting for dozens of matches. It is a fixed constant regardless of gap size (the gap already enters scale linearly); see [§7](#7-edge-cases-and-known-limitations) for the implications.

### Constants summary

| Constant | Value | Derivation |
|---|---|---|
| `NTRP_RANGE` | 6.0 | 7.0 ceiling − 1.0 floor |
| `UTR_RANGE` | 15.0 | 16.0 ceiling − 1.0 floor |
| `COMPETITIVE_THRESHOLD_PCT` | 0.083 | one NTRP half-level: 0.5 / 6.0 = 1/12 |
| `K_FACTOR_NTRP` | 0.16 | calibration anchor (±0.03–0.16 typical changes) |
| `K_FACTOR_UTR` | 0.4 | derived: 0.16 × (15.0 / 6.0) |
| upset multiplier | 2.0 | upsets carry double evidence weight |

---

## 4. Dominance Factor Tables

All values from `dominance = (W − L) / (W + L)`, games totalled across sets, winner's perspective.

### Single set

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

Dominance for every combination of the seven standard set scores (symmetric — set order doesn't matter):

| | 6-0 | 6-1 | 6-2 | 6-3 | 6-4 | 7-5 | 7-6 |
|---|---|---|---|---|---|---|---|
| **6-0** | 1.000 | 0.846 | 0.714 | 0.600 | 0.500 | 0.444 | 0.368 |
| **6-1** | 0.846 | 0.714 | 0.600 | 0.500 | 0.412 | 0.368 | 0.300 |
| **6-2** | 0.714 | 0.600 | 0.500 | 0.412 | 0.333 | 0.300 | 0.238 |
| **6-3** | 0.600 | 0.500 | 0.412 | 0.333 | 0.263 | 0.238 | 0.182 |
| **6-4** | 0.500 | 0.412 | 0.333 | 0.263 | 0.200 | 0.182 | 0.130 |
| **7-5** | 0.444 | 0.368 | 0.300 | 0.238 | 0.182 | 0.167 | 0.120 |
| **7-6** | 0.368 | 0.300 | 0.238 | 0.182 | 0.130 | 0.120 | 0.077 |

Example reading: a 6-3, 7-5 win → games 13–8 → (13−8)/21 = **0.238**.

### Three-set matches (representative scenarios)

Dropping a set costs games, so three-setters always score below the equivalent straight-sets win:

| Score | Games | Dominance |
|---|---|---|
| 6-0, 0-6, 6-0 | 12–6 | 0.333 |
| 6-0, 3-6, 6-2 | 15–8 | 0.304 |
| 6-2, 3-6, 6-3 | 15–11 | 0.154 |
| 6-4, 4-6, 6-4 | 16–14 | 0.067 |
| 7-6, 6-7, 7-6 | 20–19 | 0.026 |

Note that 6-0, 0-6, 6-0 (dominance 0.333) is treated identically to a single 6-3 set — the algorithm sees only game totals, not set structure ([known limitation](#7-edge-cases-and-known-limitations)).

### Quick reference: change between equal players

Between equal-rated players, `scale = 1`, so `change = K × dominance`:

| Score | Dominance | NTRP change | UTR change (×2.5) |
|---|---|---|---|
| 6-0, 6-0 | 1.000 | ±0.160 | ±0.400 |
| 6-2, 6-2 | 0.500 | ±0.080 | ±0.200 |
| 6-4, 6-4 | 0.200 | ±0.032 | ±0.080 |
| 7-6, 7-6 | 0.077 | ±0.012 | ±0.031 |

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

New ratings are clamped to the system's floor and ceiling:

- **NTRP**: 1.0 – 7.0
- **UTR**: 1.0 – 16.0

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
dominance     = (12−0) / (12+0)                 = 1.0    (maximum)
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

### Example 6 — Same match in UTR

Equal players (10.0 UTR each); winner takes it 6-4.

```
dominance = 0.2,  scale = 1.0,  K_UTR = 0.4
change    = 0.4 × 0.2 × 1.0 × (+1) = +0.080

Exactly 2.5× the NTRP change from Example 1 (0.032 × 2.5 = 0.080) — same fraction of range.
```

### The complete picture — every test scenario

The table below is the output of the `RatingChangeReport` test (`src/test/kotlin/.../impl/v1/RatingChangeReport.kt`), which runs all shared scenarios from `TestScenarios.kt` through the calculator in **both** rating systems. It is the big picture of what the current algorithm actually does across rating levels, gaps, scores, and smoothing settings.

**Constants in effect** (from `PerformanceBasedRankingCalculatorImpl`):

```
K_NTRP                    = 0.16
K_UTR                     = 0.4    (= 0.16 × 15.0/6.0 = 2.5 × K_NTRP)
COMPETITIVE_THRESHOLD_PCT = 0.083  (8.3% of range = 0.5 NTRP / 1.25 UTR points)
Upset multiplier          = 2.0
Rating ranges             = NTRP 1.0–7.0 (6.0), UTR 1.0–16.0 (15.0)
Smoothing                 = off unless the scenario says otherwise (SM rows)
```

How to read the table: P1 is always the match winner. **Δ (new)** is P1's rating change and resulting rating; the loser's change is the exact negative (zero-sum — no scenario hits a boundary). **⚡** marks a published-level change in that system. **UTR/NTRP** is the delta ratio, expected to be 2.5 everywhere; "—" means both deltas are zero (fully expected result).

| ID | Scenario | NTRP P1 vs P2 | UTR P1 vs P2 | Score | NTRP Δ (new) | UTR Δ (new) | UTR/NTRP |
|---|---|---|---|---|---|---|---|
| S1 | Low: Equal players, dominant | 2.5 vs 2.5 | 5.0 vs 5.0 | 6-0 | +0.160000 (2.660000) ⚡ | +0.400000 (5.400000) ⚡ | 2.50 |
| S2 | Low: Equal players, close | 2.5 vs 2.5 | 5.0 vs 5.0 | 6-4 | +0.032000 (2.532000) ⚡ | +0.080000 (5.080000) ⚡ | 2.50 |
| S3 | Low: Below threshold, 0.1 gap | 2.6 vs 2.5 | 5.25 vs 5.0 | 6-0 | +0.127871 (2.727871) ⚡ | +0.319677 (5.569677) ⚡ | 2.50 |
| S4 | Low: 0.5 gap, expected win | 3.0 vs 2.5 | 6.25 vs 5.0 | 6-0 | +0.000000 (3.000000) | +0.000000 (6.250000) | — |
| S5 | Low: 0.5 gap, expected win | 3.0 vs 2.5 | 6.25 vs 5.0 | 6-4 | +0.000000 (3.000000) | +0.000000 (6.250000) | — |
| S6 | Low: Below threshold, 0.1 gap | 3.1 vs 3.0 | 6.5 vs 6.25 | 6-0 | +0.127871 (3.227871) ⚡ | +0.319677 (6.819677) ⚡ | 2.50 |
| S7 | Low: 1.0 gap, expected win | 3.5 vs 2.5 | 7.5 vs 5.0 | 6-0 | +0.000000 (3.500000) | +0.000000 (7.500000) | — |
| S8 | Low: 1.0 gap, upset | 2.5 vs 3.5 | 5.0 vs 7.5 | 6-0 | +0.642572 (3.142572) ⚡ | +1.606429 (6.606429) ⚡ | 2.50 |
| S9 | Low: Below threshold, 0.1 gap | 3.6 vs 3.5 | 7.75 vs 7.5 | 6-0 | +0.127871 (3.727871) ⚡ | +0.319677 (8.069677) ⚡ | 2.50 |
| S10 | Low: 1.5 gap, expected win | 4.0 vs 2.5 | 8.75 vs 5.0 | 6-0 | +0.000000 (4.000000) | +0.000000 (8.750000) | — |
| S11 | Low: Below threshold, 0.1 gap | 4.1 vs 4.0 | 9.0 vs 8.75 | 6-0 | +0.127871 (4.227871) ⚡ | +0.319677 (9.319677) | 2.50 |
| S12 | Mid: 2.0 gap, big upset | 2.5 vs 4.5 | 5.0 vs 10.0 | 6-0 | +1.285139 (3.785139) ⚡ | +3.212848 (8.212848) ⚡ | 2.50 |
| S13 | Mid: Expected win, dominant | 4.5 vs 4.0 | 10.0 vs 8.75 | 6-0 | +0.000000 (4.500000) | +0.000000 (10.000000) | — |
| S14 | Mid: Expected win, close | 4.5 vs 4.0 | 10.0 vs 8.75 | 6-4 | +0.000000 (4.500000) | +0.000000 (10.000000) | — |
| S15 | Mid: Upset, dominant | 4.0 vs 4.5 | 8.75 vs 10.0 | 6-0 | +0.321284 (4.321284) ⚡ | +0.803210 (9.553210) ⚡ | 2.50 |
| S16 | Mid: Upset, close | 4.0 vs 4.5 | 8.75 vs 10.0 | 6-4 | +0.064257 (4.064257) ⚡ | +0.160642 (8.910642) ⚡ | 2.50 |
| S17 | Mid: Competitive, dominant | 4.5 vs 4.3 | 10.0 vs 9.5 | 6-0 | +0.095744 (4.595744) | +0.239359 (10.239359) | 2.50 |
| S18 | Mid: Competitive, close | 4.5 vs 4.3 | 10.0 vs 9.5 | 6-4 | +0.019149 (4.519149) | +0.047872 (10.047872) | 2.50 |
| S19 | Mid: Equal players, dominant | 4.5 vs 4.5 | 10.0 vs 10.0 | 6-0 | +0.160000 (4.660000) ⚡ | +0.400000 (10.400000) ⚡ | 2.50 |
| S20 | Mid: Equal players, close | 4.5 vs 4.5 | 10.0 vs 10.0 | 6-4 | +0.032000 (4.532000) ⚡ | +0.080000 (10.080000) ⚡ | 2.50 |
| S21 | Mid: Below threshold, 0.1 gap | 4.6 vs 4.5 | 10.25 vs 10.0 | 6-0 | +0.127871 (4.727871) ⚡ | +0.319677 (10.569677) ⚡ | 2.50 |
| S22 | High: Large gap, expected win | 5.0 vs 4.0 | 12.5 vs 10.0 | 6-0 | +0.000000 (5.000000) | +0.000000 (12.500000) | — |
| S23 | High: Large gap, big upset | 4.0 vs 5.0 | 10.0 vs 12.5 | 6-0 | +0.642572 (4.642572) ⚡ | +1.606429 (11.606429) ⚡ | 2.50 |
| S24 | High: Below threshold, 0.1 gap | 5.1 vs 5.0 | 12.75 vs 12.5 | 6-0 | +0.127871 (5.227871) ⚡ | +0.319677 (13.069677) ⚡ | 2.50 |
| SM1 | Smoothing 0.3: Equal, dominant | 4.0 vs 4.0 | 10.0 vs 10.0 | 6-0 | +0.048000 (4.048000) ⚡ | +0.120000 (10.120000) ⚡ | 2.50 |
| SM2 | Smoothing 0.5: Equal, dominant | 4.0 vs 4.0 | 10.0 vs 10.0 | 6-0 | +0.080000 (4.080000) ⚡ | +0.200000 (10.200000) ⚡ | 2.50 |
| SM3 | Smoothing 0.7: Equal, dominant | 4.0 vs 4.0 | 10.0 vs 10.0 | 6-0 | +0.112000 (4.112000) ⚡ | +0.280000 (10.280000) ⚡ | 2.50 |
| SM4 | Smoothing 0.3: Equal, close | 4.0 vs 4.0 | 10.0 vs 10.0 | 6-4 | +0.009600 (4.009600) ⚡ | +0.024000 (10.024000) ⚡ | 2.50 |
| SM5 | Smoothing 0.5: Equal, close | 4.0 vs 4.0 | 10.0 vs 10.0 | 6-4 | +0.016000 (4.016000) ⚡ | +0.040000 (10.040000) ⚡ | 2.50 |
| SM6 | Smoothing 0.5: Upset | 4.0 vs 4.5 | 10.0 vs 11.25 | 6-0 | +0.160642 (4.160642) ⚡ | +0.401605 (10.401605) ⚡ | 2.50 |
| SM7 | Smoothing 0.5: Expected win at threshold | 4.5 vs 4.0 | 10.0 vs 8.75 | 6-0 | +0.000000 (4.500000) | +0.000000 (10.000000) | — |
| SM8 | Smoothing 0.5: Competitive gap, dominant | 4.5 vs 4.3 | 10.0 vs 9.5 | 6-0 | +0.047872 (4.547872) | +0.119680 (10.119680) | 2.50 |
| SM9 | Smoothing 0.3: Competitive gap, close | 4.5 vs 4.3 | 10.0 vs 9.5 | 6-4 | +0.005745 (4.505745) | +0.014362 (10.014362) | 2.50 |
| SM10 | Smoothing 0.3: Small upset | 4.0 vs 4.5 | 8.75 vs 10.0 | 6-4 | +0.019277 (4.019277) ⚡ | +0.048193 (8.798193) ⚡ | 2.50 |
| SM11 | Smoothing 0.7: Medium upset | 4.0 vs 4.5 | 8.75 vs 10.0 | 6-0 | +0.224899 (4.224899) ⚡ | +0.562247 (9.312247) ⚡ | 2.50 |
| SM12 | Smoothing 0.5: Large upset (1.0 gap) | 2.5 vs 3.5 | 5.0 vs 7.5 | 6-0 | +0.321286 (2.821286) ⚡ | +0.803214 (5.803214) ⚡ | 2.50 |
| SM13 | Smoothing 0.3: Huge upset (2.0 gap) | 2.5 vs 4.5 | 5.0 vs 10.0 | 6-0 | +0.385542 (2.885542) ⚡ | +0.963854 (5.963854) ⚡ | 2.50 |
| SM14 | Smoothing 0.5: Small gap (0.1), dominant | 4.1 vs 4.0 | 9.0 vs 8.75 | 6-0 | +0.063935 (4.163935) ⚡ | +0.159839 (9.159839) | 2.50 |
| SM15 | Smoothing 0.7: Small gap (0.1), close | 4.6 vs 4.5 | 10.25 vs 10.0 | 6-4 | +0.017902 (4.617902) ⚡ | +0.044755 (10.294755) ⚡ | 2.50 |
| SM16 | Smoothing 0.5: High rating upset | 4.0 vs 5.0 | 10.0 vs 12.5 | 6-0 | +0.321286 (4.321286) ⚡ | +0.803214 (10.803214) ⚡ | 2.50 |
| SM17 | Smoothing 0.3: Low rating levels | 2.6 vs 2.5 | 5.25 vs 5.0 | 6-0 | +0.038361 (2.638361) ⚡ | +0.095903 (5.345903) ⚡ | 2.50 |
| SM18 | Smoothing 0.7: High rating levels | 5.1 vs 5.0 | 12.75 vs 12.5 | 6-0 | +0.089510 (5.189510) ⚡ | +0.223774 (12.973774) | 2.50 |

Patterns the table makes visible at a glance:

- **Expected wins are free** — every favorite-wins-at-or-beyond-threshold row (S4, S5, S7, S10, S13, S14, S22, SM7) is exactly zero, regardless of score.
- **Upsets dominate the magnitude scale** — the largest single change (S12: +1.285 NTRP for a 2.0-gap shutout upset) is 8× the largest equal-players change (±0.160).
- **The 2.5× ratio holds in every non-zero row**, confirming the K-factor derivation in [§3.3](#33-the-k-factors-016-and-04).
- **Smoothing scales rows proportionally** — SM2 (factor 0.5) is exactly half of S19's unsmoothed +0.160.

To regenerate after changing the algorithm or scenarios:

```bash
./gradlew test --tests "*.RatingChangeReport"
# full fixed-width report (incl. published levels) written to /tmp/rating_change_report.txt
```

---

## 7. Edge Cases and Known Limitations

### Edge cases (handled by design)

- **Shutouts** — `dominance = (6−0)/(6+0) = 1.0` with no division-by-zero; the normalized definition needs no special case.
- **Near-equal ratings** — gaps of e.g. 0.005 produce `normalizedGap ≈ 0`, `scale ≈ 1.0`: effectively treated as equals, continuously (there is no explicit equality branch).
- **Player at a boundary** — clamping absorbs the change for that player only ([§5.2](#52-boundary-clamping)).
- **Tiebreak sets** — a 7-6 set contributes 7 vs 6 games like any other; the tiebreak's internal points (7-5, 10-8) are not used.

### Known limitations

1. **No historical context** — every match is independent; no trends, streaks, or rating history.
2. **No time decay** — a six-month-old match weighs the same as yesterday's.
3. **No score validation in the calculator** — it trusts the model layer (`MatchScore`/`SetScore` validation) to reject illegal scores like 8-0.
4. **Tiebreak points ignored** — only game counts matter.
5. **Set structure ignored** — 6-0, 0-6, 6-0 is identical to a 6-3 set (same game totals); no weighting for deciding sets.
6. **Fixed upset multiplier** — 2.0 regardless of gap size or score; the gap's magnitude enters only linearly through scale.

Possible future refinements (historical weighting, time decay, set-depth weighting, surface adjustments, graduated upset multiplier) are intentionally out of scope for v1.

---

## 8. Implementation Map

| What | Where |
|---|---|
| Algorithm (all of §1–§3, §5) | `src/main/kotlin/org/skopeo/service/calculator/impl/v1/PerformanceBasedRankingCalculatorImpl.kt` |
| Dominance factor | `calculateDominanceFactor()` in `src/main/kotlin/org/skopeo/model/MatchScore.kt` |
| BigDecimal precision helpers | `src/main/kotlin/org/skopeo/service/calculator/impl/BigDecimalUtils.kt` |
| Audit trail (every step logged) | `src/main/kotlin/org/skopeo/service/calculator/AuditTrail.kt` — see [AUDIT_TRAIL.md](AUDIT_TRAIL.md) |

Key methods in the calculator:

1. `calculate(request)` — entry point; orchestrates, builds audit trail and response.
2. `calculateRatingAdjustments(...)` — the master formula and two-path scale selection.
3. `applyRatingChange(...)` → `applyNTRPChange` / `applyUTRChange` — smoothing and boundary clamping.

Test coverage: `PerformanceBasedRankingCalculatorImplTest` (24 NTRP + 24 UTR scenarios), `RatingChangeReport` (generates the [§6 rating-change table](#the-complete-picture--every-test-scenario) and verifies the 2.5× K ratio), `RankingCalculationPayloadTest` (exact values), `RankingCalculationApiErrorTest` (boundaries). The calculator is a pure function returning result + audit trail, so all of this is tested without mocks.

---

## 9. Glossary

#### Elo rating system
The classical chess rating method ([Wikipedia](https://en.wikipedia.org/wiki/Elo_rating_system)): after each game, points transfer from loser to winner, with the amount based on how expected the result was. Skopeo extends Elo with margin-of-victory awareness (the dominance factor).

#### K-factor
The Elo constant that sets rating **volatility** — how many points a single match can move. In Skopeo it equals the maximum non-upset change (reached at full dominance between equals). NTRP uses K = 0.16 (the calibration anchor); UTR uses K = 0.4, *derived* from NTRP by the range ratio 15.0/6.0 = 2.5 so both systems move the same fraction of their scale per match. See [§2.1](#21-k--the-step-size), [§3.3](#33-the-k-factors-016-and-04).

#### Rating gap
The absolute difference between the two players' ratings before the match, `|rating₁ − rating₂|`. The gap drives the scale factor: small gaps mean competitive matches (results carry information), large gaps mean predictable matches (a favorite's win carries none). Also called *rating differential*.

#### Rating range
Ceiling minus floor of a rating scale: **6.0** for NTRP (7.0 − 1.0) and **15.0** for UTR (16.0 − 1.0). Used to normalize gaps so the two systems are comparable. See [§3.1](#31-the-rating-ranges-60-and-150).

#### Normalized gap
The rating gap expressed as a fraction of the rating range: `normalizedGap = gap / range`. This is what makes a 0.5 NTRP gap and a 1.25 UTR gap equivalent (both 8.3%). See [§3.1](#31-the-rating-ranges-60-and-150).

#### Competitive threshold
The normalized gap (8.3% of range ≈ 1/12, i.e. 0.5 NTRP or 1.25 UTR points) beyond which a favorite's win is treated as fully expected and produces zero change. Anchored to one NTRP **half-level** — the smallest published skill increment. It is the counterpart concept to the rating gap: the gap measures how far apart two players are; the threshold defines how far apart they can be while their match still counts as competitive. See [§3.2](#32-the-competitive-threshold-83--05-ntrp--125-utr).

#### Dominance factor
Match closeness measured from game totals: `(gamesWon − gamesLost) / (gamesWon + gamesLost)` — the [efficiency formula](#efficiency-formula) applied to games. Ranges 0 (perfectly even) to 1.0 (shutout) for the winner. See [§2.2](#22-dominance--how-convincingly) and the [tables in §4](#4-dominance-factor-tables).

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
Forcing a new rating back inside its system's floor/ceiling (1.0–7.0 NTRP, 1.0–16.0 UTR). The final pipeline step and the only one that may break zero-sum.

#### Published level
The discrete, public-facing rating bucket (NTRP in 0.5 steps, UTR in 1.0 steps) derived from the continuous internal rating. The API reports `levelChanged` when a rating change crosses a bucket boundary.

---

## References

- **Elo Rating System**: [Wikipedia](https://en.wikipedia.org/wiki/Elo_rating_system)
- **NTRP**: USTA National Tennis Rating Program (1.0–7.0 scale, 0.5-step published levels)
- **UTR**: Universal Tennis Rating (1.0–16.0 scale as implemented)
- **Rating smoothing**: [RATING_SMOOTHING.md](RATING_SMOOTHING.md)
- **Audit trail design**: [AUDIT_TRAIL.md](AUDIT_TRAIL.md)

---

**Document Version**: 3.0 (top-down restructure, renamed from ALGORITHM_BEHAVIOR.md)
**Last Updated**: 2026-06-10
**Algorithm Version**: Performance-Based Elo v2.0 (Normalized Gap Implementation)
