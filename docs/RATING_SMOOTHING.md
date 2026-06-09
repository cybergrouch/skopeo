# Rating Smoothing Algorithm

## Overview

Skopeo implements **USTA NTRP Dynamic-style rating smoothing**, a technique that creates more stable and predictable rating changes by blending calculated ratings with previous ratings.

## Why Smoothing?

Without smoothing, a single exceptional or poor performance can cause dramatic rating swings. Smoothing addresses this by:

- **Reducing volatility**: Prevents wild rating swings from single matches
- **Improving convergence**: Ratings move gradually toward true skill level
- **Dampening noise**: Outlier performances have less dramatic impact
- **Better player experience**: Ratings feel more predictable and fair

## The Algorithm

### Basic Formula

```
calculatedRating = previousRating + rawChange
smoothedRating = (calculatedRating × factor) + (previousRating × (1 - factor))
```

This can be simplified to:

```
smoothedChange = rawChange × smoothingFactor
finalRating = previousRating + smoothedChange
```

### Process Order

1. **Calculate raw change**: Use performance-based Elo formula
2. **Apply smoothing**: Blend calculated and previous ratings
3. **Clamp to range**: Ensure rating stays within valid bounds (1.0-7.0 NTRP, 1.0-16.0 UTR)

### Mathematical Properties

- **Zero-sum preserved**: `player1Change + player2Change = 0` (before clamping)
- **Linear scaling**: Smoothing applies uniformly across all rating levels
- **Commutative**: Order of smoothing doesn't matter for multiple matches
- **Idempotent at extremes**: Factor 0.0 = no change, Factor 1.0 = full change

## Smoothing Factor Values

| Factor | Name | Description | When to Use |
|--------|------|-------------|-------------|
| **0.0** | Frozen | No change applied | Testing only |
| **0.3** | Conservative | 30% of calculated change | High-stakes leagues, established players |
| **0.5** | Standard | 50% of calculated change (USTA style) | **Recommended default** |
| **0.7** | Aggressive | 70% of calculated change | Newer players, rapid convergence needed |
| **1.0** | Full | 100% of calculated change (no smoothing) | Tournament play, single events |

## Examples

### Example 1: Equal Players, Dominant Win (6-0)

**Setup**: Both players at 4.0 NTRP, Player 1 wins 6-0

**Raw calculation** (no smoothing):
- Raw change: ±0.160000
- Player 1: 4.0 → 4.160000
- Player 2: 4.0 → 3.840000

**With smoothing factor 0.3** (conservative):
```
smoothedChange = 0.160000 × 0.3 = 0.048000
Player 1: 4.0 → 4.048000 (+0.048000)
Player 2: 4.0 → 3.952000 (-0.048000)
```

**With smoothing factor 0.5** (USTA standard):
```
smoothedChange = 0.160000 × 0.5 = 0.080000
Player 1: 4.0 → 4.080000 (+0.080000)
Player 2: 4.0 → 3.920000 (-0.080000)
```

**With smoothing factor 0.7** (aggressive):
```
smoothedChange = 0.160000 × 0.7 = 0.112000
Player 1: 4.0 → 4.112000 (+0.112000)
Player 2: 4.0 → 3.888000 (-0.112000)
```

### Example 2: Upset Victory

**Setup**: 4.0 NTRP underdog defeats 4.5 NTRP favorite, 6-0

**Raw calculation** (no smoothing):
- Raw change: ±0.321284
- Player 1 (4.0): 4.0 → 4.321284
- Player 2 (4.5): 4.5 → 4.178716

**With smoothing factor 0.5**:
```
smoothedChange = 0.321284 × 0.5 = 0.160642
Player 1: 4.0 → 4.160642 (+0.160642)
Player 2: 4.5 → 4.339358 (-0.160642)
```

### Example 3: Close Match

**Setup**: Equal 4.0 NTRP players, Player 1 wins 6-4

**Raw calculation** (no smoothing):
- Raw change: ±0.032000
- Player 1: 4.0 → 4.032000
- Player 2: 4.0 → 3.968000

**With smoothing factor 0.5**:
```
smoothedChange = 0.032000 × 0.5 = 0.016000
Player 1: 4.0 → 4.016000 (+0.016000)
Player 2: 4.0 → 3.984000 (-0.016000)
```

## UTR vs NTRP Smoothing

Both rating systems use the same smoothing formula, but UTR changes are 2.5× larger due to K-factor scaling:

| System | K-Factor | Range | Example Change (no smoothing) | With 0.5 Smoothing |
|--------|----------|-------|-------------------------------|-------------------|
| NTRP | 0.16 | 1.0-7.0 (6.0) | ±0.160000 | ±0.080000 |
| UTR | 0.40 | 1.0-16.0 (15.0) | ±0.400000 | ±0.200000 |

**Key insight**: UTR changes are 2.5× larger, but as a percentage of range:
- NTRP: 0.160 / 6.0 = 2.67% of range
- UTR: 0.400 / 15.0 = 2.67% of range

Smoothing maintains this proportional relationship.

## API Usage

### Kotlin

```kotlin
import org.skopeo.dto.RankingCalculationRequest
import org.skopeo.model.RatingCalculationOptions

val request = RankingCalculationRequest(
    players = mapOf(
        "P1" to PlayerProfile(
            playerId = "P1",
            name = "Player 1",
            rating = Rating(value = "4.0", system = RatingSystem.NTRP)
        ),
        "P2" to PlayerProfile(
            playerId = "P2",
            name = "Player 2",
            rating = Rating(value = "4.0", system = RatingSystem.NTRP)
        )
    ),
    matchScore = MatchScore(
        sets = listOf(
            SetScore(
                games = mapOf("P1" to 6, "P2" to 0),
                winner = "P1"
            )
        )
    ),
    options = RatingCalculationOptions(
        smoothingEnabled = true,
        smoothingFactor = 0.5  // USTA standard
    )
)

val result = calculator.calculate(request)
```

### REST API (JSON)

```json
{
  "players": {
    "P1": {
      "playerId": "P1",
      "name": "Player 1",
      "rating": {
        "value": "4.0",
        "system": "NTRP"
      }
    },
    "P2": {
      "playerId": "P2",
      "name": "Player 2",
      "rating": {
        "value": "4.0",
        "system": "NTRP"
      }
    }
  },
  "matchScore": {
    "sets": [
      {
        "games": {
          "P1": 6,
          "P2": 0
        },
        "winner": "P1"
      }
    ]
  },
  "options": {
    "smoothingEnabled": true,
    "smoothingFactor": 0.5
  }
}
```

## Audit Trail

When smoothing is enabled, the audit trail includes detailed information:

```kotlin
val result = calculator.calculate(request)

result.audit.filter { it.message.contains("NTRP change") }.forEach { entry ->
    println("Message: ${entry.message}")
    // "NTRP change: 4.0 + 0.160000 = 4.160000 -> smoothed 4.080000 (factor=0.5) -> clamped 4.080000"

    println("Context:")
    entry.context.forEach { (key, value) ->
        println("  $key: $value")
    }
    // smoothingEnabled: true
    // smoothingFactor: 0.5
    // smoothed: 4.080000
    // original: 4.0
    // change: 0.160000
    // newValue: 4.160000
    // clamped: 4.080000
}
```

## Best Practices

### Choosing a Smoothing Factor

1. **0.5 (USTA Standard)** - Recommended for most use cases
   - Balanced between stability and responsiveness
   - Proven effective by USTA NTRP Dynamic Algorithm
   - Good for leagues with regular play (weekly/biweekly)

2. **0.3 (Conservative)** - Use when:
   - Players have well-established ratings
   - High-stakes competitive leagues
   - Limited match frequency (monthly or less)
   - Want to minimize rating volatility

3. **0.7 (Aggressive)** - Use when:
   - New players entering the system
   - Rapid convergence to true skill needed
   - High match frequency (multiple matches per week)
   - Tournament or short-season play

4. **1.0 (No Smoothing)** - Use when:
   - Single elimination tournaments
   - One-time events
   - Testing algorithm behavior
   - Maximum responsiveness desired

### Mixing Smoothed and Non-Smoothed Matches

You can use different smoothing settings for different matches:

```kotlin
// Regular season: use smoothing
val regularSeasonOptions = RatingCalculationOptions(
    smoothingEnabled = true,
    smoothingFactor = 0.5
)

// Playoff match: no smoothing (higher stakes)
val playoffOptions = RatingCalculationOptions(
    smoothingEnabled = false
)
```

### Boundary Conditions

Be aware that smoothing is applied BEFORE boundary clamping:

```kotlin
// Player near boundary (6.8 NTRP)
// Raw change: +0.300 → would go to 7.1
// With 0.5 smoothing: +0.150 → goes to 6.95
// Clamped: 6.95 (within bounds)

// Without smoothing, would be clamped at 7.0
// With smoothing, stays below maximum
```

This means smoothing can actually help players stay within boundaries more naturally.

## Performance Considerations

- **Computation overhead**: Negligible (one additional multiplication per player)
- **Memory overhead**: None (stateless calculation)
- **Precision**: Full BigDecimal precision maintained throughout

## Backward Compatibility

Smoothing is **disabled by default** to maintain backward compatibility:

```kotlin
// These are equivalent:
val request1 = RankingCalculationRequest(
    players = players,
    matchScore = matchScore
    // options = null (smoothing disabled)
)

val request2 = RankingCalculationRequest(
    players = players,
    matchScore = matchScore,
    options = RatingCalculationOptions(
        smoothingEnabled = false  // explicit
    )
)
```

Existing code continues to work without modification.

## Testing

Comprehensive test coverage in `PerformanceBasedRankingCalculatorImplTest.kt`:

- **NTRP Smoothing**: 6 tests covering factors 0.3, 0.5, 0.7
- **UTR Smoothing**: 3 tests verifying 2.5× scaling maintained
- **Edge Cases**: 3 tests for boundaries and extreme values
- **Audit Trail**: Verification of smoothing metadata
- **Zero-Sum Property**: Confirmed preserved before clamping

Test scenarios also included in `TestScenarios.kt` (SM1-SM6).

## References

- **USTA NTRP Dynamic Algorithm**: Inspired the smoothing approach
- **Elo Rating System**: Foundation for base rating calculations
- **Skopeo Documentation**: [RatingCalculationOptions.kt](../src/main/kotlin/org/skopeo/model/RatingCalculationOptions.kt)

## Summary

Rating smoothing provides a simple yet powerful way to create more stable and fair rating systems. By choosing an appropriate smoothing factor, you can balance between rating stability and responsiveness to new information.

**Recommended**: Start with factor 0.5 (USTA standard) and adjust based on your specific use case and player feedback.
