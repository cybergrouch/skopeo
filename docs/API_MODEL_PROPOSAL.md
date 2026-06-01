# Tennis Levelr API - Data Model Proposal

## Overview

This document proposes the data model for the Dynamic Ranking Calculation API.

## Requirements

### Input
1. **Player Profiles** (2 players)
   - Player ID
   - Player name
   - Current ranking (NTRP or UTR)

2. **Match Scores**
   - Set scores (with or without tiebreaks)
   - Winner of each set
   - Optional: Game-by-game scores
   - Optional: Game details (deuces, points)

### Output
- Updated player profiles with new rankings
- Rating change details

---

## Proposed Data Model

### Option 1: Structured Detailed Model (Recommended)

This model provides maximum flexibility for capturing all match details.

#### Core Data Classes

```kotlin
// Enums
enum class RatingSystem {
    NTRP,  // National Tennis Rating Program (1.0 - 7.0)
    UTR    // Universal Tennis Rating (1.0 - 16.5+)
}

// Player Profile
data class PlayerProfile(
    val playerId: String,
    val name: String,
    val rating: Double,
    val ratingSystem: RatingSystem
)

// Match Score Container
data class MatchScore(
    val sets: List<SetScore>,
    val matchFormat: MatchFormat = MatchFormat.BEST_OF_THREE
)

enum class MatchFormat {
    BEST_OF_THREE,
    BEST_OF_FIVE,
    SINGLE_SET,
    ADVANTAGE_SET
}

// Set Score
data class SetScore(
    val playerOneGames: Int,
    val playerTwoGames: Int,
    val tiebreak: TiebreakScore? = null,
    val winner: Int,  // 1 or 2
    val games: List<GameScore> = emptyList()  // Optional: detailed game scores
)

// Tiebreak Score
data class TiebreakScore(
    val playerOnePoints: Int,
    val playerTwoPoints: Int,
    val winner: Int  // 1 or 2
)

// Game Score (Optional - for detailed tracking)
data class GameScore(
    val gameNumber: Int,
    val playerOnePoints: String,  // "0", "15", "30", "40", "AD"
    val playerTwoPoints: String,
    val winner: Int,  // 1 or 2
    val deuces: Int = 0
)

// Request
data class RankingCalculationRequest(
    val playerOne: PlayerProfile,
    val playerTwo: PlayerProfile,
    val matchScore: MatchScore,
    val matchDate: String? = null,  // ISO 8601 format (optional)
    val metadata: MatchMetadata? = null  // Optional additional info
)

data class MatchMetadata(
    val tournament: String? = null,
    val surface: String? = null,  // "Hard", "Clay", "Grass", "Carpet"
    val location: String? = null,
    val notes: String? = null
)

// Response
data class RankingCalculationResponse(
    val playerOne: PlayerProfile,  // Updated rating
    val playerTwo: PlayerProfile,  // Updated rating
    val ratingChange: RatingChange,
    val calculationDetails: CalculationDetails? = null
)

data class RatingChange(
    val playerOneChange: Double,
    val playerTwoChange: Double,
    val playerOnePercentChange: Double,
    val playerTwoPercentChange: Double
)

data class CalculationDetails(
    val expectedOutcome: ExpectedOutcome,
    val actualOutcome: String,
    val algorithm: String,  // "ELO", "Glicko", etc.
    val confidence: Double? = null
)

data class ExpectedOutcome(
    val playerOneProbability: Double,  // 0.0 to 1.0
    val playerTwoProbability: Double
)
```

---

### Example JSON Payloads

#### Example 1: Simple Match (Just Set Scores)

**Request:**
```json
{
  "playerOne": {
    "playerId": "P123",
    "name": "John Doe",
    "rating": 4.5,
    "ratingSystem": "NTRP"
  },
  "playerTwo": {
    "playerId": "P456",
    "name": "Jane Smith",
    "rating": 4.0,
    "ratingSystem": "NTRP"
  },
  "matchScore": {
    "sets": [
      {
        "playerOneGames": 6,
        "playerTwoGames": 4,
        "winner": 1
      },
      {
        "playerOneGames": 6,
        "playerTwoGames": 3,
        "winner": 1
      }
    ],
    "matchFormat": "BEST_OF_THREE"
  },
  "matchDate": "2024-01-15T14:30:00Z"
}
```

**Response:**
```json
{
  "playerOne": {
    "playerId": "P123",
    "name": "John Doe",
    "rating": 4.52,
    "ratingSystem": "NTRP"
  },
  "playerTwo": {
    "playerId": "P456",
    "name": "Jane Smith",
    "rating": 3.98,
    "ratingSystem": "NTRP"
  },
  "ratingChange": {
    "playerOneChange": 0.02,
    "playerTwoChange": -0.02,
    "playerOnePercentChange": 0.44,
    "playerTwoPercentChange": -0.50
  },
  "calculationDetails": {
    "expectedOutcome": {
      "playerOneProbability": 0.65,
      "playerTwoProbability": 0.35
    },
    "actualOutcome": "Player One Won 2-0",
    "algorithm": "Modified ELO for Tennis",
    "confidence": 0.85
  }
}
```

#### Example 2: Match with Tiebreak

**Request:**
```json
{
  "playerOne": {
    "playerId": "P789",
    "name": "Mike Wilson",
    "rating": 8.5,
    "ratingSystem": "UTR"
  },
  "playerTwo": {
    "playerId": "P101",
    "name": "Sarah Lee",
    "rating": 8.2,
    "ratingSystem": "UTR"
  },
  "matchScore": {
    "sets": [
      {
        "playerOneGames": 7,
        "playerTwoGames": 6,
        "tiebreak": {
          "playerOnePoints": 7,
          "playerTwoPoints": 5,
          "winner": 1
        },
        "winner": 1
      },
      {
        "playerOneGames": 4,
        "playerTwoGames": 6,
        "winner": 2
      },
      {
        "playerOneGames": 6,
        "playerTwoGames": 3,
        "winner": 1
      }
    ]
  }
}
```

#### Example 3: Detailed Match (With Game Scores)

**Request:**
```json
{
  "playerOne": {
    "playerId": "P111",
    "name": "Carlos Rodriguez",
    "rating": 5.5,
    "ratingSystem": "NTRP"
  },
  "playerTwo": {
    "playerId": "P222",
    "name": "Anna Kowalski",
    "rating": 5.0,
    "ratingSystem": "NTRP"
  },
  "matchScore": {
    "sets": [
      {
        "playerOneGames": 6,
        "playerTwoGames": 4,
        "winner": 1,
        "games": [
          {
            "gameNumber": 1,
            "playerOnePoints": "40",
            "playerTwoPoints": "30",
            "winner": 1,
            "deuces": 0
          },
          {
            "gameNumber": 2,
            "playerOnePoints": "30",
            "playerTwoPoints": "40",
            "winner": 2,
            "deuces": 0
          },
          {
            "gameNumber": 3,
            "playerOnePoints": "AD",
            "playerTwoPoints": "40",
            "winner": 1,
            "deuces": 3
          }
          // ... more games
        ]
      }
    ]
  },
  "metadata": {
    "tournament": "City Championships 2024",
    "surface": "Hard",
    "location": "Los Angeles, CA"
  }
}
```

---

## Option 2: Simplified String-Based Model

For simpler use cases, allow score input as strings.

```kotlin
data class SimpleRankingRequest(
    val playerOne: PlayerProfile,
    val playerTwo: PlayerProfile,
    val scoreString: String,  // e.g., "6-4, 6-3" or "7-6(5), 4-6, 6-3"
    val winner: Int  // 1 or 2
)
```

**Example:**
```json
{
  "playerOne": {
    "playerId": "P123",
    "name": "John Doe",
    "rating": 4.5,
    "ratingSystem": "NTRP"
  },
  "playerTwo": {
    "playerId": "P456",
    "name": "Jane Smith",
    "rating": 4.0,
    "ratingSystem": "NTRP"
  },
  "scoreString": "6-4, 6-3",
  "winner": 1
}
```

The API would parse the score string and convert it internally to the structured format.

---

## Option 3: Hybrid Model (Recommended for MVP)

Support both formats - accept either structured or string-based input.

```kotlin
data class RankingCalculationRequest(
    val playerOne: PlayerProfile,
    val playerTwo: PlayerProfile,

    // Option A: Structured (preferred)
    val matchScore: MatchScore? = null,

    // Option B: Simple string
    val scoreString: String? = null,
    val winner: Int? = null,

    val matchDate: String? = null,
    val metadata: MatchMetadata? = null
) {
    init {
        require(matchScore != null || (scoreString != null && winner != null)) {
            "Either matchScore or (scoreString + winner) must be provided"
        }
    }
}
```

---

## Validation Rules

### Player Profile Validation
- `playerId`: Non-empty string, max 50 chars
- `name`: Non-empty string, max 100 chars
- `rating`:
  - NTRP: 1.0 to 7.0 (increments of 0.5)
  - UTR: 1.0 to 16.5+ (any decimal)
- `ratingSystem`: Must be NTRP or UTR

### Match Score Validation
- Both players must use the same `ratingSystem`
- At least 1 set required
- Max 5 sets
- Valid tennis scores:
  - Set must be won by 2+ games (unless tiebreak)
  - Tiebreak at 6-6 (or other format-specific rules)
  - Games: 0-7 for regular sets, 0-6 with tiebreak
- Winner must be consistent with scores

### Score String Format (if using Option 2/3)
- Pattern: `"6-4, 6-3"` or `"7-6(5), 4-6, 6-3"`
- Tiebreak notation: `7-6(5)` means 7-6 set with 7-5 tiebreak
- Comma-separated sets

---

## API Endpoint Design

```
POST /api/v1/calculate-ranking
Content-Type: application/json

Request: RankingCalculationRequest
Response: RankingCalculationResponse
```

---

## Error Responses

```json
{
  "error": {
    "code": "INVALID_SCORE",
    "message": "Invalid set score: 6-5 without tiebreak",
    "details": {
      "setNumber": 1,
      "playerOneGames": 6,
      "playerTwoGames": 5
    }
  }
}
```

---

## Recommendations

### For MVP (Phase 1):
1. **Use Hybrid Model (Option 3)**
   - Support simple `scoreString` for quick calculations
   - Support structured `matchScore` for detailed data
   - Internally convert string to structured format

2. **Required Fields Only:**
   - Player profiles (id, name, rating, system)
   - Match score (either format)
   - Winner (if using string format)

3. **Optional Fields for Later:**
   - Game-by-game details
   - Metadata
   - Calculation details in response

### For Phase 2:
- Add game-level granularity
- Add match metadata
- Add calculation confidence scores
- Support more rating algorithms

---

## Sample Implementation Structure

```
src/main/kotlin/org/lange/tennis/levelr/
├── model/
│   ├── RatingSystem.kt
│   ├── PlayerProfile.kt
│   ├── MatchScore.kt
│   ├── SetScore.kt
│   ├── TiebreakScore.kt
│   ├── GameScore.kt
│   └── MatchMetadata.kt
├── dto/
│   ├── RankingCalculationRequest.kt
│   ├── RankingCalculationResponse.kt
│   └── ErrorResponse.kt
├── service/
│   ├── RankingCalculationService.kt
│   ├── ScoreParserService.kt
│   └── ValidationService.kt
├── algorithm/
│   ├── RankingAlgorithm.kt
│   ├── NtrpAlgorithm.kt
│   └── UtrAlgorithm.kt
└── routes/
    └── RankingRoutes.kt
```

---

## Questions for Discussion

1. **Which model option do you prefer?**
   - Option 1: Structured only
   - Option 2: String-based only
   - Option 3: Hybrid (both)

2. **MVP scope - which features are must-have?**
   - Basic set scores only?
   - Tiebreak support?
   - Game-level detail?

3. **Rating calculation algorithm:**
   - Should we implement actual NTRP/UTR algorithms?
   - Or use a simplified ELO-based approach initially?
   - Should the algorithm be different for NTRP vs UTR?

4. **Should players in a match be required to have the same rating system?**
   - Or allow cross-system matches (NTRP vs UTR)?

5. **How should we handle rating boundaries?**
   - NTRP: Can't go below 1.0 or above 7.0
   - UTR: Can exceed 16.5 for top players

Let me know your preferences and I'll implement accordingly!
