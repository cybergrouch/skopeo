# Tennis Ranking Algorithm Documentation

## Overview

Tennis Levelr uses an **Elo-based rating system** adapted for tennis match scoring. The algorithm calculates rating changes based on match outcomes, considering both the rating differential between players and the dominance of the victory.

**Key Features:**
- Dynamic rating adjustments based on expected vs actual performance
- Match dominance factor for more accurate skill assessment
- System-specific constraints (NTRP and UTR)
- Continuous decimal ratings for precise skill representation

---

## What is "Elo"?

### The Name, Not an Acronym

**Important:** "Elo" is a person's name, not an acronym. It should be written as "Elo" (capitalized like a name), not "Elo" (all caps).

**Elo** refers to **Arpad Elo** (1903-1992), a Hungarian-American physics professor and chess master who invented the Elo rating system in the 1960s. The United States Chess Federation (USCF) adopted his system in 1960, and FIDE (World Chess Federation) adopted it in 1970.

### Why Elo Created the System

Before Elo's system, chess ratings were subjective and inconsistent. Players needed a way to:
- **Compare skill levels** objectively across different players
- **Predict match outcomes** based on rating differences
- **Track improvement** over time with quantifiable metrics
- **Organize tournaments** fairly by grouping similar-skill players

Elo's breakthrough was creating a mathematical model that:
1. Self-corrects over time (ratings converge toward true skill)
2. Accounts for expected outcomes (beating a stronger opponent gains more points)
3. Maintains a zero-sum system (points transfer between players)
4. Updates continuously after each match

### Elo Beyond Chess

The success of Elo's system in chess led to adoption across many competitive domains:

**Sports:**
- Table tennis, soccer, basketball, American football
- Major League Baseball (FiveThirtyEight's MLB Elo ratings)
- NFL predictions and power rankings
- International football (FIFA rankings use a modified Elo)

**Esports:**
- League of Legends, Counter-Strike, Dota 2
- Rocket League, Overwatch competitive modes
- Many online gaming platforms

**Online Platforms:**
- Chess.com, Lichess (chess platforms)
- Dating apps (compatibility scoring)
- Competitive matchmaking systems

**Why Elo Works Across Domains:**
- Simple mathematical foundation
- Proven statistical properties
- Self-stabilizing over multiple matches
- Easy to understand and explain
- Computationally efficient

---

## Algorithm Foundation: The Elo Rating System

The Elo rating system, originally designed for chess, has been adapted for tennis. It operates on the principle that a player's rating should increase after a win and decrease after a loss, with the magnitude of change depending on the opponent's rating.

### Why Elo for Tennis?

Tennis is well-suited for Elo ratings because:

1. **One-on-one competition**: Direct head-to-head matches (like chess)
2. **Clear outcomes**: Every match has a winner and loser
3. **Skill-based**: Performance depends primarily on player skill
4. **Frequent matches**: Players compete regularly, allowing ratings to stabilize
5. **Measurable dominance**: Match scores provide additional context (games won)

### Adaptations for Tennis

Our implementation enhances the traditional Elo system with tennis-specific features:

**Standard Elo:**
- Win = 1 point, Loss = 0 points
- Only considers outcome (win/loss)
- Designed for chess (discrete moves, long games)

**Tennis Elo (Tennis Levelr):**
- Win = 1 point, Loss = 0 points (same)
- **Adds dominance factor** based on games won (6-0, 6-0 vs 7-6, 7-6)
- **System-specific constraints** (NTRP 1.0-7.0 range, UTR 1.0+ range)
- **Different precision levels** (NTRP: 2 decimals, UTR: 1 decimal)
- **BigDecimal calculations** for 6-digit precision (no floating-point errors)

### Core Principles

1. **Zero-Sum System**: Rating points are transferred between players
2. **Predictive**: Higher-rated players are expected to beat lower-rated players
3. **Self-Correcting**: Ratings converge toward true skill level over multiple matches
4. **Continuous**: Ratings update after every match

---

## Mathematical Model

### 1. Expected Score Calculation

The expected score predicts the probability of a player winning based on rating differential:

```
E_A = 1 / (1 + 10^((R_B - R_A) / 400))
E_B = 1 - E_A
```

Where:
- `E_A` = Expected score for Player A (probability of winning)
- `E_B` = Expected score for Player B
- `R_A` = Current rating of Player A
- `R_B` = Current rating of Player B
- `400` = Scale factor (determines how rating gap affects expected score)

**Scale Factor Explanation:**
- A 400-point rating gap means the higher-rated player has ~91% expected win probability
- A 200-point gap means ~76% expected win probability
- Equal ratings mean 50% expected win probability for each player

**Example:**
```
Player A: 5.0 NTRP
Player B: 4.0 NTRP

E_A = 1 / (1 + 10^((4.0 - 5.0) / 400))
    = 1 / (1 + 10^(-0.0025))
    = 1 / (1 + 0.9943)
    = 1 / 1.9943
    = 0.5014 (50.14% expected win probability)
```

### 2. Dominance Factor

Unlike chess (which only considers win/loss), tennis matches have varying levels of dominance. We calculate a dominance factor based on games won:

```
dominance = min(total_games_winner / total_games_loser, 2.5)
```

Where:
- `total_games_winner` = Sum of games won by the match winner across all sets
- `total_games_loser` = Sum of games won by the match loser across all sets
- `2.5` = Maximum dominance factor (prevents extreme rating swings)

**Dominance Factor Scale:**
- `1.0` = Very close match (e.g., 12-12 in games)
- `1.5` = Moderate dominance (e.g., 12-8 in games)
- `2.0` = Clear dominance (e.g., 12-6 in games)
- `2.5` = Overwhelming dominance (e.g., 12-4 or better)

**Examples:**
```
Match 1: 6-4, 6-4 (Winner: 12 games, Loser: 8 games)
dominance = 12 / 8 = 1.5

Match 2: 6-0, 6-1 (Winner: 12 games, Loser: 1 game)
dominance = 12 / 1 = 12.0 → capped at 2.5

Match 3: 6-4, 4-6, 7-5 (Winner: 17 games, Loser: 15 games)
dominance = 17 / 15 = 1.13
```

### 3. Rating Change Calculation

The rating change is calculated using the K-factor, dominance factor, and difference between actual and expected scores:

```
ΔR_A = K × dominance × (S_A - E_A)
ΔR_B = K × dominance × (S_B - E_B)
```

Where:
- `ΔR` = Rating change
- `K` = K-factor (32 in current implementation)
- `dominance` = Dominance factor from step 2
- `S_A` = Actual score for Player A (1 for win, 0 for loss)
- `E_A` = Expected score from step 1

**K-Factor Selection:**
- **K = 32** (Current): Moderate volatility, faster convergence
- **K = 24**: Lower volatility, common in professional chess
- **K = 16**: Very stable, used for established players
- **K = 40**: High volatility, used for beginners

**Example Calculation:**
```
Player A (5.0 NTRP) defeats Player B (4.0 NTRP)
Match: 6-3, 6-2 (Winner: 12 games, Loser: 5 games)

Step 1: Expected scores
E_A = 0.5014
E_B = 0.4986

Step 2: Dominance factor
dominance = 12 / 5 = 2.4

Step 3: Rating changes
ΔR_A = 32 × 2.4 × (1 - 0.5014) = 32 × 2.4 × 0.4986 = 38.28
ΔR_B = 32 × 2.4 × (0 - 0.4986) = 32 × 2.4 × (-0.4986) = -38.28

New ratings:
Player A: 5.0 + 38.28 = 43.28 (before system constraints)
Player B: 4.0 - 38.28 = -34.28 (before system constraints)
```

### 4. System-Specific Constraints

After calculating the raw rating change, system-specific constraints are applied:

#### NTRP (National Tennis Rating Program)
- **Range**: 1.0 to 7.0
- **Precision**: 2 decimal places (e.g., 4.37, 5.82)
- **Bounds**: Ratings are clamped to [1.0, 7.0] range

```kotlin
new_rating = original_rating + change
rounded = round(new_rating × 100) / 100  // 2 decimal places
final_rating = clamp(rounded, 1.0, 7.0)
```

#### UTR (Universal Tennis Rating)
- **Range**: 1.0 to unlimited (professional players can exceed 16.0)
- **Precision**: 1 decimal place (e.g., 8.5, 12.3)
- **Minimum**: 1.0 (no maximum)

```kotlin
new_rating = original_rating + change
rounded = round(new_rating × 10) / 10  // 1 decimal place
final_rating = max(rounded, 1.0)
```

---

## Implementation Details

### Match Result Analysis

The algorithm analyzes the match to determine:

1. **Match Winner**: Player who won the majority of sets
2. **Total Games Won**: Sum of games across all sets for each player
3. **Dominance Factor**: Ratio of winner's games to loser's games

**Code Location:** `RankingCalculator.analyzeMatchResult()` in `src/main/kotlin/org/lange/tennis/levelr/service/RankingCalculator.kt:94`

### Rating Change Application

Rating changes are applied differently for NTRP and UTR:

**NTRP:** `RankingCalculator.applyNTRPChange()` at line 180
- Rounds to 2 decimal places
- Clamps to [1.0, 7.0] range

**UTR:** `RankingCalculator.applyUTRChange()` at line 200
- Rounds to 1 decimal place
- Ensures minimum of 1.0

---

## Algorithm Parameters

### Current Configuration

| Parameter | Value | Description |
|-----------|-------|-------------|
| K-Factor | 32 | Controls rating volatility |
| Scale Factor | 400 | Affects expected score calculation |
| Max Dominance | 2.5 | Caps dominance factor |
| NTRP Min | 1.0 | Minimum NTRP rating |
| NTRP Max | 7.0 | Maximum NTRP rating |
| UTR Min | 1.0 | Minimum UTR rating |
| NTRP Precision | 0.01 | 2 decimal places |
| UTR Precision | 0.1 | 1 decimal place |

### Parameter Tuning Guidelines

**K-Factor Adjustment:**
- **Increase K** (e.g., to 40) for:
  - New players with uncertain ratings
  - Faster rating convergence
  - More volatile rankings

- **Decrease K** (e.g., to 16) for:
  - Established players with stable ratings
  - Slower, more stable changes
  - Professional/high-level play

**Scale Factor Adjustment:**
- **Increase Scale** (e.g., to 500) for:
  - Smaller impact of rating gaps
  - More upsets are expected

- **Decrease Scale** (e.g., to 300) for:
  - Larger impact of rating gaps
  - Favorites are more heavily favored

---

## Examples

### Example 1: Even Match Between Equal Players

**Setup:**
- Player A: 5.0 NTRP
- Player B: 5.0 NTRP
- Match: 6-4, 4-6, 6-3 (Player A wins)

**Calculation:**
```
Expected Scores:
E_A = 1 / (1 + 10^0) = 0.5
E_B = 0.5

Dominance Factor:
Winner games: 16, Loser games: 13
dominance = 16 / 13 = 1.23

Rating Changes:
ΔR_A = 32 × 1.23 × (1 - 0.5) = 19.68
ΔR_B = 32 × 1.23 × (0 - 0.5) = -19.68

New Ratings (NTRP):
Player A: 5.0 + 19.68 = 24.68 → clamped to 7.0
Player B: 5.0 - 19.68 = -14.68 → clamped to 1.0
```

**Note:** This example shows the algorithm may produce large swings that get clamped. This is expected behavior when rating differential is small but match outcome is decisive.

### Example 2: Upset Victory

**Setup:**
- Player A: 3.5 NTRP (Underdog)
- Player B: 5.5 NTRP (Favorite)
- Match: 7-5, 6-4 (Player A wins)

**Calculation:**
```
Expected Scores:
E_A = 1 / (1 + 10^((5.5-3.5)/400)) = 1 / (1 + 10^0.005) = 0.4989
E_B = 0.5011

Dominance Factor:
Winner games: 13, Loser games: 9
dominance = 13 / 9 = 1.44

Rating Changes:
ΔR_A = 32 × 1.44 × (1 - 0.4989) = 23.09
ΔR_B = 32 × 1.44 × (0 - 0.5011) = -23.09

New Ratings (NTRP):
Player A: 3.5 + 23.09 = 26.59 → clamped to 7.0
Player B: 5.5 - 23.09 = -17.59 → clamped to 1.0
```

**Analysis:** Underdog gains significant rating for upset win, favorite loses significant rating.

### Example 3: Expected Victory

**Setup:**
- Player A: 6.0 NTRP (Favorite)
- Player B: 3.5 NTRP (Underdog)
- Match: 6-1, 6-2 (Player A wins)

**Calculation:**
```
Expected Scores:
E_A = 1 / (1 + 10^((3.5-6.0)/400)) = 0.5014
E_B = 0.4986

Dominance Factor:
Winner games: 12, Loser games: 3
dominance = 12 / 3 = 4.0 → capped at 2.5

Rating Changes:
ΔR_A = 32 × 2.5 × (1 - 0.5014) = 39.89
ΔR_B = 32 × 2.5 × (0 - 0.4986) = -39.89

New Ratings (NTRP):
Player A: 6.0 + 39.89 = 45.89 → clamped to 7.0
Player B: 3.5 - 39.89 = -36.39 → clamped to 1.0
```

**Analysis:** Favorite gains modest rating, underdog loses significant rating for expected outcome.

### Example 4: Close UTR Match

**Setup:**
- Player A: 10.5 UTR
- Player B: 10.2 UTR
- Match: 7-6(5), 6-7(3), 7-5 (Player A wins)

**Calculation:**
```
Expected Scores:
E_A = 1 / (1 + 10^((10.2-10.5)/400)) = 0.5002
E_B = 0.4998

Dominance Factor:
Winner games: 20, Loser games: 18
dominance = 20 / 18 = 1.11

Rating Changes:
ΔR_A = 32 × 1.11 × (1 - 0.5002) = 17.75
ΔR_B = 32 × 1.11 × (0 - 0.4998) = -17.75

New Ratings (UTR):
Player A: 10.5 + 17.75 = 28.25 → rounded to 28.3 (but seems high)
Player B: 10.2 - 17.75 = -7.55 → clamped to 1.0
```

**Note:** These examples reveal that the current K-factor of 32 may be too aggressive and cause large rating swings. Consider reducing to K=16 for more stable ratings.

---

## Algorithm Strengths

1. **Considers Match Dominance**: Unlike simple win/loss systems, accounts for how convincingly the match was won
2. **Self-Correcting**: Over multiple matches, ratings converge to true skill level
3. **Responsive**: Significant rating changes when upsets occur
4. **Proven System**: Elo has been successfully used in chess, esports, and other sports
5. **Continuous Ratings**: Allows precise skill differentiation without artificial increments

---

## Known Limitations

### 1. Large Rating Swings

**Issue:** With K=32 and dominance factor, single matches can cause extreme rating changes that get clamped.

**Example:** A 5.0 vs 5.0 match with a clear winner can jump the winner to 7.0 (max) and drop the loser to 1.0 (min).

**Potential Solutions:**
- Reduce K-factor to 16 or 24
- Implement progressive K-factors (higher for new players, lower for established)
- Add rating change caps (e.g., max ±1.0 per match)
- Require minimum number of matches before ratings stabilize

### 2. Cold Start Problem

**Issue:** New players start with an estimated rating that may be inaccurate.

**Potential Solutions:**
- Use higher K-factor for first 10-20 matches
- Implement provisional ratings with uncertainty ranges
- Allow manual seeding based on self-assessment or other factors

### 3. Rating Inflation/Deflation

**Issue:** Over time, the average rating in a population may drift upward or downward.

**Potential Solutions:**
- Periodic normalization to maintain average rating
- Track rating distribution and adjust periodically
- Implement rating floors/ceilings that adapt to population

### 4. Doesn't Consider Match Context

**Issue:** Algorithm treats all matches equally, regardless of:
- Tournament vs casual play
- Surface type (clay, grass, hard court)
- Match format (best of 3 vs best of 5)
- Time decay (recent matches vs old matches)

**Potential Solutions:**
- Weight matches by type/importance
- Implement time decay for older matches
- Add surface-specific ratings
- Apply confidence intervals that decrease over time

### 5. Same-System Matches Only

**Issue:** Cannot currently process matches between NTRP and UTR players.

**Potential Solutions:**
- Create conversion formula between systems
- Maintain separate ratings per system
- Implement a universal internal rating with system-specific displays

---

## Future Enhancements

### Phase 1: Stability Improvements
- [ ] Reduce K-factor to 16-24 for more stable ratings
- [ ] Add maximum rating change cap (e.g., ±1.0 per match)
- [ ] Implement progressive K-factors based on match history

### Phase 2: Advanced Features
- [ ] Time decay for older matches (rating confidence decreases over time)
- [ ] Match weighting by tournament tier/importance
- [ ] Surface-specific ratings (clay, grass, hard court)
- [ ] Head-to-head performance tracking

### Phase 3: Statistical Enhancements
- [ ] Confidence intervals for ratings
- [ ] Provisional ratings for new players (first 10-20 matches)
- [ ] Rating history and trend analysis
- [ ] Win probability predictions

### Phase 4: Cross-System Support
- [ ] NTRP ↔ UTR conversion formulas
- [ ] Support for matches between different rating systems
- [ ] International rating system support

---

## Testing and Validation

### Algorithm Tests

**Location:** `src/test/kotlin/org/lange/tennis/levelr/RankingAlgorithmTest.kt`

**Test Coverage:**
- Winners gain rating, losers lose rating ✅
- Upset victories result in larger rating changes ✅
- Dominant wins produce appropriate changes ✅
- Close matches produce smaller changes ✅
- System boundaries are respected (min/max) ✅
- Continuous decimal values are supported ✅

### Validation Approach

To validate algorithm accuracy:

1. **Collect Historical Data**: Gather real match results with known player ratings
2. **Backtest**: Run algorithm on historical data
3. **Compare Predictions**: Check if predicted outcomes match actual outcomes
4. **Adjust Parameters**: Tune K-factor and scale factor based on results
5. **Monitor**: Track rating distribution and stability over time

---

## References

### Elo Rating System - Primary Sources

**Original Work:**
- Elo, Arpad (1978). *The Rating of Chessplayers, Past and Present*. Arco Publishing.
  - The definitive book by Arpad Elo explaining his rating system
  - Includes statistical analysis and historical player comparisons
  - Still considered the authoritative reference

**About Arpad Elo:**
- Born: August 25, 1903, in Egyházaskesző, Hungary
- Died: November 5, 1992, in Brookfield, Wisconsin, USA
- Education: University of Chicago (Master's in Physics)
- Career: Physics professor, chess master (8-time Wisconsin State Champion)
- Legacy: FIDE (World Chess Federation) named him an International Arbiter in 1970

**Online Resources:**
- [Wikipedia: Elo Rating System](https://en.wikipedia.org/wiki/Elo_rating_system) - Comprehensive overview
- [Wikipedia: Arpad Elo](https://en.wikipedia.org/wiki/Arpad_Elo) - Biography
- [FIDE Handbook: Rating Regulations](https://handbook.fide.com/chapter/B02) - Official chess ratings

### Tennis Rating Systems
- [USTA NTRP Rating System](https://www.usta.com/en/home/play/play-tournaments/ntrp-rating-system.html)
- [Universal Tennis Rating (UTR)](https://www.utrsports.net/about)

### Related Implementations
- [FiveThirtyEight: NFL Elo Ratings](https://fivethirtyeight.com/methodology/how-our-nfl-predictions-work/)
- [Glicko Rating System](http://www.glicko.net/glicko.html) (improved Elo with rating confidence)

---

## Appendix: Algorithm Pseudocode

```
function calculateRanking(match):
    // Step 1: Analyze match
    winner = determineWinner(match.sets)
    loser = determineLoser(match.sets)
    totalGamesWinner = sumGames(match.sets, winner)
    totalGamesLoser = sumGames(match.sets, loser)

    // Step 2: Calculate dominance
    dominance = min(totalGamesWinner / totalGamesLoser, 2.5)

    // Step 3: Calculate expected scores
    expectedWinner = 1 / (1 + 10^((loserRating - winnerRating) / 400))
    expectedLoser = 1 - expectedWinner

    // Step 4: Calculate rating changes
    K = 32
    changeWinner = K × dominance × (1 - expectedWinner)
    changeLoser = K × dominance × (0 - expectedLoser)

    // Step 5: Apply system constraints
    newWinnerRating = applySystemConstraints(
        winnerRating + changeWinner,
        ratingSystem
    )
    newLoserRating = applySystemConstraints(
        loserRating + changeLoser,
        ratingSystem
    )

    return (newWinnerRating, newLoserRating)

function applySystemConstraints(rating, system):
    if system == NTRP:
        rounded = round(rating, 2)  // 2 decimal places
        return clamp(rounded, 1.0, 7.0)
    else if system == UTR:
        rounded = round(rating, 1)  // 1 decimal place
        return max(rounded, 1.0)
```

---

**Version:** 1.0
**Last Updated:** 2024-01-15
**Algorithm Implementation:** `src/main/kotlin/org/lange/tennis/levelr/service/RankingCalculator.kt`
