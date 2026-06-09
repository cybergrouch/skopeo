# Performance-Based Elo Rating Algorithm

## Overview

Skopeo uses a **performance-based Elo rating system** with normalized gaps to calculate rating changes after matches. Unlike standard Elo (which only considers win/loss), this algorithm accounts for **how dominantly** a player won and whether the result represents an **upset** or meets **expectations**.

## Core Principles

### 1. Normalized Rating Gaps

Rating gaps are normalized by the rating system's range to ensure proportional fairness across different systems:

**Formula**: `normalizedGap = abs(ratingDiff) / ratingRange`

**Example**:
- NTRP: 0.5 gap ÷ 6.0 range = 8.3% of range
- UTR: 1.25 gap ÷ 15.0 range = 8.3% of range
- Both produce **identical** competitive factors → consistent K-factor scaling

**Rationale**: This ensures that a "significant gap" means the same thing proportionally across all rating systems.

### 2. Competitive Threshold

A **threshold of 8.3%** of the rating range determines whether a match is competitive:

**Constants**:
- NTRP threshold: 8.3% × 6.0 = 0.5 rating points
- UTR threshold: 8.3% × 15.0 = 1.25 rating points

**Purpose**: Matches within the threshold produce full performance-based changes. Matches beyond the threshold produce reduced changes.

**Rationale**: The 8.3% threshold (roughly 1/12 of the rating range) represents a "half-level" difference in tennis skill that is significant but still competitive.

### 3. Dominance Factor

The **normalized margin** (games won minus games lost, divided by total games) amplifies rating changes:

**Calculation**: `dominance = (gamesWon - gamesLost) / (gamesWon + gamesLost)`

**Range**: -1.0 to +1.0 (naturally bounded, no artificial cap needed)

**Examples**:
- 6-0 shutout: (6-0)/(6+0) = 1.0 dominance (maximum)
- 6-4 match: (6-4)/(6+4) = 0.2 dominance
- 7-5 match: (7-5)/(7+5) = 0.167 dominance
- 7-6 tiebreak: (7-6)/(7+6) = 0.077 dominance (very close)

**Rationale**: Normalized dominance reflects match closeness. A 6-0 win (dominance = 1.0) produces much larger changes than a 7-6 win (dominance ≈ 0.08). This metric naturally handles all scores without needing special cases for shutouts or caps for extreme ratios.

---

## Rating Adjustment Algorithm

### Two-Path System

The algorithm uses **two main paths** based on whether the result is an upset:

#### Path 1: Upset (underdog wins or favorite loses)
**When**: `(isWinner && ratingAdvantage < 0) || (!isWinner && ratingAdvantage > 0)`

**Logic**: Apply upset multiplier based on gap size

**Formula**:
```
scale = (normalizedGap / threshold) × upsetMultiplier
change = K × dominance × scale × sign
```

**Constants**:
- `upsetMultiplier = 2.0` (doubles the impact of upsets)

**Example**:
- P1 (3.0 NTRP) vs P2 (4.0 NTRP), P1 wins
- normalizedGap = 1.0 / 6.0 = 0.167 (16.7%)
- threshold = 0.083 (8.3%)
- scale = (0.167 / 0.083) × 2.0 = 4.0
- Higher gap = bigger upset = larger rating change

**Rationale**: Upsets indicate rating inaccuracy. Larger gaps produce proportionally larger changes. The 2.0 multiplier ensures upsets are rewarded/penalized significantly compared to expected outcomes.

#### Path 2: Competitive or Expected Outcome
**When**: Favorite wins or competitive match (gap ≤ threshold)

**Logic**: Apply competitive factor that decreases as gap increases

**Formula**:
```
scale = max(0, (threshold - normalizedGap) / threshold)
change = K × dominance × scale × sign
```

**Examples**:
- Equal ratings (gap = 0): scale = (0.083 - 0) / 0.083 = 1.0 (full performance-based change)
- Small gap (0.25 NTRP = 4.2% normalized): scale = (0.083 - 0.042) / 0.083 = 0.49
- At threshold (0.5 NTRP = 8.3%): scale = (0.083 - 0.083) / 0.083 = 0.0 (no change)
- Beyond threshold (1.0 NTRP = 16.7%): scale = max(0, ...) = 0.0 (no change)

**Rationale**: When the favorite wins as expected, ratings are already accurate. The larger the gap, the more expected the outcome, the smaller the rating change.

### Complete Formula

**For any match**:
```kotlin
normalizedGap = abs(ratingDiff) / ratingRange
scale = if (upset) {
    (normalizedGap / threshold) × upsetMultiplier
} else {
    max(0, (threshold - normalizedGap) / threshold)
}
sign = if (isWinner) +1 else -1
change = K × dominance × scale × sign
```

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

**Example**:
- NTRP: Equal players, winner gains 0.160 → loser loses 0.160
- UTR: Same scenario → winner gains 0.400 → loser loses 0.400
- Ratio: 0.400 / 0.160 = 2.5 ✓

**Rationale**: Standard Elo practice. Maintains consistent volatility across systems when accounting for different scales.

### Why K = 0.16 for NTRP?

The base K-factor of 0.16 produces typical changes of:
- Equal players, close match (6-4): ±0.032
- Equal players, dominant match (6-0): ±0.160
- Moderate upset (0.5 gap reversed): ±0.321
- Large upset (1.0 gap reversed): ±0.643

This ensures ratings converge gradually over multiple matches without excessive volatility from individual results.

---

## Special Cases and Edge Cases

### Equal Ratings (< 0.01 difference)

While not explicitly branched in the code, ratings within 0.01 are effectively equal:
- normalizedGap ≈ 0.0
- scale = (threshold - 0) / threshold = 1.0
- Result: Full performance-based change using dominance factor

**Example**:
- P1 (10.000 UTR) vs P2 (10.005 UTR), P1 wins 6-0
- Treated as equal players
- Change based purely on dominance (2.0)

### Large Rating Gaps (Beyond Threshold)

When gap significantly exceeds the competitive threshold:
- Favorite wins: scale = 0.0 → no change (expected outcome)
- Underdog wins: scale proportional to gap size → significant change

**Example 1 - Expected Win**:
- P1 (5.0 NTRP) vs P2 (3.0 NTRP), P1 wins 6-0
- normalizedGap = 2.0 / 6.0 = 33.3%
- scale = max(0, (0.083 - 0.333) / 0.083) = 0.0
- **Both ratings unchanged** (result expected)

**Example 2 - Major Upset**:
- P2 (3.0 NTRP) vs P1 (5.0 NTRP), P2 wins 6-0
- normalizedGap = 2.0 / 6.0 = 33.3%
- scale = (0.333 / 0.083) × 2.0 = 8.0
- **Large rating change** reflecting major upset

### Dominance Factor Edge Cases

**Complete Shutout (0 games for loser)**:
- 6-0: dominance = (6-0)/(6+0) = 1.0 (maximum possible)
- 6-0, 6-0: dominance = (12-0)/(12+0) = 1.0 (maximum possible)
- The normalized formula naturally handles shutouts without division by zero

**Very Lopsided Matches**:
- 6-1: dominance = (6-1)/(6+1) = 0.714
- 6-0, 6-1: dominance = (12-1)/(12+1) = 0.846
- Approaches 1.0 asymptotically as gap increases

**Very Close Matches**:
- 7-6: dominance = (7-6)/(7+6) = 0.077
- 6-4, 6-4: dominance = (12-8)/(12+8) = 0.2
- Approaches 0.0 as match becomes closer

### Tiebreak Handling

**Current Behavior**: All games count equally
- 6-4 set = 6 games contributed
- 7-6(5) tiebreak set = 7 games contributed
- Tiebreak point scores (7-5, 10-8) are not factored

**No Special Weighting**: Tiebreak wins are not distinguished from standard wins

**Example**:
- P1 wins 7-6(7-5), 6-3 = 13 games vs 9 games
- Dominance = 13/9 = 1.44 (moderate)
- Close tiebreak + standard set = small rating change

---

## Zero-Sum Property and Boundary Enforcement

### Before Clamping: Zero-Sum

All rating changes calculated satisfy:
```
player1Change + player2Change = 0.0
```

This is inherent in the formula: one player's gain equals the other's loss.

### After Clamping: May Violate Zero-Sum

When a player reaches a rating boundary, their rating is clamped:
- **NTRP**: 1.0 minimum, 7.0 maximum
- **UTR**: 1.0 minimum, 16.0 maximum

**Example**:
- P1 (7.0 NTRP, at max) vs P2 (6.5 NTRP)
- P1 wins 6-0, calculated change = +0.2
- After clamping:
  - P1: 7.0 + 0.2 = 7.0 (clamped) → actual change = 0.0
  - P2: 6.5 - 0.2 = 6.3 → actual change = -0.2
- **Zero-sum violated**: 0.0 + (-0.2) = -0.2 ≠ 0.0

**Rationale**: Maintaining accurate ratings at boundaries is more important than strict zero-sum property. This prevents rating inflation/deflation at the edges of the scale.

---

## Precision and Rounding

### BigDecimal Precision

All calculations use BigDecimal with **6 decimal places** (CALCULATION_SCALE = 6).

**Why 6 decimals?**
- Sufficient precision for rating calculations
- Avoids floating-point rounding errors
- Standard practice for Elo implementations

### Display Precision

- NTRP: Displayed with up to 6 decimals (e.g., "4.123456")
- UTR: Displayed with up to 6 decimals (e.g., "10.123456")
- Percent changes: Up to 6 decimals (e.g., "1.234567%")

In practice, changes are typically in the range of 0.01-0.50 for competitive matches.

---

## Algorithm Constants Summary

| Constant | Value | Purpose | Rationale |
|----------|-------|---------|-----------|
| K_FACTOR_NTRP | 0.16 | Base volatility for NTRP | Typical changes ±0.03 to ±0.16 |
| K_FACTOR_UTR | 0.4 | Base volatility for UTR | Scaled by range ratio (2.5×) |
| COMPETITIVE_THRESHOLD_PCT | 0.083 | Competitive gap threshold | 8.3% = ~1/12 of range (half level) |
| NTRP_RANGE | 6.0 | NTRP scale range | 1.0 to 7.0 |
| UTR_RANGE | 15.0 | UTR scale range | 1.0 to 16.0 |
| Upset multiplier | 2.0 | Upset bonus factor | Doubles impact of unexpected results |

---

## Worked Examples

### Example 1: Equal Players, Competitive Match

**Setup**:
- P1 (5.0 NTRP) vs P2 (5.0 NTRP)
- Result: P1 wins 6-4

**Calculation**:
```
normalizedGap = 0.0 / 6.0 = 0.0
dominance = (6 - 4) / (6 + 4) = 0.2
scale = (0.083 - 0.0) / 0.083 = 1.0 (full competitive factor)
change = 0.16 × 0.2 × 1.0 × (+1) = +0.032 for P1

Result: P1 = 5.032, P2 = 4.968
```

### Example 2: Small Gap, Expected Win

**Setup**:
- P1 (4.5 NTRP) vs P2 (4.0 NTRP) [gap = 0.5, at threshold]
- Result: P1 wins 6-3

**Calculation**:
```
normalizedGap = 0.5 / 6.0 = 0.083 (exactly at threshold)
dominance = (6 - 3) / (6 + 3) = 0.333
scale = (0.083 - 0.083) / 0.083 = 0.0
change = 0.16 × 0.333 × 0.0 × (+1) = 0.0

Result: No change (met expectations exactly)
```

### Example 3: Upset Win

**Setup**:
- P1 (3.0 NTRP) vs P2 (4.0 NTRP) [gap = 1.0]
- Result: P1 wins 6-2 (upset)

**Calculation**:
```
normalizedGap = 1.0 / 6.0 = 0.167
dominance = (6 - 2) / (6 + 2) = 0.5
scale = (0.167 / 0.083) × 2.0 = 4.0 (upset multiplier applied)
change = 0.16 × 0.5 × 4.0 × (+1) = +0.32 for P1

Result: P1 = 3.32, P2 = 3.68
```

### Example 4: Large Gap, Expected Win

**Setup**:
- P1 (6.0 NTRP) vs P2 (3.0 NTRP) [gap = 3.0]
- Result: P1 wins 6-0, 6-0

**Calculation**:
```
normalizedGap = 3.0 / 6.0 = 0.5 (way beyond threshold)
dominance = (12 - 0) / (12 + 0) = 1.0 (maximum dominance)
scale = max(0, (0.083 - 0.5) / 0.083) = 0.0
change = 0.16 × 1.0 × 0.0 × (+1) = 0.0

Result: No change (heavily favored player won as expected)
```

### Example 5: Close Match Near Threshold

**Setup**:
- P1 (4.3 NTRP) vs P2 (4.0 NTRP) [gap = 0.3, within threshold]
- Result: P1 wins 7-5

**Calculation**:
```
normalizedGap = 0.3 / 6.0 = 0.05
dominance = (7 - 5) / (7 + 5) = 0.167
scale = (0.083 - 0.05) / 0.083 = 0.398
change = 0.16 × 0.167 × 0.398 × (+1) = +0.011 for P1

Result: P1 = 4.311, P2 = 3.989
```

---

## Known Limitations

### 1. No Historical Context

Each match is independent; algorithm doesn't consider:
- Previous match history
- Rating trends over time
- Recent performance streaks

### 2. No Time Decay

A match from 6 months ago has the same weight as yesterday's match.

### 3. No Input Validation in Calculator

Algorithm assumes valid tennis scores. Invalid scores (e.g., 8-0, 5-3) may produce unexpected results if not caught by model validation.

### 4. Tiebreak Points Ignored

Only game counts matter; tiebreak point scores (7-5, 10-8) are not factored into calculations.

### 5. Set Context Ignored

- No weighting for set depth (1st set vs 5th set in Grand Slam)
- Split set wins (6-0, 3-6, 6-2) treated identically to straight sets with same total games

### 6. Fixed Upset Multiplier

The 2.0 upset multiplier is constant regardless of:
- Gap size (1.0 upset vs 3.0 upset both use 2.0)
- Match closeness (6-0 upset vs 7-6 upset both use 2.0)

This is intentional for simplicity but could be refined.

---

## Testing

### Test Coverage

- **Unit tests**: PerformanceBasedRankingCalculatorImplTest (24 NTRP + 24 UTR scenarios)
- **API tests**: RankingCalculationApiTest (boundary conditions, system tests)
- **Payload tests**: RankingCalculationPayloadTest (exact value verification)
- **Comparison tests**: NTRPvsUTRComparison (2.5× K-factor validation)

**Total**: 70+ tests covering all scenarios and edge cases

### Edge Cases Tested

✅ Equal players (various dominance levels)
✅ Small gaps within threshold (0.1-0.4 NTRP)
✅ At-threshold gaps (0.5 NTRP, 1.25 UTR)
✅ Beyond-threshold gaps (1.0+ NTRP, 2.5+ UTR)
✅ Extreme gaps (2.0-5.0 rating difference)
✅ Upsets at various gap sizes
✅ Dominance edge cases (shutouts, cap boundary)
✅ Tiebreak scenarios
✅ Multi-set matches
✅ Boundary conditions (1.0 and 7.0 NTRP, 16.0 UTR)
✅ K-factor scaling verification (UTR = 2.5× NTRP)

---

## Implementation Details

### File Location

`src/main/kotlin/org/skopeo/service/calculator/impl/v1/PerformanceBasedRankingCalculatorImpl.kt`

### Key Methods

1. **`calculate(request: RankingCalculationRequest)`**
   - Main entry point
   - Orchestrates calculation and applies boundary clamping
   - Returns RankingCalculationResult with audit trail

2. **`calculateRatingAdjustments(...)`**
   - Core rating calculation logic
   - Implements the two-path algorithm (upset vs competitive)
   - Uses normalized gaps for proportional fairness

3. **`calculateRankingAdjustment(...)`**
   - Inner function calculating individual player's change
   - Determines scale factor based on upset vs competitive scenario
   - Applies dominance factor and sign

4. **`applyRatingChange(...)`**
   - Applies rating change with system-specific constraints
   - Enforces boundary clamping (1.0-7.0 for NTRP, 1.0+ for UTR)
   - Maintains 6 decimal precision

### Audit Trail

Every calculation produces a detailed audit trail including:
- Initial player ratings
- Match result (winner, scores, dominance)
- Calculated rating adjustments
- Applied boundary clamping
- Final rating changes

This is crucial for debugging, transparency, and validation.

---

## Future Enhancements (Out of Scope)

Potential improvements not currently implemented:

1. **Historical weighting**: Consider recent performance trends (rolling average)
2. **Time decay**: Reduce weight of older matches (exponential decay)
3. **Set depth weighting**: Weight later sets more heavily in best-of-5
4. **Tiebreak point consideration**: Factor in tiebreak closeness (7-5 vs 10-8)
5. **Surface adjustments**: Different K-factors for clay, grass, hard court
6. **Tournament context**: Weight tournament matches differently than practice
7. **Opponent quality**: Adjust for strength of schedule
8. **Dynamic thresholds**: Adjust competitive threshold based on rating level
9. **Graduated upset multiplier**: Scale upset bonus by gap size (non-linear)

---

## References

- **Elo Rating System**: [Wikipedia](https://en.wikipedia.org/wiki/Elo_rating_system)
- **NTRP Rating System**: USTA National Tennis Rating Program (1.0-7.0 scale)
- **UTR Rating System**: Universal Tennis Rating (1.0-16.5 scale)
- **K-factor Calibration**: Standard Elo practice for multi-system normalization

---

**Document Version**: 2.0 (Updated for Normalized Gap Algorithm)
**Last Updated**: 2026-06-05
**Algorithm Version**: Performance-Based Elo v2.0 (Normalized Gap Implementation)
