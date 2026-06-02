# Performance-Based Elo Rating Algorithm

## Overview

Tennis Levelr uses a **performance-based Elo rating system** to calculate rating changes after matches. Unlike standard Elo, which only considers win/loss, this algorithm accounts for **how dominantly** a player won and whether they **exceeded or fell short of expectations**.

## Core Principles

### 1. Expected Performance
Players are expected to win by a margin proportional to their rating advantage:
- **Formula**: `expectedMargin = ratingDiff × 6 games per rating point`
- **Example**: A 1.0 rating advantage expects a 6-game margin (e.g., 6-4, 6-2)

### 2. Dominance Factor
The **ratio of games won to games lost** amplifies or diminishes rating changes:
- **Calculation**: `dominance = totalGamesWinner / totalGamesLoser`
- **Capped at 2.5** to prevent excessive swings
- **Default 2.0** when loser wins 0 games (complete shutout)

### 3. Performance-Based Adjustments
Rating changes depend on five distinct cases based on match outcome relative to expectations.

---

## The Five Adjustment Cases

### Case 0: Equal Ratings (diff < 0.01)
**When**: Rating difference is negligible (< 0.01 points)

**Logic**: Treat as equal opponents, apply standard Elo with dominance factor

**Formula**:
```
change = K × (actualScore - 0.5) × dominanceFactor
```

**Example**:
- P1 (10.000 UTR) vs P2 (10.005 UTR)
- P1 wins 6-4 (dominance = 1.5)
- P1 gains `0.4 × 0.5 × 1.5 = 0.3` rating points

**Rationale**: Below 0.01, rating differences produce negligible expected score differences (~0.500 vs ~0.501).

---

### Case 1: Upset (underdog wins)
**When**: Lower-rated player wins

**Logic**: Cap changes at ±(ratingDiff / 3) **regardless of score margin**

**Formula**:
```
cap = ratingDiff / 3
change = ±cap (winner gains cap, loser loses cap)
```

**Example**:
- P1 (3.0 NTRP) vs P2 (6.0 NTRP)
- P1 wins 6-0, 6-0 (huge upset)
- Rating change capped at ±1.0 (6.0 / 3)
- **Note**: Same cap whether P1 wins 6-0 or 7-6

**Rationale**: Upsets indicate rating inaccuracy, but a single match shouldn't close large gaps immediately. Requires ~3 dominant wins to close a rating gap.

---

### Case 2: Met Expectations (margin ≈ expected)
**When**: Actual margin within 0.1 games of expected margin

**Logic**: **Zero rating change** for both players

**Formula**:
```
if |actualMargin - expectedMargin| < 0.1:
    change = 0.0
```

**Example**:
- P1 (4.5 NTRP) vs P2 (4.0 NTRP), ratingDiff = 0.5
- Expected margin = 0.5 × 6 = 3.0 games
- P1 wins 6-3 (actual margin = 3.0 games)
- Both players' ratings unchanged

**Rationale**: When results match expectations, ratings are already accurate. Narrow tolerance (0.1 games) ensures this case is rare.

---

### Case 3: Underperformed (margin < expected)
**When**: Winner won by a smaller margin than expected

**Logic**: **Invert** rating changes with a multiplier adjusted for match length

**Formula**:
```
multiplier = 0.5 - (0.0166642 × (totalGames - 10))
invertedChange = -baseChange × multiplier
```

**Breakdown**:
- **Base multiplier**: 0.5 (50% of standard Elo change, inverted)
- **Adjustment**: -0.0166642 per game over 10
- **Reference**: 10 games = standard 6-4 set

**Multiplier Examples**:
| Match Length | Total Games | Multiplier | Effect |
|--------------|-------------|------------|--------|
| 6-0 shutout | 6 games | 0.567 | Less penalty (short match) |
| 6-4 standard | 10 games | 0.500 | Base penalty |
| 3-set close | 18 games | 0.367 | More penalty (long match) |

**Example**:
- P1 (4.5 NTRP) vs P2 (4.0 NTRP), ratingDiff = 0.5
- Expected margin = 3.0 games
- P1 wins 6-4 (actual margin = 2 games) → underperformance
- **P1 LOSES rating** despite winning (failed to meet expectations)
- **P2 GAINS rating** despite losing (performed better than expected)

**Rationale**: Longer matches indicate closer competition. If the favorite only barely wins a 3-set match, they underperformed more significantly than barely winning a short set.

**Magic Constant Explanation**:
- **0.0166642**: Empirically tuned to produce appropriate penalties across match lengths
- Derived to ensure multiplier stays in reasonable range (0.3-0.6) for typical matches

---

### Case 4: Overperformed (margin > expected)
**When**: Winner exceeded expectations

**Logic**: Cap changes based on **margin difference magnitude**

**Formula**:
```
baseCap = ratingDiff / 3

if marginDiff >= 2.0:
    cappedChange = baseCap                    # Full cap
else:
    cappedChange = baseCap × 0.798846        # Reduced cap (~80%)
```

**Example 1 - Very Dominant** (margin_diff >= 2.0):
- P1 (5.0 NTRP) vs P2 (4.0 NTRP), ratingDiff = 1.0
- Expected margin = 6.0 games
- P1 wins 6-0, 6-2 (actual margin = 8 games)
- Margin diff = 8 - 6 = 2.0 games
- Change capped at 1.0 / 3 = **0.333** (full cap)

**Example 2 - Moderate Overperformance** (margin_diff < 2.0):
- P1 (5.0 NTRP) vs P2 (4.8 NTRP), ratingDiff = 0.2
- Expected margin = 1.2 games
- P1 wins 6-3 (actual margin = 3.0 games)
- Margin diff = 3.0 - 1.2 = 1.8 games
- Change capped at (0.2 / 3) × 0.798846 = **0.053**

**Rationale**: Prevents single dominant matches from producing excessive rating jumps. Even very dominant wins require multiple matches to close large rating gaps.

**Magic Constant Explanation**:
- **0.798846**: Approximately 80% cap for moderate overperformance
- Distinguishes between "exceeded expectations" (1 game over) and "dominated" (2+ games over)

---

## Special Cases and Edge Cases

### Large Rating Gaps (Margin Capping)
**Issue**: When rating gap > ~2.0, expected margin exceeds maximum possible games

**Example**:
- P1 (6.0 NTRP) vs P2 (1.0 NTRP), ratingDiff = 5.0
- Uncapped expected margin = 5.0 × 6 = **30 games** (impossible in 6-0, 6-0)
- Capped to maxPossibleMargin = 12 games (winner's total)

**Effect**: When P1 wins 6-0, 6-0:
- actualMargin = 12 games
- expectedMargin = 12 games (capped)
- Difference < 0.1 → **Case 2 (zero change)**

**Implication**: Extremely mismatched games produce no rating changes (ratings already accurate).

### Dominance Factor Edge Cases

**Complete Shutout (0 games for loser)**:
- Can't divide by zero, so **defaults to 2.0** instead of undefined
- Treats 6-0, 6-0 similar to 6-3, 6-3 (both have 2.0 dominance)

**Near Cap (2.4 vs 2.6)**:
- 12 games vs 5 games = 2.4 dominance (not capped)
- 13 games vs 5 games = 2.6 → capped to 2.5
- Small game count changes create threshold effects

**Above Cap**:
- 18 games vs 6 games = 3.0 dominance → capped to 2.5
- Prevents extreme multipliers even in lopsided matches

### Tiebreak Handling
**Current Behavior**: All games count equally
- 6-4 set = 6 games contributed
- 7-6(5) tiebreak set = 7 games contributed

**No Special Weighting**: Tiebreak wins are not distinguished from standard wins in the algorithm.

**Example**:
- P1 wins 7-6(7-5), 6-3 = 13 games vs 9 games
- Dominance = 13/9 = 1.44 (moderate, not highly dominant)
- Close tiebreak + standard set = small rating change

---

## Zero-Sum Property and Boundary Enforcement

### Before Clamping: Zero-Sum
All rating changes calculated satisfy:
```
player1Change + player2Change = 0.0
```

### After Clamping: May Violate Zero-Sum
When a player reaches a rating boundary (NTRP: 1.0-7.0, UTR: 1.0+), their rating is clamped.

**Example**:
- P1 (7.0 NTRP, at max) vs P2 (6.5 NTRP)
- P1 wins, calculated change = +0.2
- After clamping: P1 = 7.0 (change = 0.0), P2 = 6.3 (change = -0.2)
- **Zero-sum violated**: 0.0 + (-0.2) ≠ 0.0

**Rationale**: Maintaining accurate ratings at boundaries is more important than strict zero-sum property.

---

## K-Factor Scaling

### K-Factor Values
- **NTRP**: K = 0.16
- **UTR**: K = 0.4 (2.5× larger)

### Proportional Scaling
K-factors are scaled proportionally to rating system ranges:
```
K_UTR = K_NTRP × (UTR_range / NTRP_range)
      = 0.16 × (15.0 / 6.0)
      = 0.4
```

**Implication**: UTR ratings change 2.5× faster than NTRP for the same match, reflecting UTR's larger scale.

**Rationale**: Standard Elo practice to maintain consistent volatility across systems.

---

## Precision and Rounding

### BigDecimal Precision
All calculations use BigDecimal with **6 decimal places** (CALCULATION_SCALE = 6).

**Precision Trade-off**:
- Expected score calculation uses `10^x` via Double conversion
- Loses BigDecimal precision advantage during power operation
- **Acceptable** because:
  - Double provides ~15-16 decimal digits
  - Rating differences rarely exceed ±5.0
  - Final results rounded to 6 decimals anyway

### Display Precision
- NTRP: Displayed with up to 6 decimals (e.g., "4.123456")
- UTR: Displayed with up to 6 decimals (e.g., "10.123456")
- Percent changes: Up to 6 decimals (e.g., "1.234567%")

---

## Known Limitations

### 1. No Historical Context
Each match is independent; algorithm doesn't consider:
- Previous match history
- Rating trends over time
- Recent performance streaks

### 2. No Time Decay
A match from 6 months ago has the same weight as yesterday's match.

### 3. No Input Validation
Algorithm assumes valid tennis scores. Invalid scores (e.g., 8-0, 5-3) may produce unexpected results if not caught by model validation.

### 4. Tiebreak Points Ignored
Only game counts matter; tiebreak point scores (7-5, 10-8) are not factored into calculations.

### 5. Set Context Ignored
- No weighting for set depth (1st set vs 5th set in Grand Slam)
- Split set wins (6-0, 3-6, 6-2) treated identically to straight set wins with same game total

---

## Examples and Test Cases

### Example 1: Equal Players
- **Players**: P1 (5.0 NTRP) vs P2 (5.0 NTRP)
- **Result**: P1 wins 7-5 (dominance = 1.4)
- **Case**: Case 0 (equal ratings)
- **Expected**: P1 gains ~0.11, P2 loses ~0.11

### Example 2: Upset Win
- **Players**: P1 (3.0 NTRP) vs P2 (6.0 NTRP)
- **Result**: P1 wins 6-4, 6-4 (underdog victory)
- **Case**: Case 1 (upset)
- **Expected**: Changes capped at ±1.0 (6.0 / 3)

### Example 3: Overperformance
- **Players**: P1 (5.0 NTRP) vs P2 (4.0 NTRP)
- **Result**: P1 wins 6-1, 6-0 (dominant)
- **Expected margin**: 6.0 games
- **Actual margin**: 11 games
- **Case**: Case 4 (overperformance, margin_diff = 5)
- **Expected**: Changes capped at ±0.333 (1.0 / 3, full cap)

### Example 4: Underperformance
- **Players**: P1 (4.5 NTRP) vs P2 (4.0 NTRP)
- **Result**: P1 wins 7-6, 3-6, 7-5 (close 3-setter)
- **Expected margin**: 3.0 games
- **Actual margin**: 2 games (19 vs 17)
- **Case**: Case 3 (underperformance)
- **Expected**: P1 **loses** rating, P2 **gains** rating (inverted)

### Example 5: Large Gap Zero Change
- **Players**: P1 (6.0 NTRP) vs P2 (1.0 NTRP)
- **Result**: P1 wins 6-0, 6-0
- **Expected margin**: 30 games → capped to 12
- **Actual margin**: 12 games
- **Case**: Case 2 (met expectations, diff < 0.1)
- **Expected**: Both ratings unchanged (zero change)

---

## Algorithm Constants Summary

| Constant | Value | Purpose | Rationale |
|----------|-------|---------|-----------|
| K_FACTOR_NTRP | 0.16 | Base volatility for NTRP | Standard Elo practice |
| K_FACTOR_UTR | 0.4 | Base volatility for UTR | Scaled by range ratio (2.5×) |
| SCALE_FACTOR | 400.0 | Expected score calculation | Standard Elo constant |
| Equal threshold | 0.01 | Treat ratings as equal | Negligible expected score diff |
| Max dominance | 2.5 | Cap dominance factor | Prevent excessive swings |
| Games per rating | 6.0 | Expected margin formula | Empirical tennis estimate |
| Baseline tolerance | 0.1 | Zero change threshold | Very narrow tolerance |
| Base total games | 10.0 | Underperform reference | Standard 6-4 set |
| Adjustment per game | 0.0166642 | Underperform multiplier | Tuned to test expectations |
| Overperform threshold | 2.0 | Full vs reduced cap | Distinguish moderate/dominant |
| Moderate factor | 0.798846 | Reduced overperform cap | ~80% of full cap |
| Default dominance | 2.0 | Shutout fallback | Avoid division by zero |

---

## Implementation Notes

### File Location
`../src/main/kotlin/org/lange/tennis/levelr/service/calculator/impl/v1/PerformanceBasedRankingCalculatorImpl.kt`

### Key Methods
- `calculate(request)`: Main entry point
- `analyzeMatch(matchScore, players)`: Determines winner, dominance, game totals
- `calculateRatingChanges(...)`: Applies five-case logic
- `coerceRatingToBounds(...)`: Enforces rating boundaries (clamping)

### Audit Trail
Every calculation produces a detailed audit trail including:
- Expected scores
- Match analysis (winner, dominance, games)
- Rating calculations (base changes, adjustments)
- Applied case (equal, upset, baseline, underperform, overperform)
- Final rating changes

---

## Testing

### Test Coverage
- **Unit tests**: 21 tests (RankingCalculatorUnitTest)
- **API tests**: 12 tests (RankingCalculationApiTest)
- **Payload tests**: 7 tests (RankingCalculationPayloadTest)
- **Edge case tests**: 12 tests (RankingCalculationEdgeCasesTest)
- **Audit tests**: 27 tests (RankingCalculatorAuditTest)

**Total**: 79 tests covering all five cases and edge scenarios

### Edge Cases Tested
✅ Extreme rating gaps (5.0+ difference)
✅ Dominance near/above cap (2.4, 3.0)
✅ Near-equal ratings (0.009 vs 0.011)
✅ Margin difference thresholds (2.0 boundary)
✅ Best-of-5 matches (professional format)
✅ Multiple tiebreaks in match
✅ Split sets with varying margins
✅ Complete shutouts (0 games for loser)
✅ Boundary players (7.0 vs 1.0 NTRP)
✅ Moderate vs full overperformance

---

## Future Enhancements (Out of Scope)

Potential improvements not currently implemented:
1. **Historical weighting**: Consider recent performance trends
2. **Time decay**: Reduce weight of older matches
3. **Set depth weighting**: Weight later sets more heavily in best-of-5
4. **Tiebreak point consideration**: Factor in tiebreak closeness
5. **Surface adjustments**: Different K-factors for clay, grass, hard court
6. **Tournament context**: Weight tournament matches differently than practice
7. **Opponent quality**: Adjust for strength of schedule

---

## References

- **Elo Rating System**: [Wikipedia](https://en.wikipedia.org/wiki/Elo_rating_system)
- **NTRP Rating System**: USTA National Tennis Rating Program (1.0-7.0 scale)
- **UTR Rating System**: Universal Tennis Rating (1.0-16.5 scale)

---

**Document Version**: 1.0
**Last Updated**: 2026-06-02
**Algorithm Version**: Performance-Based Elo v1.0
