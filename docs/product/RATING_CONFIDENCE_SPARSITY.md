# Rating Confidence — Sparsity & Match-Weight Revision

**Status:** Proposed · **Tracking issue:** [#459](https://github.com/cybergrouch/skopeo/issues/459) · Supersedes the days-since-last-match confidence in `model/RatingConfidence.kt` (#343).

## 1. Motivation
The current confidence formula keys off **days since the last match**, which over-rewards a recent burst of play. The failure case: a player plays **5 matches at the start of a month and 1 at the end** — "days since last match" is small, so confidence reads high, even though play was sparse/clustered. We want confidence to **reward consistent activity over time** (density of meaningful matches within a window), not just recency.

## 2. Current formula (being revised)
`model/RatingConfidence.kt` — `confidenceAt(matchRatedAt, matchesSinceReset, now)`:

```
decay = 1 / (1 + (daysSinceLastMatch / 35)^2.5)
scale = min(1, matchesSinceReset / 5)   // ramp over first ~5 matches after a reset/band-jump
confidence = decay * scale              // computed on read, never stored; 0 when not match-derived
```

## 3. Proposed approach — Sparsity Index + match-type weighting

### 3.1 Strategy: "average days between matches"
Instead of *days since the last match*, measure the **average gap between matches** within a fixed evaluation window (e.g. 30 days). Lower gap (denser, more meaningful play) → higher confidence.

### 3.2 Weighted match count
Higher-stakes matches tell us more about true skill, so weight by type before summing:

| Match type | Weight |
|---|---|
| Tournament (`W_t`) | **3.0** — highly competitive; maximum signal |
| League (`W_l`) | **1.5** — structured/competitive |
| Open play (`W_o`) | **0.5** — casual/practice; high volume, lower reliability |

```
weightedCount = 3.0·tournaments + 1.5·leagues + 0.5·openPlays
```

### 3.3 Sparsity (average gap)
Over a fixed window (e.g. 30 days):

```
averageGap = windowDays / weightedCount
```
2 matches in 30 days → gap 15; 10 matches → gap 3. A tournament match "closes the gap" faster because it carries more weight.

### 3.4 Unified log-logistic (reuses the existing shape/midpoint)
```
confidence = 1 / (1 + (averageGap / 35)^2.5)
```

### 3.5 Kotlin sketch
```kotlin
import kotlin.math.pow

/**
 * Rating confidence from match sparsity over a fixed window.
 * @param windowDays evaluation slice (e.g. 30.0 for a month).
 */
fun calculateSparsityConfidence(
    windowDays: Double = 30.0,
    tournamentCount: Int,
    leagueCount: Int,
    openPlayCount: Int,
): Double {
    val weightedCount = tournamentCount * 3.0 + leagueCount * 1.5 + openPlayCount * 0.5
    if (weightedCount <= 0.0) return 0.0          // no play in the window → 0% confidence
    val averageGap = windowDays / weightedCount
    val targetMidpointGap = 35.0
    val exponent = 2.5
    return 1.0 / (1.0 + (averageGap / targetMidpointGap).pow(exponent))
}
```

## 4. Tunables
`windowDays` (30), `targetMidpointGap` (35), `exponent` (2.5), and the per-type weights (3.0 / 1.5 / 0.5) — all should be centralized and configurable.

## 5. Worked examples (30-day window)

| Player | Matches | Weighted count | Average gap | Confidence |
|---|---|---|---|---|
| **A — the "gulf" scenario** | 2 open play (Day 1 & Day 30) | 2 × 0.5 = **1.0** | 30 / 1.0 = **30 d** | **≈ 59.5%** |
| **B — consistent casual** | 8 open play, spread evenly | 8 × 0.5 = **4.0** | 30 / 4.0 = **7.5 d** | **≈ 97.9%** |
| **C — efficient tournament player** | 2 tournament | 2 × 3.0 = **6.0** | 30 / 6.0 = **5.0 d** | **≈ 99.2%** |

- **A** — the huge gap (low volume) drops them close to the 50% cliff. *(This value is ≈59.5% for `(30/35)^2.5 ≈ 0.680`; an earlier draft quoted 56.4%, corrected here to match the formula/constants.)*
- **B** — high consistency, low sparsity → near-full confidence.
- **C** — tournament matches carry so much weight that just two prove current form reliably, yielding a very tight effective gap.

## 6. Design nuance — density vs. true clustering
`averageGap = windowDays / weightedCount` measures **density (matches per window)**, not the *actual* spacing between matches. It scores "6 matches spread evenly" and "6 matches all clustered on one day" **identically**. This is a real improvement over days-since-last (it rewards volume + match quality and no longer over-rewards recency), but it does **not** by itself distinguish a clustered 5-early-1-late month from an evenly-spread one of the same weighted count.

If we specifically want to penalize **clustering**, compute the **real inter-match intervals** (mean/max gap or variance over the window) instead of `window / count`. Two candidate models:
- **(A) Density-based** — the proposal above; simple, rewards activity + match quality.
- **(B) True-spacing-based** — actual gaps between match timestamps; more faithful to the stated scenario, more data/compute.

*(Note: in the worked examples, A scores low mainly because of its low weighted count — not the clustering per se; under model A, two players with the same weighted count but different spacing get the same score.)*

## 7. Open decisions
- **Density (A) vs true-spacing (B)** (see §6).
- **Fate of the reset/ramp** (`scale = min(1, matchesSinceReset/5)`) and band-jump reset semantics — keep as a separate multiplier, fold in, or drop.
- **Evaluation window** — fixed 30 days, rolling, or multiple windows; handling players active only >30 days ago (→ weightedCount 0 → 0%).
- **Classification → weights** — map `MatchType` (`OPEN_PLAY`/`LEAGUE_PLAY`/`TOURNAMENT_*`) and/or event `type` (`OPEN_PLAY`/`LEAGUE`/`TOURNAMENT`) to `W_o/W_l/W_t`.
- **Data plumbing / perf** — `confidenceAt` currently takes only `matchRatedAt` + `matchesSinceReset`; the new formula needs **windowed match counts by type per player** from match history (`MatchRepository`). Confidence is computed on read (standings/seeding lists), so batch or precompute to avoid an N+1.
- **Back-compat** — confidence isn't stored (computed on read), so no migration; but the inputs and every caller signature change.

## 8. Touch points
`model/RatingConfidence.kt` (formula + inputs); callers `RatingService`, `RatingCalculationService`, `StandingsService`, `SeedingService`, `PlayerService`, `MatchService`; `repository/MatchRepository.kt` (windowed counts by type); `model/MatchDomain.kt` / `model/EventDomain.kt` (type→weight). Relates to #343 (confidence), #403 (event types), and match-type classification.
