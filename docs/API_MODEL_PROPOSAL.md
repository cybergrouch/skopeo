# Skopeo API - Data Model Proposal

## Overview

This document originally proposed the data model for the Dynamic Ranking Calculation API.
The core calculator described here is now **implemented**; this document has been updated to
align with the shipped DTOs and domain model. Skopeo is **NTRP-only** (1.0–7.0) — there is no
multi-system support. Sections describing features not yet built are explicitly marked
**(future/proposed)**.

## Requirements

### Input
1. **Player Profiles** (2 players)
   - Player ID
   - Player name
   - Current NTRP rating

2. **Match Scores**
   - Set scores (with or without tiebreaks)
   - Winner of each set
   - Optional: Game-by-game scores
   - Optional: Game details (deuces, points)

### Output
- Updated player profiles with new rankings
- Rating change details

---

## Data Model

### Option 1: Map-Based Model (implemented)

This is the model that shipped: a map keyed by ID, structured match scores, and BigDecimal
ratings serialized as strings. (The shipped request keys the map by *team* ID rather than player
ID — see the note after the request class — but the shape is otherwise as below.)

#### Core Data Classes

```kotlin
// Rating: the continuous NTRP value paired with its discrete published level.
// Ratings are BigDecimal-precise throughout and serialized as strings in JSON.
// The level is derived from the value on construction (v1 stateless); clients may
// send just `value` and the level is filled in.
data class Rating(
    val value: String,            // e.g. "4.532"
    val publishedLevel: Level     // e.g. 4.5
) {
    init {
        val numericValue = value.toDoubleOrNull()
            ?: throw IllegalArgumentException("Rating value must be a valid number")
        require(numericValue in 1.0..7.0) { "NTRP rating must be between 1.0 and 7.0" }
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
    }
}

// NOTE: The shipped request model wraps players in teams to allow doubles later:
// `RankingCalculationRequest(teams: Map<String, Team>, matchScore, matchDate?, options?)`,
// where each Team currently holds exactly 1 player (SINGLES). See dto/RankingCalculationRequest.kt.

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
        "value": "4.5",
        "publishedLevel": "4.5"
      }
    },
    "P456": {
      "playerId": "P456",
      "name": "Jane Smith",
      "rating": {
        "value": "4.0",
        "publishedLevel": "4.0"
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
        "value": "4.52",
        "publishedLevel": "4.5"
      }
    },
    "P456": {
      "playerId": "P456",
      "name": "Jane Smith",
      "rating": {
        "value": "3.98",
        "publishedLevel": "3.5"
      }
    }
  },
  "ratingChanges": {
    "P123": {
      "change": 0.02,
      "percentChange": 0.44,
      "previousRating": {
        "value": "4.5",
        "publishedLevel": "4.5"
      },
      "newRating": {
        "value": "4.52",
        "publishedLevel": "4.5"
      }
    },
    "P456": {
      "change": -0.02,
      "percentChange": -0.50,
      "previousRating": {
        "value": "4.0",
        "publishedLevel": "4.0"
      },
      "newRating": {
        "value": "3.98",
        "publishedLevel": "3.5"
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
        "value": "5.5",
        "publishedLevel": "5.5"
      }
    },
    "P101": {
      "playerId": "P101",
      "name": "Sarah Lee",
      "rating": {
        "value": "5.0",
        "publishedLevel": "5.0"
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
        "value": "5.5",
        "publishedLevel": "5.5"
      }
    },
    "P222": {
      "playerId": "P222",
      "name": "Anna Kowalski",
      "rating": {
        "value": "5.0",
        "publishedLevel": "5.0"
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

## Option 2: Simplified String-Based Model (future/proposed)

> **Not implemented.** The shipped API uses the structured `matchScore` (Option 1).
> This string-based variant is retained as a possible future addition.

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
        "value": "4.5",
        "publishedLevel": "4.5"
      }
    },
    "P456": {
      "playerId": "P456",
      "name": "Jane Smith",
      "rating": {
        "value": "4.0",
        "publishedLevel": "4.0"
      }
    }
  },
  "scoreString": "6-4, 6-3",
  "winner": "P123"
}
```

The API would parse the score string and convert it internally to the structured format.

---

## Option 3: Hybrid Model (future/proposed)

> **Not implemented.** The shipped request takes only the structured `matchScore` (wrapped in
> teams). This hybrid variant is retained as a possible future addition.

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
  - `value`: NTRP, continuous in the range 1.0 to 7.0 (any decimal, e.g. "4.532" —
    the published level is the value rounded down to the nearest 0.5)
  - `publishedLevel`: Derived from `value` if omitted by the client

### Match Score Validation
- Exactly 2 players required for singles match
- Map key must match `playerId` in the profile
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
   - Player profiles (id, name, NTRP rating)
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

---

## Sample Implementation Structure

```
src/main/kotlin/org/skopeo/
├── model/
│   ├── Rating.kt
│   ├── Level.kt
│   ├── PlayerProfile.kt
│   ├── MatchScore.kt
│   ├── SetScore.kt
│   ├── TiebreakScore.kt
│   └── Team.kt
├── dto/
│   ├── RankingCalculationRequest.kt
│   ├── RankingCalculationResponse.kt
│   └── RatingChange.kt
├── service/
│   └── calculator/impl/v1/
│       └── PerformanceBasedRankingCalculatorImpl.kt
└── routes/
    └── RankingRoutes.kt
```

---

## Questions for Discussion (resolved)

These were the open questions when this was a proposal. They have since been resolved by the
shipped implementation; recorded here for context.

1. **Which model option?** → Structured `matchScore` is the implemented path
   (Option 1). The string-based and hybrid variants below remain **(future/proposed)**.

2. **MVP scope?** → Set scores with tiebreak support are implemented; game-level
   detail remains **(future/proposed)**.

3. **Rating calculation algorithm?** → A single Elo-style NTRP algorithm is implemented
   (`service/calculator/impl/v1/`). Skopeo is NTRP-only; there is no UTR or multi-system support.

4. **Same rating system per match?** → Not applicable — Skopeo supports only NTRP.

5. **Rating boundaries?** → NTRP ratings are clamped to 1.0–7.0.
