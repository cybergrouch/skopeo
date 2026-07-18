# Rating Confidence — Recency × Sparsity × Spacing (3-factor)

**Status:** Implemented (#459) · **Tracking issue:** [#459](https://github.com/cybergrouch/skopeo/issues/459) · Supersedes the days-since-last-match confidence (#343) and the sparsity-only interim (#461), in `model/RatingConfidence.kt`.

## 1. Motivation
The original confidence formula keyed off **days since the last match**, which over-rewards a recent burst of play. The failure case: a player plays **5 matches at the start of a month and 1 at the end** — "days since last match" is small, so confidence reads high, even though play was sparse/clustered. We want confidence to **reward consistent, fresh, evenly-spread activity** — not just recency, and not just raw volume.

The interim revision (#461) replaced days-since-last with a **sparsity-only** density term (`window / weightedCount`). That rewards volume + match quality, but is blind to *when* the matches fell: it scores "6 matches spread evenly" and "6 matches all clustered on day 1" identically, and it can't tell a fresh record from a stale one. This document specifies the **3-factor** model that closes those gaps.

## 2. The model — three log-logistic factors, multiplied
All three factors share the same **log-logistic decay** (1.0 at x=0, ~0.5 at the 35-day midpoint, falling toward 0 beyond):

```
f(x) = 1 / (1 + (x / 35)^2.5)      // TARGET_MIDPOINT_GAP = 35, DECAY_SHAPE = 2.5
```

Over a fixed **30-day window** (`WINDOW_DAYS = 30`), for a player's COMPLETED matches:

```
weightedCount = 3.0·tournaments + 1.5·leagues + 0.5·openPlays   // matches in the last 30 days
if (no matches in window) confidence = 0                        // no qualifying play → 0%

recency  = f(daysSinceLastMatch)   // daysSinceLastMatch = DAYS.between(latest in-window match, now), ≥ 0
sparsity = f(30 / weightedCount)   // weighted density: denser / higher-stakes play → smaller gap
spacing  = f(maxInternalGap)       // largest gap BETWEEN consecutive match dates; 1.0 with ≤ 1 match

confidence = recency × sparsity × spacing     // result in [0, 1], BigDecimal scale 6, computed on read
```

- **Recency** — freshness of the *most recent* match in the window. A match today gives `f(0) = 1.0`; it decays as the newest match ages.
- **Sparsity** — weighted **density** (volume + match quality). Higher-stakes matches close the gap faster: a tournament (`W_t = 3.0`) counts 6× an open-play match (`W_o = 0.5`).
- **Spacing** — the biggest **internal** hole: the largest gap in days between *consecutive* match dates. A burst-then-gap month has one big hole → low spacing. With **≤ 1 match there is no internal gap → spacing = 1.0** (recency + sparsity carry the score).

### 2.1 Why Spacing uses *internal* gaps only (no double-count with Recency)
Spacing deliberately measures only the gaps **between** matches — never the trailing gap from the last match to `now`. **Recency already covers the trailing gap.** If Spacing also included the trailing gap, a stale record would be penalized twice for the same silence. Keeping Spacing internal-only makes the three factors capture distinct signals: *how fresh* (Recency), *how much/how good* (Sparsity), *how evenly* (Spacing).

## 3. Match-type weights
Higher-stakes matches tell us more about true skill, so each class is weighted before summing (`MatchType.weightClass()` in `model/MatchDomain.kt` folds playoffs into their parent class):

| Match type | Weight |
|---|---|
| Tournament (`W_t`) | **3.0** — highly competitive; maximum signal |
| League (`W_l`) | **1.5** — structured/competitive |
| Open play (`W_o`) | **0.5** — casual/practice; high volume, lower reliability |

## 4. Tunables
`WINDOW_DAYS` (30), `TARGET_MIDPOINT_GAP` (35), `DECAY_SHAPE` (2.5), and the per-type weights (3.0 / 1.5 / 0.5) — all centralized as named consts in `model/RatingConfidence.kt`.

## 5. Worked examples (30-day window; eval on day 30 unless noted; `f` = the log-logistic above)

| Player | Matches (day-of-window) | Recency | Sparsity | Spacing | Confidence |
|---|---|---|---|---|---|
| **A — the "gulf" scenario** | 2 open, day 1 & day 30 | f(0)=1.0 | f(30/1.0=30)=0.595169 | f(29)=0.615353 | **≈ 0.366289** |
| **6 even** | open on days 5,10,15,20,25,30 | f(0)=1.0 | f(30/3.0=10)=0.958179 | f(5)=0.992345 | **≈ 0.950848** |
| **6 clustered, ends today** | open on days 1,2,3,4,5,30 | f(0)=1.0 | f(10)=0.958179 | f(25)=0.698708 | **≈ 0.669485** |
| **5 clustered, then quiet** | open on days 1–5 only | f(25)=0.698708 | f(30/2.5=12)=0.935703 | f(1)=~0.9999 | **≈ 0.653732** |
| **0 matches in window** | — | — | — | — | **0.000000** |
| **1 match** | any single match | f(daysSince) | f(30/wc) | **1.0** | Recency × Sparsity |

Weight ordering: for equal counts, spacing, and recency, **tournament > league > open play** (via the weighted count feeding Sparsity).

## 6. Why 3-factor (vs 2-factor)

Projected comparison of a 2-factor model (Recency × Sparsity) against the shipped 3-factor (× Spacing). All values use `f(x) = 1/(1+(x/35)^2.5)`, 30-day window; Recency = f(daysSinceLast), Sparsity = f(30/weightedCount), Spacing = f(maxInternalGap).

| Scenario | daysSince → Recency | wc → Sparsity | maxInternalGap → Spacing | 2-factor (R×S) | 3-factor (R×S×Sp) |
|---|---|---|---|---|---|
| A — 2 open, day 1 & day 30 (eval d30) | 0 → 100% | 1.0 → 59.5% | 29 → 61.5% | 59.5% | **36.6%** |
| B — 2 tournaments early, 21d idle | 21 → 78.2% | 6.0 → 99.2% | 2 → ~100% | 77.6% | 77.5% |
| C — 1T+2L+4O even, last 3d ago | 3 → 99.8% | 8.0 → 99.6% | ~4.3 → 99.5% | 99.4% | 98.9% |
| 6 even (last on day 30) | 0 → 100% | 3.0 → 95.8% | 5 → 99.2% | 95.8% | 95.0% |
| 6 clustered, ends today (5 in wk1 + 1 on d30) | 0 → 100% | 3.0 → 95.8% | 25 → 69.9% | 95.8% | **67.0%** |
| 5 clustered, then quiet (burst wk1, idle since) | 25 → 69.9% | 2.5 → 93.6% | 1 → ~100% | 65.4% | 65.4% |

- **2-factor (Recency × Sparsity)** catches recency + volume/quality and the "burst-then-quiet" pattern (via Recency), but has two gaps: (1) it can't see internal clustering when the last match is fresh — "6 clustered, ends today" reads the same 95.8% as evenly-spread; (2) it rates a thin record too generously — Player A (2 matches spanning the whole month) at 59.5%.
- **3-factor (× Spacing)** closes both: the internal-hole term drops "6 clustered, ends today" to 67.0%, and Player A to 36.6%.
- **Decision:** the 3-factor's lower Player-A score (36.6%) is the *correct* confidence for a thin, erratic record — 59.5% was too generous for someone with only 2 matches. Note the "double-dip" for sparse players (both Sparsity and Spacing fire) is intentional/desirable there, and it does NOT wrongly fire when spacing is fine (B, C, "then quiet" barely move). Hence we settled on 3-factor.

## 7. Data plumbing
`confidenceAt(matches, now)` takes the player's windowed match **rows** (each a `(matchDate, weightClass)` `WindowMatch`) rather than per-type counts — the dates drive Recency (latest date) and Spacing (inter-match gaps), while the weight classes drive Sparsity. `repository/MatchRepository.kt` supplies them via `windowedMatchesInWindow`:
- a **single-player** form (`userId, asOf`), and
- a **batched** form (`userIds, asOf`) grouped by user — **one query** for a whole page of N players, no N+1.

Standings, seeding, match/history, and profile reads all go through the batched form (`RatingRepository`). Confidence is computed on read and never stored, so there is **no migration**. The vestigial `matchRatedAt` / `matchesSinceReset` columns remain (band-hop bookkeeping) but no longer affect confidence.

## 8. Touch points
`model/RatingConfidence.kt` (the 3-factor formula + `WindowMatch` inputs); `model/MatchDomain.kt` (`WindowMatch`, `WeightClass`, `MatchType.weightClass()`); `repository/MatchRepository.kt` (`windowedMatchesInWindow`, single + batched); `repository/RatingRepository.kt` (read plumbing → `toUserRating`). Downstream consumers (`RatingService`, `RatingCalculationService`, `StandingsService`, `SeedingService`, `PlayerService`, `MatchService`) read `UserRating.confidence` transparently. Relates to #343 (confidence), #403 (event types), and the match-type classification.
