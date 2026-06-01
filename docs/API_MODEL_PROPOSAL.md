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

### Option 1: Map-Based Model (Recommended)

This model uses player IDs as map keys for a cleaner, more scalable design.

#### Core Data Classes

```kotlin
// Enums
enum class RatingSystem {
    NTRP,  // National Tennis Rating Program (1.0 - 7.0)
    UTR    // Universal Tennis Rating (1.0 - 16.5+)
}

// Rating (encapsulates value and system)
data class Rating(
    val value: Double,
    val system: RatingSystem
) {
    init {
        when (system) {
            RatingSystem.NTRP -> {
                require(value in 1.0..7.0) { "NTRP rating must be between 1.0 and 7.0" }
                require(value % 0.5 == 0.0) { "NTRP rating must be in 0.5 increments" }
            }
            RatingSystem.UTR -> {
                require(value >= 1.0) { "UTR rating must be at least 1.0" }
            }
        }
    }
}

// Player Profile
data class PlayerProfile(
    val playerId: String,  // Must match the map key for validation
    val name: String,
    val rating: Rating
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

// Set Score (using player IDs as keys)
data class SetScore(
    val games: Map<String, Int>,  // playerId -> games won
    val tiebreak: TiebreakScore? = null,
    val winner: String,  // playerId of winner
    val gameDetails: List<GameScore> = emptyList()  // Optional: detailed game scores
)

// Tiebreak Score
data class TiebreakScore(
    val points: Map<String, Int>,  // playerId -> points
    val winner: String  // playerId of winner
)

// Game Score (Optional - for detailed tracking)
data class GameScore(
    val gameNumber: Int,
    val points: Map<String, String>,  // playerId -> "0", "15", "30", "40", "AD"
    val winner: String,  // playerId of winner
    val deuces: Int = 0
)

// Request
data class RankingCalculationRequest(
    val players: Map<String, PlayerProfile>,  // playerId (key) -> profile
    val matchScore: MatchScore,
    val matchDate: String? = null,  // ISO 8601 format (optional)
    val metadata: MatchMetadata? = null  // Optional additional info
) {
    init {
        require(players.size == 2) { "Exactly 2 players required for singles match" }
        require(players.all { (key, profile) -> key == profile.playerId }) {
            "Map key must match player profile ID"
        }
        // Ensure both players use the same rating system
        val ratingSystems = players.values.map { it.rating.system }.toSet()
        require(ratingSystems.size == 1) {
            "Both players must use the same rating system"
        }
    }
}

data class MatchMetadata(
    val tournament: String? = null,
    val surface: String? = null,  // "Hard", "Clay", "Grass", "Carpet"
    val location: String? = null,
    val notes: String? = null
)

// Response
data class RankingCalculationResponse(
    val players: Map<String, PlayerProfile>,  // playerId -> updated profile
    val ratingChanges: Map<String, RatingChange>,  // playerId -> rating change
    val calculationDetails: CalculationDetails? = null
)

data class RatingChange(
    val change: Double,
    val percentChange: Double,
    val previousRating: Rating,
    val newRating: Rating
)

data class CalculationDetails(
    val expectedOutcome: Map<String, Double>,  // playerId -> win probability
    val actualWinner: String,  // playerId
    val algorithm: String,  // "Elo", "Glicko", etc.
    val confidence: Double? = null
)
```

---

### Example JSON Payloads

#### Example 1: Simple Match (Just Set Scores)

**Request:**
```json
{
  "players": {
    "P123": {
      "playerId": "P123",
      "name": "John Doe",
      "rating": {
        "value": 4.5,
        "system": "NTRP"
      }
    },
    "P456": {
      "playerId": "P456",
      "name": "Jane Smith",
      "rating": {
        "value": 4.0,
        "system": "NTRP"
      }
    }
  },
  "matchScore": {
    "sets": [
      {
        "games": {
          "P123": 6,
          "P456": 4
        },
        "winner": "P123"
      },
      {
        "games": {
          "P123": 6,
          "P456": 3
        },
        "winner": "P123"
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
  "players": {
    "P123": {
      "playerId": "P123",
      "name": "John Doe",
      "rating": {
        "value": 4.52,
        "system": "NTRP"
      }
    },
    "P456": {
      "playerId": "P456",
      "name": "Jane Smith",
      "rating": {
        "value": 3.98,
        "system": "NTRP"
      }
    }
  },
  "ratingChanges": {
    "P123": {
      "change": 0.02,
      "percentChange": 0.44,
      "previousRating": {
        "value": 4.5,
        "system": "NTRP"
      },
      "newRating": {
        "value": 4.52,
        "system": "NTRP"
      }
    },
    "P456": {
      "change": -0.02,
      "percentChange": -0.50,
      "previousRating": {
        "value": 4.0,
        "system": "NTRP"
      },
      "newRating": {
        "value": 3.98,
        "system": "NTRP"
      }
    }
  },
  "calculationDetails": {
    "expectedOutcome": {
      "P123": 0.65,
      "P456": 0.35
    },
    "actualWinner": "P123",
    "algorithm": "Modified Elo for Tennis",
    "confidence": 0.85
  }
}
```

#### Example 2: Match with Tiebreak

**Request:**
```json
{
  "players": {
    "P789": {
      "playerId": "P789",
      "name": "Mike Wilson",
      "rating": {
        "value": 8.5,
        "system": "UTR"
      }
    },
    "P101": {
      "playerId": "P101",
      "name": "Sarah Lee",
      "rating": {
        "value": 8.2,
        "system": "UTR"
      }
    }
  },
  "matchScore": {
    "sets": [
      {
        "games": {
          "P789": 7,
          "P101": 6
        },
        "tiebreak": {
          "points": {
            "P789": 7,
            "P101": 5
          },
          "winner": "P789"
        },
        "winner": "P789"
      },
      {
        "games": {
          "P789": 4,
          "P101": 6
        },
        "winner": "P101"
      },
      {
        "games": {
          "P789": 6,
          "P101": 3
        },
        "winner": "P789"
      }
    ]
  }
}
```

#### Example 3: Detailed Match (With Game Scores)

**Request:**
```json
{
  "players": {
    "P111": {
      "playerId": "P111",
      "name": "Carlos Rodriguez",
      "rating": {
        "value": 5.5,
        "system": "NTRP"
      }
    },
    "P222": {
      "playerId": "P222",
      "name": "Anna Kowalski",
      "rating": {
        "value": 5.0,
        "system": "NTRP"
      }
    }
  },
  "matchScore": {
    "sets": [
      {
        "games": {
          "P111": 6,
          "P222": 4
        },
        "winner": "P111",
        "gameDetails": [
          {
            "gameNumber": 1,
            "points": {
              "P111": "40",
              "P222": "30"
            },
            "winner": "P111",
            "deuces": 0
          },
          {
            "gameNumber": 2,
            "points": {
              "P111": "30",
              "P222": "40"
            },
            "winner": "P222",
            "deuces": 0
          },
          {
            "gameNumber": 3,
            "points": {
              "P111": "AD",
              "P222": "40"
            },
            "winner": "P111",
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
    val players: Map<String, PlayerProfile>,  // playerId -> profile
    val scoreString: String,  // e.g., "6-4, 6-3" or "7-6(5), 4-6, 6-3"
    val winner: String  // playerId of winner
)
```

**Example:**
```json
{
  "players": {
    "P123": {
      "playerId": "P123",
      "name": "John Doe",
      "rating": {
        "value": 4.5,
        "system": "NTRP"
      }
    },
    "P456": {
      "playerId": "P456",
      "name": "Jane Smith",
      "rating": {
        "value": 4.0,
        "system": "NTRP"
      }
    }
  },
  "scoreString": "6-4, 6-3",
  "winner": "P123"
}
```

The API would parse the score string and convert it internally to the structured format.

---

## Option 3: Hybrid Model (Recommended for MVP)

Support both formats - accept either structured or string-based input.

```kotlin
data class RankingCalculationRequest(
    val players: Map<String, PlayerProfile>,  // playerId -> profile

    // Option A: Structured (preferred)
    val matchScore: MatchScore? = null,

    // Option B: Simple string
    val scoreString: String? = null,
    val winner: String? = null,  // playerId

    val matchDate: String? = null,
    val metadata: MatchMetadata? = null
) {
    init {
        require(players.size == 2) { "Exactly 2 players required for singles match" }
        require(matchScore != null || (scoreString != null && winner != null)) {
            "Either matchScore or (scoreString + winner) must be provided"
        }
        if (winner != null) {
            require(winner in players.keys) { "Winner must be one of the players" }
        }
    }
}
```

---

## Validation Rules

### Player Profile Validation
- `playerId`: Non-empty string, max 50 chars
  - **Must match the map key** (validated in request)
- `name`: Non-empty string, max 100 chars
- `rating`: Rating object
  - `value`: Validated based on system
    - NTRP: 1.0 to 7.0 (increments of 0.5)
    - UTR: 1.0 to 16.5+ (any decimal)
  - `system`: Must be NTRP or UTR

### Match Score Validation
- Exactly 2 players required for singles match
- Map key must match `playerId` in the profile
- Both players must use the same rating system (validated in request)
- At least 1 set required
- Max 5 sets
- Valid tennis scores:
  - Set must be won by 2+ games (unless tiebreak)
  - Tiebreak at 6-6 (or other format-specific rules)
  - Games: 0-7 for regular sets, 0-6 with tiebreak
- Winner must be a valid playerId from the players map
- Winner must be consistent with scores
- All score maps must contain exactly the 2 player IDs

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
   - Or use a simplified Elo-based approach initially?
   - Should the algorithm be different for NTRP vs UTR?

4. **Should players in a match be required to have the same rating system?**
   - Or allow cross-system matches (NTRP vs UTR)?

5. **How should we handle rating boundaries?**
   - NTRP: Can't go below 1.0 or above 7.0
   - UTR: Can exceed 16.5 for top players

Let me know your preferences and I'll implement accordingly!
