# USTA NTRP Dynamic Feature Comparison

> **Purpose**: Compare Skopeo against USTA's NTRP Dynamic rating system, tracking which USTA
> features Skopeo has adopted, has in the pipeline, or does not plan to build. This document is a
> roadmap study; it helps understand the differences between our implementation and USTA's
> comprehensive rating system. Skopeo is **NTRP-only** (UTR is not part of the product).

**Last Updated**: 2026-06-23

**Status legend used throughout:**
- ✅ **adopted** — built and shipped in Skopeo
- 🚧 **in pipeline** — partially built or actively planned
- ❌ **not planned** — no current intent to build

**Since the original study, Skopeo has shipped a persistence layer**, which unlocked several
features below. Notably built: admin-set/adjusted ratings, match fixtures + result upload, a
rating-calculation trigger that drives ratings from match results, append-only rating history,
rating confidence that converges with matches played, continuous (dynamic) ratings paired with
discrete published levels, a pending-assessment queue for administrators, and rating smoothing.

---

## Feature Status Summary

| Feature | USTA | Skopeo | Priority | Complexity |
|---------|------|---------------|----------|------------|
| [Match Type Weighting](#1-match-type-weighting) | ✅ | ❌ not planned | 🔴 High | Medium |
| [Doubles Support](#2-doubles-support) | ✅ | 🚧 in pipeline | 🟡 Medium | High |
| [Outlier Detection](#3-outlierstrikes-algorithm) | ✅ | ❌ not planned | 🟢 Low | Medium |
| [Minimum Match Requirements](#4-minimum-match-requirements) | ✅ | 🚧 in pipeline | 🟡 Medium | Low |
| [Rating Periods](#5-time-based-rating-periods) | ✅ | ❌ not planned | 🟢 Low | Medium |
| [Publication Delays](#6-rating-publication-delaysholds) | ✅ | ❌ not planned | 🟢 Low | Low |
| [Self-Rate Validation](#7-self-rate-validation) | ✅ | ❌ not planned | 🟡 Medium | Medium |
| [Disqualification System](#8-disqualification-dq-system) | ✅ | ❌ not planned | 🟡 Medium | Medium |
| [Benchmark Players](#9-benchmarkvalidation-players) | ✅ | ❌ not planned | 🟢 Low | High |
| [Appeal Process](#10-appeal-process) | ✅ | ❌ not planned | 🟢 Low | Medium |
| [Rating Confidence](#11-rating-confidencereliability-metrics) | ✅ | ✅ adopted | 🔴 High | Medium |
| [Regional Variations](#12-sectionregional-variations) | ✅ | ❌ not planned | 🟢 Low | Low |
| [Historical Tracking](#13-historical-data-and-trends) | ✅ | ✅ adopted | 🔴 High | High |
| [Dynamic vs Static Ratings](#14-dynamic-vs-static-ratings) | ✅ | ✅ adopted | 🟡 Medium | Medium |
| [Age Considerations](#15-age-and-experience-considerations) | ✅ | 🚧 in pipeline | 🟢 Low | Low |
| [Level Boundaries](#16-rating-boundaries-and-level-assignment) | ✅ | ✅ adopted | 🟡 Medium | Low |
| [Surface Adjustments](#17-surface-type-adjustments) | ✅ | ❌ not planned | 🟢 Low | Medium |
| **Admin-Assigned Ratings / Assessment** | ✅ | ✅ adopted | 🔴 High | Medium |
| **Match Results Drive Ratings** | ✅ | ✅ adopted | 🔴 High | High |
| **Rating Smoothing** | ✅ | ✅ adopted | - | - |

**Priority Legend:**
- 🔴 **High**: Valuable for most use cases, significant impact on rating quality
- 🟡 **Medium**: Useful for specific scenarios, moderate impact
- 🟢 **Low**: Nice-to-have, minimal impact or very specific use cases

**Complexity Legend:**
- **Low**: Can be implemented in a day or two
- **Medium**: Requires a week or two of development
- **High**: Major feature requiring significant architecture changes

---

## Detailed Feature Descriptions

### 1. Match Type Weighting

**Status**: ❌ Not Planned (for now) | **Priority**: 🔴 High | **Complexity**: Medium

> **Skopeo status**: Not built. Note that the `matchType` field on a stored `Match` denotes the
> *format* (SINGLES/DOUBLES, a `TeamType`), not a USTA-style league/tournament/playoff weight
> class. Fixtures do carry a free-text `tournamentName`, but no weighting is applied in the rating
> calculation. This remains a possible future enhancement, not currently planned.

#### What USTA Does

USTA applies different weights to matches based on their type and context:

- **League Matches**: 100% weight (official competitive play)
- **Tournament Matches**: Variable weight based on tournament level
  - Local tournaments: ~75% weight
  - Sectional tournaments: ~90% weight
  - National tournaments: 100% weight
- **Playoff Matches**: Often 110-120% weight (higher stakes)
- **Practice/Casual Matches**: Not included in rating calculations

**Weight Formula Example**:
```
adjustedChange = baseChange × matchTypeWeight
```

#### Why USTA Does This

1. **Prioritize competitive results**: Official matches better reflect true skill
2. **Reduce gaming**: Casual matches can't be used to manipulate ratings
3. **Context matters**: High-stakes matches should count more
4. **Quality control**: Only validated, official matches affect ratings

#### Why This Is Valuable

- **Prevents rating manipulation**: Players can't inflate ratings with casual wins
- **Better accuracy**: Competitive matches are more reliable indicators
- **Flexible system**: Can accommodate different match contexts
- **Encourages serious play**: Official matches matter more

#### Implementation Considerations

**Database Schema**:
```kotlin
enum class MatchType {
    LEAGUE,
    TOURNAMENT,
    PLAYOFF,
    PRACTICE,
    CASUAL
}

data class Match(
    val matchType: MatchType,
    val typeWeight: Double = 1.0,
    val tournamentLevel: TournamentLevel? = null,
    // ... other fields
)
```

**Algorithm Changes**:
```kotlin
fun calculateRatingChange(
    baseChange: BigDecimal,
    matchType: MatchType
): BigDecimal {
    val weight = when (matchType) {
        MatchType.LEAGUE -> 1.0
        MatchType.TOURNAMENT -> 0.75
        MatchType.PLAYOFF -> 1.2
        MatchType.PRACTICE -> 0.0 // Not counted
        MatchType.CASUAL -> 0.0
    }
    return baseChange * weight.bd
}
```

**API Changes**:
```json
{
  "matchMetadata": {
    "matchType": "LEAGUE",
    "tournamentLevel": "SECTIONAL",
    "officialMatch": true
  }
}
```

**Challenges**:
- Requires match metadata storage
- Need clear definitions for each match type
- Potential for disagreement about appropriate weights
- Must handle edge cases (exhibition matches, charity events, etc.)

**Estimated Effort**: 1-2 weeks
- Add match type to data model
- Implement weighting logic
- Update API to accept match type
- Add validation and tests
- Document weight policies

---

### 2. Doubles Support

**Status**: 🚧 In Pipeline | **Priority**: 🟡 Medium | **Complexity**: High

> **Skopeo status**: Schema-ready, not yet calculable. The data model is intentionally
> team-based (`Team`/`TeamType` with SINGLES/DOUBLES; matches store two `MatchSide`s each with a
> list of user IDs), so doubles can be added without breaking the schema. The rating calculator
> currently validates and supports SINGLES only. The partner-quality algorithm below is the
> remaining work.

#### What USTA Does

USTA maintains **separate ratings** for singles and doubles:

**Partner Quality Adjustment**:
- When playing doubles, rating adjusts for partner strength
- Stronger partner → smaller change for wins, larger penalty for losses
- Weaker partner → larger reward for wins, smaller penalty for losses

**Formula** (simplified):
```
partnerAdjustment = (myRating - partnerRating) / 2
adjustedTeamRating = myRating + partnerAdjustment
```

**Example**:
- Player A (4.5 NTRP) + Player B (4.0 NTRP) vs Team C (4.25 avg)
- Player A's team rating: 4.5 + (4.5-4.0)/2 = 4.5 + 0.25 = 4.75
- Player B's team rating: 4.0 + (4.0-4.5)/2 = 4.0 - 0.25 = 3.75
- If they win, Player A gains less (carried by average partner), Player B gains more (exceeded with weaker rating)

#### Why USTA Does This

1. **Different skills**: Doubles requires different tactics (net play, positioning, teamwork)
2. **Partner influence**: Strong partner can carry weaker player
3. **Fair assessment**: Can't just average partner ratings
4. **Separate progression**: Players may be better at singles or doubles

#### Why This Is Valuable

- **Accurate representation**: Many players specialize in one format
- **Fair competition**: Accounts for team dynamics
- **Broader coverage**: Most recreational tennis is doubles
- **Player choice**: Players can focus on format they prefer

#### Implementation Considerations

**Data Model**:
```kotlin
enum class MatchFormat {
    SINGLES,
    DOUBLES,
    MIXED_DOUBLES
}

data class PlayerRatings(
    val playerId: String,
    val singlesRating: Rating,
    val doublesRating: Rating,
    val mixedDoublesRating: Rating? = null
)

data class DoublesMatch(
    val team1Player1: PlayerProfile,
    val team1Player2: PlayerProfile,
    val team2Player1: PlayerProfile,
    val team2Player2: PlayerProfile,
    val score: MatchScore
)
```

**Partner Adjustment Algorithm**:
```kotlin
fun calculateDoublesRatingChange(
    player: PlayerProfile,
    partner: PlayerProfile,
    opponents: Pair<PlayerProfile, PlayerProfile>,
    score: MatchScore,
    isWinner: Boolean
): RatingChange {
    // Calculate team ratings
    val myTeamRating = (player.rating + partner.rating) / 2
    val opponentTeamRating = (opponents.first.rating + opponents.second.rating) / 2

    // Partner adjustment
    val partnerDiff = player.rating - partner.rating
    val partnerAdjustment = partnerDiff / 2

    // Adjusted individual contribution
    val adjustedPlayerRating = player.rating + partnerAdjustment

    // Calculate base change using adjusted rating
    val baseChange = calculateEloChange(
        adjustedPlayerRating,
        opponentTeamRating,
        score,
        isWinner
    )

    // Apply partner influence factor
    val partnerInfluenceFactor = calculatePartnerInfluence(partnerDiff)
    return baseChange * partnerInfluenceFactor
}

fun calculatePartnerInfluence(ratingDiff: Double): Double {
    // Stronger partner → reduce my credit for wins
    // Weaker partner → increase my credit for wins
    return when {
        abs(ratingDiff) < 0.3 -> 1.0 // Similar strength
        ratingDiff > 0 -> 0.7 // I'm stronger (carried partner)
        else -> 1.3 // I'm weaker (exceeded expectations)
    }
}
```

**API Design**:
```json
{
  "matchFormat": "DOUBLES",
  "team1": {
    "player1": {
      "playerId": "P1",
      "rating": {"value": "4.5", "publishedLevel": "4.5"}
    },
    "player2": {
      "playerId": "P2",
      "rating": {"value": "4.0", "publishedLevel": "4.0"}
    }
  },
  "team2": {
    "player1": {...},
    "player2": {...}
  },
  "matchScore": {...},
  "winningTeam": 1
}
```

**Challenges**:
- **Complex algorithm**: Need to fairly distribute credit/blame
- **Data model changes**: Support teams instead of just individual players
- **Rating storage**: Separate singles/doubles ratings per player
- **Edge cases**:
  - What if partner is unrated?
  - How to handle mixed doubles (male + female)?
  - Different partner every match?
- **Testing complexity**: Many more scenarios to test

**Estimated Effort**: 3-4 weeks
- Design and implement team-based data model
- Implement partner quality adjustment algorithm
- Create separate rating tracking for singles/doubles
- Extensive testing of edge cases
- API changes and documentation

---

### 3. Outlier/Strikes Algorithm

**Status**: ❌ Not Implemented | **Priority**: 🟢 Low | **Complexity**: Medium

#### What USTA Does

USTA automatically identifies and "strikes" (removes) matches that are statistical outliers:

**Strike Criteria** (estimated):
1. **Injury/Retirement**: Player retires early (< 3 games played)
2. **Extreme Deviation**: Result differs significantly from expected based on ratings
   - Example: 5.0 player loses 0-6, 0-6 to 3.5 player (unusual if not injured)
3. **Non-Competitive**: Score indicates one player didn't try
   - Example: Default, walkover, or extremely lopsided (0-6, 0-6 in under 30 minutes)
4. **Equipment/Court Issues**: Match affected by external factors

**Statistical Outlier Detection**:
```
deviation = actualResult - expectedResult
if (abs(deviation) > 3 * standardDeviation) {
    flagForReview()
}
```

#### Why USTA Does This

1. **Rating accuracy**: Anomalous matches don't reflect true skill
2. **Injury protection**: Players aren't penalized for playing injured
3. **Gaming prevention**: Can't manipulate ratings by tanking
4. **Fairness**: External factors shouldn't permanently affect ratings

#### Why This Is Valuable

- **Protects against anomalies**: One bad day doesn't ruin rating
- **Data quality**: Improves overall rating reliability
- **Player confidence**: Know ratings reflect actual ability
- **Statistical validity**: Removes noise from the system

#### Implementation Considerations

**Detection Algorithm**:
```kotlin
data class MatchOutlierAnalysis(
    val isOutlier: Boolean,
    val reason: OutlierReason?,
    val confidence: Double,
    val deviation: BigDecimal
)

enum class OutlierReason {
    EARLY_RETIREMENT,
    EXTREME_SCORE_DEVIATION,
    NON_COMPETITIVE,
    SUSPECTED_TANKING,
    INJURY_REPORTED
}

fun detectOutlier(
    player: PlayerProfile,
    opponent: PlayerProfile,
    score: MatchScore,
    matchDuration: Duration?,
    retirementFlag: Boolean
): MatchOutlierAnalysis {
    // Check for early retirement
    if (retirementFlag && score.totalGames < 5) {
        return MatchOutlierAnalysis(
            isOutlier = true,
            reason = OutlierReason.EARLY_RETIREMENT,
            confidence = 0.95,
            deviation = BigDecimal.ZERO
        )
    }

    // Calculate expected result
    val ratingDiff = player.rating.value.toDouble() - opponent.rating.value.toDouble()
    val expectedProbability = calculateWinProbability(ratingDiff)
    val actualResult = if (score.winner == player.id) 1.0 else 0.0

    // Calculate dominance
    val dominance = calculateDominance(score)

    // Check for extreme deviation
    val deviation = abs(actualResult - expectedProbability)
    if (deviation > 0.8 && abs(dominance) > 0.9) {
        return MatchOutlierAnalysis(
            isOutlier = true,
            reason = OutlierReason.EXTREME_SCORE_DEVIATION,
            confidence = 0.85,
            deviation = deviation.bd
        )
    }

    // Check for non-competitive play (very short match with lopsided score)
    if (matchDuration != null &&
        matchDuration < Duration.ofMinutes(30) &&
        abs(dominance) > 0.95) {
        return MatchOutlierAnalysis(
            isOutlier = true,
            reason = OutlierReason.NON_COMPETITIVE,
            confidence = 0.75,
            deviation = BigDecimal.ZERO
        )
    }

    return MatchOutlierAnalysis(
        isOutlier = false,
        reason = null,
        confidence = 0.0,
        deviation = BigDecimal.ZERO
    )
}
```

**Strike Policy**:
```kotlin
fun shouldStrikeMatch(analysis: MatchOutlierAnalysis): Boolean {
    return when {
        analysis.confidence > 0.9 -> true  // High confidence outlier
        analysis.confidence > 0.7 && analysis.reason == OutlierReason.EARLY_RETIREMENT -> true
        else -> false
    }
}

fun calculateRatingWithOutlierDetection(
    match: Match,
    matchDuration: Duration?,
    retirementFlag: Boolean
): RatingCalculationResult {
    val analysis = detectOutlier(
        match.player1,
        match.player2,
        match.score,
        matchDuration,
        retirementFlag
    )

    if (shouldStrikeMatch(analysis)) {
        return RatingCalculationResult(
            ratingChanges = emptyMap(), // No rating change
            struck = true,
            strikeReason = analysis.reason,
            audit = listOf(
                AuditEntry("Match struck as outlier: ${analysis.reason}")
            )
        )
    }

    // Normal rating calculation
    return calculator.calculate(match)
}
```

**API Changes**:
```json
{
  "matchMetadata": {
    "duration": "PT45M",
    "retirement": false,
    "injuryReported": false,
    "completionStatus": "COMPLETED"
  }
}
```

**Response with Strike Info**:
```json
{
  "ratingChanges": {},
  "struck": true,
  "strikeReason": "EARLY_RETIREMENT",
  "strikeConfidence": 0.95,
  "message": "Match identified as statistical outlier and excluded from rating calculation"
}
```

**Challenges**:
- **False positives**: Legitimate upsets might be flagged
- **Threshold tuning**: What constitutes an "outlier"?
- **Historical data needed**: Need baseline statistics for deviation
- **Appeals**: Players may want to contest strikes
- **Complexity**: Statistical analysis adds significant complexity

**Estimated Effort**: 2 weeks
- Implement outlier detection algorithm
- Add match metadata (duration, retirement status)
- Create strike policy logic
- Extensive testing with edge cases
- Documentation and tuning

---

### 4. Minimum Match Requirements

**Status**: 🚧 In Pipeline | **Priority**: 🟡 Medium | **Complexity**: Low

> **Skopeo status**: Partial. The persistence layer already tracks `matchesPlayed` per user and a
> `confidence` value that starts low and converges as matches accumulate (`UserRating`). What is
> not yet built is a *publish threshold* that withholds a rating until a minimum match count is
> reached. The foundation exists; gating publication on it is the remaining work.

#### What USTA Does

USTA requires players to complete a **minimum number of matches** before their dynamic rating is published:

**Typical Requirements**:
- **Computer-rated players**: 2-3 matches minimum
- **Self-rated players**: 3-5 matches minimum
- **New players**: May need more matches for initial rating

**Rating Display**:
- Before threshold: "M" (Mixed) rating or no rating shown
- After threshold: Dynamic rating published

**Rationale**:
- Early matches are highly variable
- More data = more reliable rating
- Prevents premature judgments

#### Why USTA Does This

1. **Statistical validity**: Small sample size is unreliable
2. **Prevents gaming**: Can't check rating after one match
3. **Player protection**: Don't publish potentially inaccurate early rating
4. **System stability**: Reduces volatility from limited data

#### Why This Is Valuable

- **Better accuracy**: More data points = more confidence
- **Fair representation**: Don't judge players on 1-2 matches
- **Reduced volatility**: Prevents wild rating swings early
- **Gaming prevention**: Can't exploit single matches

#### Implementation Considerations

**Data Model**:
```kotlin
data class PlayerRatingRecord(
    val playerId: String,
    val currentRating: Rating,
    val matchCount: Int,
    val ratingStatus: RatingStatus
)

enum class RatingStatus {
    PROVISIONAL,      // < minimum matches
    ESTABLISHED,      // >= minimum matches
    INACTIVE,         // No recent matches
    APPEALED          // Under review
}

data class RatingRequirements(
    val minimumMatches: Int = 3,
    val publishThreshold: Int = 3,
    val confidenceRequirement: Double = 0.7
)
```

**Rating Calculation Logic**:
```kotlin
fun calculateRatingWithConfidence(
    player: PlayerRatingRecord,
    newMatch: Match,
    requirements: RatingRequirements
): RatingUpdate {
    // Calculate new rating
    val newRating = performRatingCalculation(player.currentRating, newMatch)
    val newMatchCount = player.matchCount + 1

    // Determine if rating should be published
    val shouldPublish = newMatchCount >= requirements.publishThreshold

    // Calculate confidence based on match count
    val confidence = calculateConfidence(newMatchCount, requirements)

    val newStatus = when {
        newMatchCount < requirements.minimumMatches -> RatingStatus.PROVISIONAL
        confidence >= requirements.confidenceRequirement -> RatingStatus.ESTABLISHED
        else -> RatingStatus.PROVISIONAL
    }

    return RatingUpdate(
        rating = newRating,
        matchCount = newMatchCount,
        status = newStatus,
        confidence = confidence,
        published = shouldPublish
    )
}

fun calculateConfidence(matchCount: Int, requirements: RatingRequirements): Double {
    // Confidence increases with match count
    return when {
        matchCount < requirements.minimumMatches ->
            matchCount.toDouble() / requirements.minimumMatches * 0.5
        matchCount < requirements.minimumMatches * 2 ->
            0.5 + (matchCount - requirements.minimumMatches).toDouble() /
                  requirements.minimumMatches * 0.3
        else -> 0.8 + min(0.2, (matchCount - requirements.minimumMatches * 2) * 0.01)
    }.coerceIn(0.0, 1.0)
}
```

**API Response**:
```json
{
  "ratingChanges": {
    "P1": {
      "newRating": {
        "value": "4.23",
        "publishedLevel": "4.0"
      },
      "change": "+0.15",
      "matchCount": 2,
      "status": "PROVISIONAL",
      "confidence": 0.40,
      "published": false,
      "message": "Rating not published. 1 more match required (minimum: 3)"
    }
  }
}
```

**Storage Requirements**:
- Need to track match count per player
- Store rating status (provisional vs established)
- Maintain confidence scores

**Challenges**:
- **Requires persistence**: Must track player history
- **Initial rating**: How to handle very first match?
- **Threshold tuning**: What's the right minimum?
- **Communication**: Players need to know why rating isn't published

**Estimated Effort**: 3-5 days
- Add match counting to data model
- Implement confidence calculation
- Add status field to ratings
- Update API to return status info
- Documentation

---

### 5. Time-Based Rating Periods

**Status**: ❌ Not Implemented | **Priority**: 🟢 Low | **Complexity**: Medium

#### What USTA Does

USTA calculates ratings on a **scheduled basis** rather than real-time:

**Rating Calculation Schedule**:
- **Nightly updates**: Dynamic ratings recalculated each night
- **Weekly snapshots**: Ratings frozen at specific points
- **Year-end ratings**: Final rating for the year becomes baseline for next year
- **Championship year**: Ratings tied to specific year (e.g., "2024C")

**Rating Types by Time**:
- **ESR (Early Start Rating)**: Rating at start of year
- **Dynamic Rating**: Current rating during year
- **YER (Year-End Rating)**: Final rating at end of championship year

**Blackout Periods**:
- Ratings may be frozen during:
  - Championship events
  - National tournaments
  - Administrative review periods

#### Why USTA Does This

1. **Administrative simplicity**: Batch processing is easier to manage
2. **Gaming prevention**: Can't check rating constantly to exploit system
3. **Stability**: Gives players predictable rating update schedule
4. **Registration consistency**: Ratings stable during signup periods
5. **Business logic**: Ties to USTA's annual championship structure

#### Why This Might Be Valuable

- **Predictability**: Players know when ratings update
- **Batch optimization**: Can run expensive calculations overnight
- **Gaming prevention**: Reduces ability to manipulate ratings
- **Administrative control**: Can freeze/hold ratings when needed

#### Implementation Considerations

**Data Model**:
```kotlin
data class RatingPeriod(
    val periodId: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val periodType: PeriodType,
    val status: PeriodStatus
)

enum class PeriodType {
    DAILY,
    WEEKLY,
    MONTHLY,
    CHAMPIONSHIP_YEAR,
    YEAR_END
}

enum class PeriodStatus {
    ACTIVE,
    CALCULATING,
    FROZEN,
    COMPLETED
}

data class ScheduledRatingUpdate(
    val playerId: String,
    val scheduledFor: Instant,
    val matches: List<Match>,
    val currentRating: Rating,
    val calculatedRating: Rating?,
    val published: Boolean
)
```

**Batch Calculation System**:
```kotlin
class RatingBatchProcessor(
    val schedule: RatingUpdateSchedule,
    val calculator: RankingCalculator
) {
    suspend fun processDailyUpdates(date: LocalDate) {
        log.info("Starting daily rating update for $date")

        // Get all matches from previous day
        val matches = matchRepository.getMatchesByDate(date.minusDays(1))

        // Group matches by player
        val matchesByPlayer = matches.groupBy { it.playerId }

        // Calculate new ratings for each player
        val updates = matchesByPlayer.mapNotNull { (playerId, playerMatches) ->
            try {
                calculatePlayerRatingUpdate(playerId, playerMatches)
            } catch (e: Exception) {
                log.error("Failed to update rating for player $playerId", e)
                null
            }
        }

        // Publish ratings
        ratingRepository.batchPublishRatings(updates)

        log.info("Completed daily rating update: ${updates.size} players updated")
    }

    private fun calculatePlayerRatingUpdate(
        playerId: String,
        matches: List<Match>
    ): RatingUpdate {
        val currentRating = ratingRepository.getCurrentRating(playerId)

        // Apply all matches sequentially
        var rating = currentRating
        matches.sortedBy { it.date }.forEach { match ->
            val result = calculator.calculate(createRequest(rating, match))
            rating = result.response.ratingChanges[playerId]?.newRating ?: rating
        }

        return RatingUpdate(
            playerId = playerId,
            oldRating = currentRating,
            newRating = rating,
            matchCount = matches.size,
            calculatedAt = Instant.now()
        )
    }
}

// Cron-style scheduler
@Scheduled(cron = "0 0 2 * * *") // 2 AM daily
fun scheduledRatingUpdate() {
    ratingBatchProcessor.processDailyUpdates(LocalDate.now())
}
```

**Rating Query API**:
```kotlin
@GET("/api/v1/players/{playerId}/rating")
fun getPlayerRating(
    playerId: String,
    @Query("asOf") asOfDate: LocalDate? = null,
    @Query("periodType") periodType: PeriodType? = null
): PlayerRating {
    return when {
        asOfDate != null -> ratingRepository.getRatingAsOf(playerId, asOfDate)
        periodType != null -> ratingRepository.getRatingForPeriod(playerId, periodType)
        else -> ratingRepository.getCurrentRating(playerId)
    }
}
```

**Challenges**:
- **Architecture change**: Move from stateless to scheduled processing
- **Infrastructure**: Need job scheduler (cron, scheduled tasks)
- **State management**: Track which matches have been processed
- **Idempotency**: Handle re-runs of batch jobs
- **Real-time vs batch**: Conflicts with current instant calculation model
- **Testing**: Harder to test scheduled/batch processes

**Trade-offs**:
- ❌ **Lose**: Real-time feedback
- ❌ **Lose**: Stateless simplicity
- ✅ **Gain**: Administrative control
- ✅ **Gain**: Gaming prevention
- ✅ **Gain**: Batch optimization opportunities

**Estimated Effort**: 1-2 weeks
- Design batch processing architecture
- Implement scheduling system
- Add period/date-based rating queries
- State management for processed matches
- Testing batch processes

**Note**: This fundamentally changes the architecture from stateless calculation to stateful batch processing. May not be desirable for all use cases.

---

### 6. Rating Publication Delays/Holds

**Status**: ❌ Not Implemented | **Priority**: 🟢 Low | **Complexity**: Low

#### What USTA Does

USTA can **delay or hold** rating publication for various reasons:

**Hold Scenarios**:
1. **Administrative Review**: Rating flagged for manual review
2. **Appeal in Progress**: Player appealed rating, pending decision
3. **Disqualification Investigation**: Checking for sandbagging
4. **Blackout Periods**: During championships or key events
5. **Data Quality Issues**: Match results need verification
6. **Section Coordinator Hold**: Manual hold by administrator

**During Hold**:
- Rating calculated but not published
- Player sees "Rating Under Review" or similar message
- Can't register for new leagues until resolved

#### Why USTA Does This

1. **Quality control**: Verify accuracy before publishing
2. **Fair process**: Allow appeals to be resolved
3. **Investigation time**: Review suspected violations
4. **Administrative oversight**: Human review when needed
5. **Event fairness**: Prevent mid-tournament rating changes

#### Why This Might Be Valuable

- **Data quality**: Catch errors before publishing
- **Player protection**: Don't publish incorrect ratings
- **Administrative control**: Override automated system when needed
- **Appeals support**: Time to resolve disputes

#### Implementation Considerations

**Data Model**:
```kotlin
data class RatingHold(
    val holdId: String,
    val playerId: String,
    val reason: HoldReason,
    val initiatedBy: String,
    val initiatedAt: Instant,
    val status: HoldStatus,
    val notes: String?,
    val resolvedAt: Instant? = null,
    val resolution: String? = null
)

enum class HoldReason {
    ADMINISTRATIVE_REVIEW,
    APPEAL_IN_PROGRESS,
    DQ_INVESTIGATION,
    BLACKOUT_PERIOD,
    DATA_QUALITY_ISSUE,
    MANUAL_HOLD
}

enum class HoldStatus {
    ACTIVE,
    UNDER_REVIEW,
    RESOLVED_APPROVED,
    RESOLVED_REJECTED,
    EXPIRED
}

data class PlayerRatingWithHold(
    val rating: Rating,
    val published: Boolean,
    val hold: RatingHold?,
    val canCompete: Boolean
)
```

**Hold Management**:
```kotlin
class RatingHoldService(
    val holdRepository: RatingHoldRepository,
    val notificationService: NotificationService
) {
    fun placeHold(
        playerId: String,
        reason: HoldReason,
        initiatedBy: String,
        notes: String?
    ): RatingHold {
        val hold = RatingHold(
            holdId = generateId(),
            playerId = playerId,
            reason = reason,
            initiatedBy = initiatedBy,
            initiatedAt = Instant.now(),
            status = HoldStatus.ACTIVE,
            notes = notes
        )

        holdRepository.save(hold)

        // Notify player
        notificationService.notifyRatingHold(playerId, hold)

        return hold
    }

    fun releaseHold(
        holdId: String,
        resolution: String,
        approvedBy: String
    ): RatingHold {
        val hold = holdRepository.findById(holdId)

        val updated = hold.copy(
            status = HoldStatus.RESOLVED_APPROVED,
            resolvedAt = Instant.now(),
            resolution = resolution
        )

        holdRepository.update(updated)

        // Publish rating
        ratingService.publishRating(hold.playerId)

        // Notify player
        notificationService.notifyHoldReleased(hold.playerId, updated)

        return updated
    }

    fun shouldPublishRating(playerId: String): Boolean {
        val activeHolds = holdRepository.findActiveHoldsByPlayer(playerId)
        return activeHolds.isEmpty()
    }
}
```

**API Integration**:
```kotlin
fun calculateRatingWithHoldCheck(
    request: RankingCalculationRequest
): RankingCalculationResult {
    // Calculate rating normally
    val result = calculator.calculate(request)

    // Check for holds on each player
    val publishableChanges = result.response.ratingChanges.mapValues { (playerId, change) ->
        val hasHold = !ratingHoldService.shouldPublishRating(playerId)

        change.copy(
            published = !hasHold,
            hold = if (hasHold) ratingHoldRepository.findActiveHoldsByPlayer(playerId).firstOrNull() else null
        )
    }

    return result.copy(
        response = result.response.copy(
            ratingChanges = publishableChanges
        )
    )
}
```

**API Response**:
```json
{
  "ratingChanges": {
    "P1": {
      "newRating": {"value": "4.75", "publishedLevel": "4.5"},
      "change": "+0.15",
      "published": false,
      "hold": {
        "reason": "APPEAL_IN_PROGRESS",
        "initiatedAt": "2024-06-01T10:00:00Z",
        "status": "UNDER_REVIEW",
        "message": "Rating publication on hold pending appeal review"
      }
    }
  }
}
```

**Admin API**:
```kotlin
// Place hold
POST /api/v1/admin/rating-holds
{
  "playerId": "P1",
  "reason": "ADMINISTRATIVE_REVIEW",
  "notes": "Unusual rating spike, reviewing match history"
}

// Release hold
POST /api/v1/admin/rating-holds/{holdId}/release
{
  "resolution": "Reviewed and approved",
  "approvedBy": "admin123"
}

// Query holds
GET /api/v1/admin/rating-holds?status=ACTIVE
```

**Challenges**:
- **Administrative overhead**: Need tools to manage holds
- **Notification system**: Must inform players of holds
- **Appeals process**: Need workflow for reviewing holds
- **Database**: Store hold records and history
- **Permissions**: Role-based access for hold management

**Estimated Effort**: 3-5 days
- Implement hold data model
- Create hold management service
- Add admin API endpoints
- Integrate with rating calculation
- Notification system
- Documentation

---

### 7. Self-Rate Validation

**Status**: ❌ Not Planned | **Priority**: 🟡 Medium | **Complexity**: Medium

> **Skopeo status**: Not built, and the premise differs from Skopeo's model. Skopeo does not let
> players self-rate; initial ratings are **admin-assigned**. A new user with no rating appears in a
> pending-assessment queue (`PendingAssessment`), and an administrator sets the rating via
> `PUT /api/v1/users/{userId}/ratings` (`SetRatingRequest`). Because there is no self-rating, the
> sandbagging-detection machinery USTA layers on self-rated players is not applicable as designed.
> (See also the new "Admin-Assigned Ratings / Assessment" entry in the summary table — that path
> is ✅ adopted.)

#### What USTA Does

USTA distinguishes between **self-rated** (S) and **computer-rated** (C) players:

**Self-Rated Players**:
- New to USTA or returning after absence
- Choose their own initial NTRP level
- Subject to stricter scrutiny
- Can be disqualified more easily

**Computer-Rated Players**:
- Have established rating history
- Rating based on match results
- More stable, less prone to gaming

**Self-Rate Validation**:
1. **Three strikes rule**: Three losses to C-rated players at same level = DQ
2. **Stricter thresholds**: Lower tolerance for dominating wins
3. **Automatic bumps**: Can be bumped up mid-season if dominating
4. **Appeals limited**: Harder to appeal self-rated DQs

#### Why USTA Does This

1. **Prevent sandbagging**: Players deliberately rating themselves too low
2. **Protect leagues**: Ensure fair competition
3. **Verification period**: Test self-assessment with actual results
4. **Gaming prevention**: Harder to exploit self-rating

#### Why This Is Valuable

- **Fair play**: Prevents deliberate misrating
- **League integrity**: Protects competitive balance
- **Player honesty incentive**: Encourages accurate self-assessment
- **Gaming prevention**: Makes manipulation harder

#### Implementation Considerations

**Data Model**:
```kotlin
enum class RatingSource {
    COMPUTER_CALCULATED,    // Based on match history
    SELF_RATED,            // Player self-assessment
    ADMIN_ASSIGNED,        // Assigned by administrator
    APPEALED,              // Result of successful appeal
    BENCHMARK              // Known benchmark player
}

data class PlayerProfile(
    val playerId: String,
    val rating: Rating,
    val ratingSource: RatingSource,
    val selfRateDate: LocalDate?,
    val validationStatus: ValidationStatus,
    val strikes: Int = 0,
    val matchHistory: List<MatchResult> = emptyList()
)

enum class ValidationStatus {
    PENDING_VALIDATION,     // New self-rate, < 3 matches
    UNDER_REVIEW,          // Potential issues detected
    VALIDATED,             // Passed validation checks
    DISQUALIFIED           // Failed validation, DQ'd
}

data class SelfRateValidationResult(
    val valid: Boolean,
    val issues: List<ValidationIssue>,
    val recommendedAction: ValidationAction,
    val confidence: Double
)

enum class ValidationAction {
    NO_ACTION,
    FLAG_FOR_REVIEW,
    AUTOMATIC_BUMP,
    DISQUALIFY
}

data class ValidationIssue(
    val type: IssueType,
    val severity: IssueSeverity,
    val description: String,
    val matchIds: List<String>
)

enum class IssueType {
    DOMINATING_WINS,
    CONSISTENT_BLOWOUTS,
    RATING_TOO_LOW,
    SUSPICIOUS_PATTERN
}

enum class IssueSeverity {
    INFO,
    WARNING,
    CRITICAL
}
```

**Validation Algorithm**:
```kotlin
class SelfRateValidator(
    val thresholds: ValidationThresholds
) {
    fun validateSelfRatedPlayer(player: PlayerProfile): SelfRateValidationResult {
        require(player.ratingSource == RatingSource.SELF_RATED) {
            "Can only validate self-rated players"
        }

        val issues = mutableListOf<ValidationIssue>()

        // Check 1: Dominating wins pattern
        val dominatingWins = player.matchHistory.count { match ->
            match.isWinner && calculateDominance(match.score) > 0.85
        }

        if (dominatingWins >= thresholds.maxDominatingWins) {
            issues.add(ValidationIssue(
                type = IssueType.DOMINATING_WINS,
                severity = IssueSeverity.CRITICAL,
                description = "Player has $dominatingWins dominating wins, threshold: ${thresholds.maxDominatingWins}",
                matchIds = player.matchHistory.filter { it.isWinner }.map { it.matchId }
            ))
        }

        // Check 2: Consistent blowouts against computer-rated players
        val blowoutsVsComputer = player.matchHistory.count { match ->
            match.opponentRatingSource == RatingSource.COMPUTER_CALCULATED &&
            match.isWinner &&
            calculateDominance(match.score) > 0.75
        }

        if (blowoutsVsComputer >= 3) {  // USTA's "three strikes"
            issues.add(ValidationIssue(
                type = IssueType.CONSISTENT_BLOWOUTS,
                severity = IssueSeverity.CRITICAL,
                description = "Three or more blowout wins against computer-rated players",
                matchIds = player.matchHistory.map { it.matchId }
            ))
        }

        // Check 3: Rating trajectory analysis
        val calculatedRating = calculateTrueRating(player.matchHistory, player.rating)
        val ratingGap = calculatedRating - player.rating.value.toDouble()

        if (ratingGap > thresholds.maxRatingGap) {
            issues.add(ValidationIssue(
                type = IssueType.RATING_TOO_LOW,
                severity = IssueSeverity.WARNING,
                description = "Calculated rating ($calculatedRating) exceeds self-rating by $ratingGap",
                matchIds = emptyList()
            ))
        }

        // Determine action
        val recommendedAction = when {
            issues.any { it.severity == IssueSeverity.CRITICAL && it.type == IssueType.CONSISTENT_BLOWOUTS } ->
                ValidationAction.DISQUALIFY
            issues.any { it.severity == IssueSeverity.CRITICAL } ->
                ValidationAction.AUTOMATIC_BUMP
            issues.any { it.severity == IssueSeverity.WARNING } ->
                ValidationAction.FLAG_FOR_REVIEW
            else ->
                ValidationAction.NO_ACTION
        }

        return SelfRateValidationResult(
            valid = recommendedAction == ValidationAction.NO_ACTION,
            issues = issues,
            recommendedAction = recommendedAction,
            confidence = calculateConfidence(issues, player.matchHistory.size)
        )
    }

    private fun calculateTrueRating(
        matchHistory: List<MatchResult>,
        startingRating: Rating
    ): Double {
        // Use our rating calculator to project what rating should be
        var rating = startingRating.value.toDouble()

        matchHistory.forEach { match ->
            val change = estimateRatingChange(rating, match)
            rating += change
        }

        return rating
    }
}

data class ValidationThresholds(
    val maxDominatingWins: Int = 3,
    val maxRatingGap: Double = 0.5,
    val dominanceThreshold: Double = 0.85,
    val blowoutThreshold: Double = 0.75
)
```

**Integration with Rating Calculation**:
```kotlin
fun calculateRatingWithValidation(
    request: RankingCalculationRequest,
    player: PlayerProfile
): RankingCalculationResult {
    // Calculate rating normally
    val result = calculator.calculate(request)

    // If player is self-rated, validate after each match
    if (player.ratingSource == RatingSource.SELF_RATED) {
        val updatedHistory = player.matchHistory + MatchResult(request)
        val updatedPlayer = player.copy(matchHistory = updatedHistory)

        val validation = validator.validateSelfRatedPlayer(updatedPlayer)

        when (validation.recommendedAction) {
            ValidationAction.DISQUALIFY -> {
                return result.copy(
                    disqualified = true,
                    disqualificationReason = "Failed self-rate validation",
                    validationIssues = validation.issues
                )
            }
            ValidationAction.AUTOMATIC_BUMP -> {
                val bumpedRating = player.rating.value.toDouble() + 0.5
                // Trigger bump process
            }
            ValidationAction.FLAG_FOR_REVIEW -> {
                // Send notification to administrators
            }
            else -> {}
        }
    }

    return result
}
```

**API Response with Validation**:
```json
{
  "ratingChanges": {
    "P1": {
      "newRating": {"value": "4.35", "publishedLevel": "4.0"},
      "change": "+0.15",
      "ratingSource": "SELF_RATED",
      "validationStatus": "UNDER_REVIEW",
      "validationIssues": [
        {
          "type": "DOMINATING_WINS",
          "severity": "WARNING",
          "description": "Player has 2 dominating wins, approaching threshold of 3"
        }
      ],
      "strikes": 2,
      "message": "Self-rated player validation in progress"
    }
  }
}
```

**Challenges**:
- **Database**: Track rating source and match history per player
- **Threshold tuning**: What constitutes "too dominant"?
- **False positives**: Legitimately improving players might be flagged
- **Administrative overhead**: Need process for reviewing flags
- **Player communication**: Must explain validation process

**Estimated Effort**: 1-2 weeks
- Implement validation algorithm
- Add rating source tracking
- Create threshold configuration
- Build notification system
- Admin review interface
- Testing edge cases

---

### 8. Disqualification (DQ) System

**Status**: ❌ Not Implemented | **Priority**: 🟡 Medium | **Complexity**: Medium

#### What USTA Does

USTA has an **automatic disqualification system** to maintain competitive balance:

**Dynamic Disqualification (DQ)**:
- **Three strikes at a level**: Three dominant wins can trigger DQ
- **Strike thresholds**: Based on rating thresholds above level
- **Automatic bump**: DQ'd player moved up to next level
- **Mid-season enforcement**: Can happen during active play
- **Section rules**: Some sections have stricter DQ policies

**Strike Examples (NTRP)**:
- 4.0 player whose dynamic rating exceeds 4.50 gets "strike"
- Three strikes = DQ from 4.0, bumped to 4.5
- Wins by score (6-0, 6-0) more likely to trigger strikes

**Consequences**:
- Can't play at lower level for rest of year
- Team may forfeit matches where DQ'd player competed
- Must register at higher level going forward

#### Why USTA Does This

1. **Competitive balance**: Remove players dominating below their level
2. **Sandbagger prevention**: Automatic enforcement against rating manipulation
3. **Fair play**: Protect lower-level players from mismatched competition
4. **League integrity**: Maintain trust in rating system
5. **Player development**: Push players to compete at appropriate level

#### Why This Is Valuable

- **Automatic enforcement**: No need for manual intervention
- **Clear rules**: Players know the consequences
- **Gaming prevention**: Hard to deliberately stay at lower level
- **Fair competition**: Ensures balanced matches

#### Implementation Considerations

**Data Model**:
```kotlin
data class DisqualificationRecord(
    val dqId: String,
    val playerId: String,
    val level: String,
    val strikes: List<Strike>,
    val dqDate: Instant,
    val reason: DQReason,
    val newLevel: String,
    val affectedMatches: List<String>
)

data class Strike(
    val strikeId: String,
    val matchId: String,
    val date: LocalDate,
    val ratingAtMatch: Double,
    val threshold: Double,
    val exceedanceAmount: Double,
    val dominance: Double
)

enum class DQReason {
    THREE_STRIKES,
    EXTREME_DOMINANCE,
    MANUAL_DQ,
    APPEAL_DENIAL
}

data class DQThresholds(
    val level: String,
    val strikeThreshold: Double,  // Rating threshold for strike
    val maxStrikes: Int = 3,
    val seasonalReset: Boolean = true
)

// NTRP thresholds
val NTRP_DQ_THRESHOLDS = mapOf(
    "3.0" to DQThresholds("3.0", strikeThreshold = 3.50),
    "3.5" to DQThresholds("3.5", strikeThreshold = 4.00),
    "4.0" to DQThresholds("4.0", strikeThreshold = 4.50),
    "4.5" to DQThresholds("4.5", strikeThreshold = 5.00),
    "5.0" to DQThresholds("5.0", strikeThreshold = 5.50)
)
```

**DQ Detection Algorithm**:
```kotlin
class DisqualificationService(
    val thresholds: Map<String, DQThresholds>,
    val strikeRepository: StrikeRepository,
    val dqRepository: DQRepository
) {
    fun checkForStrike(
        player: PlayerProfile,
        match: Match,
        newRating: Double
    ): StrikeResult {
        val playerLevel = player.rating.level  // e.g., "4.0"
        val threshold = thresholds[playerLevel] ?: return StrikeResult.NoStrike

        // Check if rating exceeds strike threshold
        if (newRating > threshold.strikeThreshold) {
            val dominance = calculateDominance(match.score)

            // Record strike
            val strike = Strike(
                strikeId = generateId(),
                matchId = match.matchId,
                date = match.date,
                ratingAtMatch = newRating,
                threshold = threshold.strikeThreshold,
                exceedanceAmount = newRating - threshold.strikeThreshold,
                dominance = dominance
            )

            strikeRepository.save(player.playerId, strike)

            // Check total strikes
            val totalStrikes = strikeRepository.countActiveStrikes(player.playerId, playerLevel)

            if (totalStrikes >= threshold.maxStrikes) {
                return StrikeResult.DisqualificationTriggered(strike, totalStrikes)
            }

            return StrikeResult.StrikeRecorded(strike, totalStrikes)
        }

        return StrikeResult.NoStrike
    }

    fun executeDisqualification(
        player: PlayerProfile,
        strikes: List<Strike>
    ): DisqualificationRecord {
        val currentLevel = player.rating.level
        val newLevel = calculateBumpLevel(currentLevel)

        val dq = DisqualificationRecord(
            dqId = generateId(),
            playerId = player.playerId,
            level = currentLevel,
            strikes = strikes,
            dqDate = Instant.now(),
            reason = DQReason.THREE_STRIKES,
            newLevel = newLevel,
            affectedMatches = strikes.map { it.matchId }
        )

        dqRepository.save(dq)

        // Update player level
        playerRepository.updateLevel(player.playerId, newLevel)

        // Notify player
        notificationService.notifyDisqualification(player.playerId, dq)

        // Notify league administrators
        adminNotificationService.notifyDQ(dq)

        return dq
    }

    private fun calculateBumpLevel(currentLevel: String): String {
        // NTRP levels: 2.5, 3.0, 3.5, 4.0, 4.5, 5.0, 5.5
        return when (currentLevel) {
            "2.5" -> "3.0"
            "3.0" -> "3.5"
            "3.5" -> "4.0"
            "4.0" -> "4.5"
            "4.5" -> "5.0"
            "5.0" -> "5.5"
            "5.5" -> "5.5"  // Already at high level
            else -> currentLevel
        }
    }
}

sealed class StrikeResult {
    object NoStrike : StrikeResult()
    data class StrikeRecorded(val strike: Strike, val totalStrikes: Int) : StrikeResult()
    data class DisqualificationTriggered(val strike: Strike, val totalStrikes: Int) : StrikeResult()
}
```

**Integration with Rating Calculation**:
```kotlin
fun calculateRatingWithDQCheck(
    request: RankingCalculationRequest,
    player: PlayerProfile
): RankingCalculationResult {
    // Calculate rating
    val result = calculator.calculate(request)
    val newRating = result.response.ratingChanges[player.playerId]?.newRating?.value?.toDouble()

    if (newRating != null) {
        // Check for strike
        val strikeResult = dqService.checkForStrike(player, request.toMatch(), newRating)

        when (strikeResult) {
            is StrikeResult.NoStrike -> {
                // Normal result
            }
            is StrikeResult.StrikeRecorded -> {
                result.warnings.add(
                    "Strike recorded (${strikeResult.totalStrikes}/3). " +
                    "Rating ${strikeResult.strike.ratingAtMatch} exceeds " +
                    "threshold ${strikeResult.strike.threshold}"
                )
            }
            is StrikeResult.DisqualificationTriggered -> {
                val strikes = strikeRepository.getStrikes(player.playerId, player.rating.level)
                val dq = dqService.executeDisqualification(player, strikes)

                return result.copy(
                    disqualified = true,
                    disqualificationInfo = dq,
                    message = "Player disqualified from level ${dq.level}, " +
                             "bumped to ${dq.newLevel} due to three strikes"
                )
            }
        }
    }

    return result
}
```

**API Response with Strike/DQ Info**:
```json
{
  "ratingChanges": {
    "P1": {
      "newRating": {"value": "4.55", "publishedLevel": "4.5"},
      "change": "+0.25",
      "level": "4.0",
      "strikes": 2,
      "strikeInfo": {
        "strikeRecorded": true,
        "ratingAtMatch": 4.55,
        "threshold": 4.50,
        "strikesRemaining": 1,
        "warning": "Strike recorded (2/3). One more strike will result in disqualification."
      }
    }
  }
}
```

**DQ Response**:
```json
{
  "ratingChanges": {
    "P1": {
      "newRating": {"value": "4.62", "publishedLevel": "4.5"},
      "change": "+0.18",
      "level": "4.5",
      "disqualified": true,
      "disqualificationInfo": {
        "dqDate": "2024-06-05T14:30:00Z",
        "reason": "THREE_STRIKES",
        "oldLevel": "4.0",
        "newLevel": "4.5",
        "strikes": [
          {
            "date": "2024-05-15",
            "rating": 4.52,
            "threshold": 4.50
          },
          {
            "date": "2024-05-22",
            "rating": 4.58,
            "threshold": 4.50
          },
          {
            "date": "2024-06-05",
            "rating": 4.62,
            "threshold": 4.50
          }
        ],
        "message": "Disqualified from 4.0 level. Must compete at 4.5 level going forward."
      }
    }
  }
}
```

**Admin Interface**:
```kotlin
// View strikes for player
GET /api/v1/admin/players/{playerId}/strikes

// Manual DQ
POST /api/v1/admin/players/{playerId}/disqualify
{
  "reason": "MANUAL_DQ",
  "newLevel": "4.5",
  "notes": "Multiple reports of sandbagging"
}

// View all DQs in period
GET /api/v1/admin/disqualifications?startDate=2024-01-01&endDate=2024-06-30

// Reset strikes (e.g., new season)
POST /api/v1/admin/strikes/reset
{
  "season": "2024",
  "levels": ["3.0", "3.5", "4.0", "4.5", "5.0"]
}
```

**Challenges**:
- **Database**: Store strikes and DQ history
- **Level management**: Need to track player levels (not just continuous ratings)
- **Threshold tuning**: What's the right strike threshold for each level?
- **Appeals**: Need process for contesting DQs
- **Match forfeits**: Should DQ retroactively affect team results?
- **Communication**: Players need clear notification

**Estimated Effort**: 2 weeks
- Implement strike tracking system
- Build DQ detection algorithm
- Add level bump logic
- Create notification system
- Admin interface for DQ management
- Testing edge cases and appeals

---

### 9. Benchmark/Validation Players

**Status**: ❌ Not Implemented | **Priority**: 🟢 Low | **Complexity**: High

#### What USTA Does

USTA uses **benchmark players** to calibrate and validate the rating system:

**Benchmark Players**:
- Known, established players with reliable ratings
- Used as reference points for system calibration
- Help ensure ratings remain consistent across:
  - Different sections/regions
  - Different years
  - Different populations

**Validation Process**:
1. **Select benchmarks**: Identify players with stable, accurate ratings
2. **Cross-validation**: Compare new ratings against benchmark results
3. **Calibration**: Adjust system parameters if benchmarks drift
4. **Regional consistency**: Ensure 4.0 in California = 4.0 in Texas

**Example**:
- Known 4.5 benchmark player plays new self-rated player
- If new player dominates benchmark, rating may be adjusted
- If many new players beat benchmark, system may be recalibrated

#### Why USTA Does This

1. **System accuracy**: Verify ratings reflect true skill
2. **Consistency**: Maintain standards across regions and time
3. **Drift prevention**: Catch rating inflation/deflation
4. **Validation**: Confirm algorithm is working correctly
5. **Quality assurance**: Ongoing monitoring of system health

#### Why This Is Valuable

- **Rating accuracy**: Benchmarks provide ground truth
- **Long-term stability**: Prevents ratings from drifting over time
- **Cross-section fairness**: Ensures national consistency
- **Confidence**: Validates that system works as intended

#### Implementation Considerations

**Data Model**:
```kotlin
data class BenchmarkPlayer(
    val playerId: String,
    val designatedRating: Rating,
    val confidenceLevel: Double,
    val benchmarkStatus: BenchmarkStatus,
    val validFrom: LocalDate,
    val validTo: LocalDate?,
    val section: String?,
    val notes: String?
)

enum class BenchmarkStatus {
    ACTIVE,
    RETIRED,
    UNDER_REVIEW,
    INVALIDATED
}

data class BenchmarkValidation(
    val validationId: String,
    val benchmarkPlayerId: String,
    val testMatches: List<Match>,
    val expectedRating: Double,
    val calculatedRating: Double,
    val deviation: Double,
    val passed: Boolean,
    val validatedAt: Instant
)

data class SystemCalibration(
    val calibrationId: String,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val benchmarkResults: List<BenchmarkValidation>,
    val averageDeviation: Double,
    val adjustmentRecommended: Boolean,
    val adjustmentMade: Boolean,
    val notes: String?
)
```

**Benchmark Selection**:
```kotlin
class BenchmarkSelectionService {
    fun selectBenchmarkCandidates(
        pool: List<PlayerProfile>,
        criteria: BenchmarkCriteria
    ): List<BenchmarkPlayer> {
        return pool
            .filter { meetsBasicRequirements(it, criteria) }
            .sortedByDescending { calculateBenchmarkScore(it) }
            .take(criteria.targetCount)
            .map { createBenchmarkPlayer(it) }
    }

    private fun meetsBasicRequirements(
        player: PlayerProfile,
        criteria: BenchmarkCriteria
    ): Boolean {
        return player.matchHistory.size >= criteria.minMatches &&
               player.ratingStability >= criteria.minStability &&
               player.activeYears >= criteria.minYears &&
               player.ratingSource == RatingSource.COMPUTER_CALCULATED
    }

    private fun calculateBenchmarkScore(player: PlayerProfile): Double {
        // Score based on:
        // - Number of matches
        // - Rating stability (low variance)
        // - Longevity (years active)
        // - Match quality (played against diverse opponents)

        val matchScore = min(player.matchHistory.size / 100.0, 1.0)
        val stabilityScore = player.ratingStability
        val longevityScore = min(player.activeYears / 5.0, 1.0)
        val diversityScore = calculateOpponentDiversity(player)

        return (matchScore * 0.3 +
                stabilityScore * 0.4 +
                longevityScore * 0.2 +
                diversityScore * 0.1)
    }
}

data class BenchmarkCriteria(
    val targetCount: Int = 50,  // Benchmarks per level
    val minMatches: Int = 50,
    val minStability: Double = 0.85,
    val minYears: Int = 2,
    val maxDeviation: Double = 0.15
)
```

**Validation Against Benchmarks**:
```kotlin
class BenchmarkValidator(
    val benchmarkRepository: BenchmarkRepository
) {
    fun validateRatingAgainstBenchmarks(
        player: PlayerProfile,
        matches: List<Match>
    ): BenchmarkValidation {
        // Find matches against benchmark players
        val benchmarkMatches = matches.filter { match ->
            benchmarkRepository.isBenchmark(match.opponentId)
        }

        if (benchmarkMatches.isEmpty()) {
            return BenchmarkValidation.notApplicable()
        }

        // Calculate expected rating based on benchmark results
        val expectedRating = calculateExpectedRatingFromBenchmarks(
            player,
            benchmarkMatches
        )

        // Compare to actual calculated rating
        val actualRating = player.rating.value.toDouble()
        val deviation = abs(expectedRating - actualRating)

        val passed = deviation < 0.2  // Within 0.2 points

        return BenchmarkValidation(
            validationId = generateId(),
            benchmarkPlayerId = player.playerId,
            testMatches = benchmarkMatches,
            expectedRating = expectedRating,
            calculatedRating = actualRating,
            deviation = deviation,
            passed = passed,
            validatedAt = Instant.now()
        )
    }

    private fun calculateExpectedRatingFromBenchmarks(
        player: PlayerProfile,
        benchmarkMatches: List<Match>
    ): Double {
        // Use benchmark results to infer player's true rating
        var estimatedRating = 0.0
        var totalWeight = 0.0

        benchmarkMatches.forEach { match ->
            val benchmark = benchmarkRepository.getBenchmark(match.opponentId)
            val benchmarkRating = benchmark.designatedRating.value.toDouble()

            // Estimate player rating based on result against benchmark
            val dominance = calculateDominance(match.score)
            val expectedDiff = when {
                match.playerWon && dominance > 0.7 -> 0.5  // Dominated benchmark → higher rating
                match.playerWon && dominance > 0.3 -> 0.2  // Beat benchmark → slightly higher
                match.playerWon -> 0.0                    // Narrowly beat → similar
                !match.playerWon && abs(dominance) < 0.3 -> 0.0  // Close loss → similar
                !match.playerWon && abs(dominance) > 0.7 -> -0.5 // Dominated by benchmark → lower
                else -> -0.2                              // Lost → slightly lower
            }

            val weight = benchmark.confidenceLevel
            estimatedRating += (benchmarkRating + expectedDiff) * weight
            totalWeight += weight
        }

        return if (totalWeight > 0) estimatedRating / totalWeight else player.rating.value.toDouble()
    }
}
```

**System Calibration**:
```kotlin
class SystemCalibrationService(
    val validator: BenchmarkValidator,
    val benchmarkRepository: BenchmarkRepository
) {
    fun runCalibration(
        period: DateRange,
        benchmarks: List<BenchmarkPlayer>
    ): SystemCalibration {
        val validations = benchmarks.map { benchmark ->
            val player = playerRepository.getPlayer(benchmark.playerId)
            val matches = matchRepository.getMatchesInPeriod(benchmark.playerId, period)
            validator.validateRatingAgainstBenchmarks(player, matches)
        }

        val averageDeviation = validations.map { it.deviation }.average()
        val maxDeviation = validations.maxOf { it.deviation }

        val adjustmentRecommended = averageDeviation > 0.15 || maxDeviation > 0.30

        val calibration = SystemCalibration(
            calibrationId = generateId(),
            periodStart = period.start,
            periodEnd = period.end,
            benchmarkResults = validations,
            averageDeviation = averageDeviation,
            adjustmentRecommended = adjustmentRecommended,
            adjustmentMade = false,
            notes = if (adjustmentRecommended) {
                "Average deviation: $averageDeviation exceeds threshold 0.15. " +
                "System calibration recommended."
            } else {
                "System operating within normal parameters."
            }
        )

        calibrationRepository.save(calibration)

        if (adjustmentRecommended) {
            notificationService.alertAdministrators(calibration)
        }

        return calibration
    }
}
```

**Admin Interface**:
```kotlin
// Designate benchmark player
POST /api/v1/admin/benchmarks
{
  "playerId": "P1",
  "designatedRating": {"value": "4.5", "publishedLevel": "4.5"},
  "confidenceLevel": 0.95,
  "validFrom": "2024-01-01",
  "section": "Southern California"
}

// Run calibration
POST /api/v1/admin/calibration/run
{
  "periodStart": "2024-01-01",
  "periodEnd": "2024-06-30",
  "sections": ["Southern California", "Northern California"]
}

// View calibration results
GET /api/v1/admin/calibration/{calibrationId}

// List benchmarks
GET /api/v1/admin/benchmarks?status=ACTIVE&level=4.5
```

**Challenges**:
- **Benchmark selection**: How to identify reliable benchmark players?
- **Long-term tracking**: Benchmarks need stable, consistent performance
- **Regional variations**: Different regions may have different player pools
- **Maintenance**: Benchmarks need periodic review and rotation
- **Statistical complexity**: Sophisticated algorithms needed
- **Data requirements**: Requires extensive historical match data

**Estimated Effort**: 3-4 weeks
- Design benchmark selection algorithm
- Implement validation logic
- Build calibration system
- Create statistical analysis tools
- Admin interface for benchmark management
- Reporting and alerts
- Extensive testing

**Note**: This is a **high complexity** feature requiring significant statistical sophistication and ongoing maintenance.

---

### 10. Appeal Process

**Status**: ❌ Not Implemented | **Priority**: 🟢 Low | **Complexity**: Medium

#### What USTA Does

USTA provides a **rating appeal process** for players who believe their rating is inaccurate:

**Appeal Scenarios**:
1. **Medical appeals**: Injury affected recent matches, rating should exclude those
2. **Skill decline**: Player no longer plays at current level (age, injury, etc.)
3. **Rating too high**: Can't compete at assigned level
4. **Clerical error**: Incorrect match results or data entry
5. **Extraordinary circumstances**: Life events affecting play

**Appeal Process**:
1. Player submits appeal to section coordinator
2. Coordinator reviews match history and evidence
3. Decision made to approve/deny
4. If approved, rating adjusted or matches struck
5. Appeal decision communicated to player

**Evidence Required**:
- Medical documentation (for injury appeals)
- Match history showing inability to compete
- Written statement from player
- Supporting statements from captains/coaches

**Limitations**:
- Can't appeal "rating too low" (prevents sandbagging)
- Limited number of appeals per year
- Must provide documentation
- Coordinator has final say

#### Why USTA Does This

1. **Fairness**: Accommodate legitimate special circumstances
2. **Player satisfaction**: Provide recourse for perceived injustice
3. **Accuracy**: Correct errors or unusual situations
4. **Human judgment**: Algorithm can't account for everything
5. **Trust**: Players feel system is reasonable, not arbitrary

#### Why This Is Valuable

- **Error correction**: Fix mistakes in data or calculation
- **Special circumstances**: Handle injury, life events, etc.
- **Player trust**: Shows system is fair and flexible
- **Quality control**: Human review catches edge cases

#### Implementation Considerations

**Data Model**:
```kotlin
data class RatingAppeal(
    val appealId: String,
    val playerId: String,
    val currentRating: Rating,
    val requestedRating: Rating,
    val appealType: AppealType,
    val submittedAt: Instant,
    val status: AppealStatus,
    val evidence: List<EvidenceDocument>,
    val playerStatement: String,
    val reviewedBy: String?,
    val reviewedAt: Instant?,
    val decision: AppealDecision?,
    val decisionReason: String?,
    val effectiveDate: LocalDate?
)

enum class AppealType {
    MEDICAL,
    SKILL_DECLINE,
    RATING_TOO_HIGH,
    CLERICAL_ERROR,
    EXTRAORDINARY_CIRCUMSTANCES
}

enum class AppealStatus {
    SUBMITTED,
    UNDER_REVIEW,
    APPROVED,
    DENIED,
    WITHDRAWN
}

data class AppealDecision(
    val approved: Boolean,
    val adjustedRating: Rating?,
    val struckMatches: List<String>,  // Match IDs to exclude
    val effectiveDate: LocalDate,
    val expirationDate: LocalDate?,
    val conditions: String?
)

data class EvidenceDocument(
    val documentId: String,
    val type: DocumentType,
    val filename: String,
    val uploadedAt: Instant,
    val verified: Boolean
)

enum class DocumentType {
    MEDICAL_CERTIFICATE,
    MATCH_HISTORY,
    CAPTAIN_STATEMENT,
    OTHER_SUPPORTING_DOC
}
```

**Appeal Submission**:
```kotlin
class RatingAppealService(
    val appealRepository: AppealRepository,
    val playerRepository: PlayerRepository,
    val notificationService: NotificationService
) {
    fun submitAppeal(
        playerId: String,
        appealType: AppealType,
        requestedRating: Rating,
        playerStatement: String,
        evidence: List<EvidenceDocument>
    ): RatingAppeal {
        val player = playerRepository.getPlayer(playerId)

        // Validation
        require(canSubmitAppeal(player)) {
            "Player has exceeded maximum appeals for this period"
        }

        require(appealType != AppealType.RATING_TOO_HIGH ||
                requestedRating.value < player.rating.value) {
            "Cannot appeal for rating increase (prevents sandbagging)"
        }

        val appeal = RatingAppeal(
            appealId = generateId(),
            playerId = playerId,
            currentRating = player.rating,
            requestedRating = requestedRating,
            appealType = appealType,
            submittedAt = Instant.now(),
            status = AppealStatus.SUBMITTED,
            evidence = evidence,
            playerStatement = playerStatement,
            reviewedBy = null,
            reviewedAt = null,
            decision = null,
            decisionReason = null,
            effectiveDate = null
        )

        appealRepository.save(appeal)

        // Notify administrators
        notificationService.notifyNewAppeal(appeal)

        // Notify player
        notificationService.confirmAppealSubmission(playerId, appeal)

        return appeal
    }

    private fun canSubmitAppeal(player: PlayerProfile): Boolean {
        val recentAppeals = appealRepository.getAppealsByPlayer(
            player.playerId,
            since = LocalDate.now().minusYears(1)
        )

        return recentAppeals.size < 2  // Max 2 appeals per year
    }
}
```

**Appeal Review**:
```kotlin
class AppealReviewService(
    val appealRepository: AppealRepository,
    val matchRepository: MatchRepository,
    val ratingService: RatingService
) {
    fun reviewAppeal(
        appealId: String,
        reviewerId: String,
        approved: Boolean,
        decisionReason: String,
        adjustedRating: Rating? = null,
        matchesToStrike: List<String> = emptyList()
    ): RatingAppeal {
        val appeal = appealRepository.findById(appealId)

        require(appeal.status == AppealStatus.SUBMITTED ||
                appeal.status == AppealStatus.UNDER_REVIEW) {
            "Appeal is not in reviewable status"
        }

        val decision = if (approved) {
            AppealDecision(
                approved = true,
                adjustedRating = adjustedRating,
                struckMatches = matchesToStrike,
                effectiveDate = LocalDate.now(),
                expirationDate = calculateExpirationDate(appeal.appealType),
                conditions = buildApprovalConditions(appeal.appealType)
            )
        } else {
            AppealDecision(
                approved = false,
                adjustedRating = null,
                struckMatches = emptyList(),
                effectiveDate = LocalDate.now(),
                expirationDate = null,
                conditions = null
            )
        }

        val updatedAppeal = appeal.copy(
            status = if (approved) AppealStatus.APPROVED else AppealStatus.DENIED,
            reviewedBy = reviewerId,
            reviewedAt = Instant.now(),
            decision = decision,
            decisionReason = decisionReason,
            effectiveDate = decision.effectiveDate
        )

        appealRepository.update(updatedAppeal)

        // Apply decision
        if (approved) {
            applyAppealDecision(updatedAppeal)
        }

        // Notify player
        notificationService.notifyAppealDecision(appeal.playerId, updatedAppeal)

        return updatedAppeal
    }

    private fun applyAppealDecision(appeal: RatingAppeal) {
        val decision = appeal.decision!!

        // Strike specified matches
        decision.struckMatches.forEach { matchId ->
            matchRepository.strikeMatch(matchId, reason = "Rating appeal approved")
        }

        // Apply rating adjustment
        if (decision.adjustedRating != null) {
            ratingService.overrideRating(
                appeal.playerId,
                decision.adjustedRating,
                reason = "Appeal approved: ${appeal.appealType}",
                effectiveDate = decision.effectiveDate,
                expirationDate = decision.expirationDate
            )
        }
    }

    private fun calculateExpirationDate(appealType: AppealType): LocalDate? {
        return when (appealType) {
            AppealType.MEDICAL -> LocalDate.now().plusMonths(6)  // 6 months recovery
            AppealType.SKILL_DECLINE -> null  // Permanent until re-evaluated
            AppealType.RATING_TOO_HIGH -> null  // Permanent
            AppealType.CLERICAL_ERROR -> null  // Permanent correction
            AppealType.EXTRAORDINARY_CIRCUMSTANCES -> LocalDate.now().plusYears(1)
        }
    }
}
```

**API Endpoints**:
```kotlin
// Submit appeal
POST /api/v1/appeals
{
  "playerId": "P1",
  "appealType": "MEDICAL",
  "requestedRating": {"value": "4.0", "publishedLevel": "4.0"},
  "playerStatement": "Played injured last 3 months, had surgery in May",
  "evidenceIds": ["doc1", "doc2"]
}

// Upload evidence
POST /api/v1/appeals/evidence
Content-Type: multipart/form-data

// Review appeal (admin)
POST /api/v1/admin/appeals/{appealId}/review
{
  "approved": true,
  "decisionReason": "Medical evidence supports appeal",
  "adjustedRating": {"value": "4.0", "publishedLevel": "4.0"},
  "matchesToStrike": ["match1", "match2", "match3"]
}

// Get appeal status
GET /api/v1/appeals/{appealId}

// List player appeals
GET /api/v1/players/{playerId}/appeals

// List pending appeals (admin)
GET /api/v1/admin/appeals?status=SUBMITTED
```

**Response Example**:
```json
{
  "appealId": "appeal123",
  "playerId": "P1",
  "currentRating": {"value": "4.5", "publishedLevel": "4.5"},
  "requestedRating": {"value": "4.0", "publishedLevel": "4.0"},
  "appealType": "MEDICAL",
  "status": "APPROVED",
  "submittedAt": "2024-05-15T10:00:00Z",
  "reviewedAt": "2024-05-20T14:30:00Z",
  "reviewedBy": "coordinator@section.usta.com",
  "decision": {
    "approved": true,
    "adjustedRating": {"value": "4.0", "publishedLevel": "4.0"},
    "struckMatches": ["match1", "match2", "match3"],
    "effectiveDate": "2024-05-20",
    "expirationDate": "2024-11-20",
    "decisionReason": "Medical documentation verified. Three matches during injury period struck from record."
  }
}
```

**Challenges**:
- **Document management**: Store and verify evidence documents
- **Review workflow**: Need admin interface for reviewing appeals
- **Notification system**: Keep players informed of status
- **Rating recalculation**: Striking matches requires recomputing rating history
- **Abuse prevention**: Limit appeals to prevent gaming
- **Documentation**: Players need clear guidelines on appeal process

**Estimated Effort**: 1-2 weeks
- Implement appeal data model and workflow
- Build submission interface
- Create admin review tools
- Document upload/verification
- Notification system
- Rating adjustment logic
- Testing and documentation

---

## Continued in Part 2...

**Note**: Due to length, features 11-17 are summarized below. Full details can be added if needed.

### 11. Rating Confidence/Reliability Metrics — ✅ adopted
- **What**: Track confidence in rating based on match count, recency, opponent quality
- **Priority**: 🔴 High (valuable for most use cases)
- **Complexity**: Medium
- **Value**: Distinguish between established and uncertain ratings
- **Skopeo status**: Built. `UserRating` carries a `confidence` value that starts low and
  converges as `matchesPlayed` grows; it is returned on the rating endpoints
  (`UserRatingResponse`). Opponent-quality weighting of confidence is a possible refinement.

### 12. Section/Regional Variations — ❌ not planned
- **What**: Support different rules/policies by geographic section
- **Priority**: 🟢 Low (specific to USTA organizational structure)
- **Complexity**: Low
- **Value**: Accommodate regional differences

### 13. Historical Data and Trends — ✅ adopted
- **What**: Store and analyze complete match history, rating progression
- **Priority**: 🔴 High (essential for many features)
- **Complexity**: High (requires full persistence layer)
- **Value**: Enables analytics, trending, pattern detection
- **Skopeo status**: Built. Rating changes are recorded as an append-only
  `RatingHistoryEntry` log (per-match, with previous/new rating, change, level change,
  dominance factor, and smoothing detail) and exposed via the rating-history endpoint. This was
  unlocked by the persistence layer. Higher-level trend analytics can build on this store.

### 14. Dynamic vs Static Ratings — ✅ adopted
- **What**: Separate continuous (dynamic) ratings from discrete published levels
- **Priority**: 🟡 Medium (useful for leagues)
- **Complexity**: Medium
- **Value**: Balance stability with accuracy
- **Skopeo status**: Built. Every rating pairs a continuous `currentRating`/`value` with a
  discrete `currentLevel`/`publishedLevel` (the value floored to the nearest 0.5; see `Level`).
  The continuous value moves per match while the published level is the stable bucket. USTA's
  *year-end vs in-season* snapshotting specifically is not built.

### 15. Age and Experience Considerations — 🚧 in pipeline
- **What**: Different treatment for juniors, seniors, new vs experienced players
- **Priority**: 🟢 Low (nice-to-have)
- **Complexity**: Low
- **Value**: Better fit for different player populations
- **Skopeo status**: Partial. `dateOfBirth` is captured at sign-up (required, alongside `sex`)
  and stored on the user, so age data exists. No age-based rating adjustment uses it yet.

### 16. Rating Boundaries and Level Assignment — ✅ adopted
- **What**: Discrete level buckets (3.0, 3.5, 4.0) alongside continuous ratings
- **Priority**: 🟡 Medium (needed for league organization)
- **Complexity**: Low
- **Value**: Simplifies league/tournament organization
- **Skopeo status**: Built. The `Level` model defines NTRP 1.0–7.0 in 0.5-wide bands and derives
  the published level from a rating value; every `Rating` carries its `publishedLevel`.

### 17. Surface Type Adjustments — ❌ not planned
- **What**: Different ratings for clay, hard, grass courts
- **Priority**: 🟢 Low (nice-to-have)
- **Complexity**: Medium
- **Value**: Accounts for surface-specific strengths

---

## Implementation Priorities

### ✅ Already Adopted
- **Historical Data Tracking** - append-only rating history (foundation for many features)
- **Rating Confidence Metrics** - confidence converging with matches played
- **Dynamic vs Published Ratings** - continuous value + discrete level
- **Level Boundaries** - NTRP 0.5-wide bands
- **Admin-Assigned Ratings / Assessment** - pending-assessment queue + admin set/adjust
- **Match Results Drive Ratings** - fixtures, result upload, calculation trigger
- **Rating Smoothing** - USTA-style smoothing factor

### 🚧 In Pipeline (foundation laid)
- **Doubles Support** - team-based schema ready; calculator is SINGLES-only
- **Minimum Match Requirements** - `matchesPlayed`/confidence tracked; publish gating remains
- **Age Considerations** - `dateOfBirth` captured; no rating use yet

### ❌ Not Planned (for now)
- **Match Type Weighting** - Prevents gaming, improves accuracy
- **Self-Rate Validation** - N/A: Skopeo uses admin-assigned, not self-rated, initial ratings
- **DQ System** - Maintains competitive balance
- **Outlier Detection** - Improves data quality
- **Rating Periods** - Administrative convenience
- **Publication Delays** - Administrative control
- **Appeal Process** - Handles edge cases
- **Benchmark Players** - System validation
- **Regional Variations** - Organizational flexibility
- **Surface Adjustments** - Advanced feature

---

## Dependencies

Some features depend on others:

```
Historical Data Tracking (must implement first)
  ├── Rating Confidence Metrics
  ├── Match Type Weighting
  ├── Minimum Match Requirements
  ├── Outlier Detection
  ├── Self-Rate Validation
  ├── DQ System
  └── Benchmark Players

Level Management System
  ├── Level Boundaries
  └── DQ System

Administrative Tools
  ├── Appeal Process
  ├── Rating Holds
  └── Manual Overrides
```

---

## Next Steps

To implement any of these features:

1. **Choose feature** based on priority and business needs
2. **Review detailed section** in this document
3. **Design data model** for persistence
4. **Create API contracts** for new endpoints
5. **Implement core logic** following examples
6. **Build admin tools** if needed
7. **Test thoroughly** with edge cases
8. **Document** usage and behavior

---

**Questions or suggestions?**
Add comments to this document or create issues for specific features to implement.
