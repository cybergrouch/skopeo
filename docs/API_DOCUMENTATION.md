# Skopeo API Documentation

## Overview

Skopeo provides a REST API for calculating updated tennis player rankings based on match results. The API supports both NTRP (National Tennis Rating Program) and UTR (Universal Tennis Rating) systems.

**Base URL:** `http://localhost:8080`

**Version:** 0.0.1-SNAPSHOT

---

## Endpoints

### Health & Monitoring

#### GET `/health`
Check API health status.

**Response:**
```json
{
  "status": "UP",
  "service": "Skopeo API",
  "version": "0.0.1-SNAPSHOT"
}
```

#### GET `/metrics`
Prometheus metrics endpoint for monitoring.

**Response:** Prometheus text format with JVM, HTTP, and application metrics.

---

### Ranking Calculation

#### POST `/api/v1/calculate-ranking`
Calculate updated player rankings based on match results.

**Request Headers:**
- `Content-Type: application/json`

**Request Body:**

```json
{
  "players": {
    "<playerId>": {
      "playerId": "string",
      "name": "string",
      "rating": {
        "value": number,
        "system": "NTRP" | "UTR"
      }
    }
  },
  "matchScore": {
    "sets": [
      {
        "games": {
          "<playerId>": number
        },
        "winnerTeamId": "string",
        "tiebreak": {
          "points": {
            "<playerId>": number
          },
          "winnerTeamId": "string"
        }
      }
    ],
    "matchFormat": "BEST_OF_THREE" | "BEST_OF_FIVE" | "SINGLE_SET" | "ADVANTAGE_SET"
  },
  "matchDate": "string (ISO 8601 format, optional)"
}
```

**Response (200 OK):**

```json
{
  "players": {
    "<playerId>": {
      "playerId": "string",
      "name": "string",
      "rating": {
        "value": number,
        "system": "NTRP" | "UTR"
      }
    }
  },
  "ratingChanges": {
    "<playerId>": {
      "change": number,
      "percentChange": number,
      "previousRating": {
        "value": number,
        "system": "NTRP" | "UTR"
      },
      "newRating": {
        "value": number,
        "system": "NTRP" | "UTR"
      }
    }
  }
}
```

**Error Response (400 Bad Request):**

```json
{
  "error": "Validation error",
  "message": "Detailed error message"
}
```

**Error Response (500 Internal Server Error):**

```json
{
  "error": "Internal server error",
  "message": "An unexpected error occurred"
}
```

---

## Data Models

### PlayerProfile

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `playerId` | string | Yes | Unique player identifier (max 50 chars, must match map key) |
| `name` | string | Yes | Player name (max 100 chars) |
| `rating` | Rating | Yes | Player's current rating |

### Rating

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `value` | number | Yes | Rating value |
| `system` | string | Yes | "NTRP" or "UTR" |

**Rating Constraints:**
- **NTRP**: 1.0 to 7.0 (continuous decimal values)
- **UTR**: >= 1.0 (continuous decimal values)

### MatchScore

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `sets` | SetScore[] | Yes | List of sets (1-5 sets) |
| `matchFormat` | string | No | Default: "BEST_OF_THREE" |

### SetScore

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `games` | Map<string, number> | Yes | Games won by each player |
| `winner` | string | Yes | Player ID of set winner |
| `tiebreak` | TiebreakScore | No | Tiebreak details if applicable |

**Set Score Validation:**
- Must have exactly 2 players
- Winner must win by at least 2 games (6-4, 6-3, etc.)
- Tiebreak sets must be 7-6 or 6-7
- Regular sets: winner must have at least 6 games

### TiebreakScore

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `points` | Map<string, number> | Yes | Points won by each player |
| `winner` | string | Yes | Player ID of tiebreak winner |

**Tiebreak Validation:**
- Winner must have at least 7 points
- Winner must win by at least 2 points

### RatingChange

| Field | Type | Description |
|-------|------|-------------|
| `change` | number | Absolute rating change |
| `percentChange` | number | Percentage change in rating |
| `previousRating` | Rating | Rating before match |
| `newRating` | Rating | Rating after match |

---

## Examples

### Example 1: Simple NTRP Match

**Request:**
```bash
curl -X POST http://localhost:8080/api/v1/calculate-ranking \
  -H "Content-Type: application/json" \
  -d '{
  "players": {
    "P123": {
      "playerId": "P123",
      "name": "John Doe",
      "rating": {"value": 4.5, "system": "NTRP"}
    },
    "P456": {
      "playerId": "P456",
      "name": "Jane Smith",
      "rating": {"value": 4.0, "system": "NTRP"}
    }
  },
  "matchScore": {
    "sets": [
      {"games": {"P123": 6, "P456": 4}, "winnerTeamId": "P123"},
      {"games": {"P123": 6, "P456": 3}, "winnerTeamId": "P123"}
    ]
  }
}'
```

**Response:**
```json
{
  "players": {
    "P123": {
      "playerId": "P123",
      "name": "John Doe",
      "rating": {"value": 4.5, "system": "NTRP"}
    },
    "P456": {
      "playerId": "P456",
      "name": "Jane Smith",
      "rating": {"value": 4.0, "system": "NTRP"}
    }
  },
  "ratingChanges": {
    "P123": {
      "change": 0.0,
      "percentChange": 0.0,
      "previousRating": {"value": 4.5, "system": "NTRP"},
      "newRating": {"value": 4.5, "system": "NTRP"}
    },
    "P456": {
      "change": 0.0,
      "percentChange": 0.0,
      "previousRating": {"value": 4.0, "system": "NTRP"},
      "newRating": {"value": 4.0, "system": "NTRP"}
    }
  }
}
```

*Note: Uses Elo-based ranking algorithm. See [RATING_CALCULATION_ALGORITHM.md](./RATING_CALCULATION_ALGORITHM.md) for details.*

### Example 2: UTR Match with Tiebreak

**Request:**
```bash
curl -X POST http://localhost:8080/api/v1/calculate-ranking \
  -H "Content-Type: application/json" \
  -d '{
  "players": {
    "P789": {
      "playerId": "P789",
      "name": "Mike Wilson",
      "rating": {"value": 8.5, "system": "UTR"}
    },
    "P101": {
      "playerId": "P101",
      "name": "Sarah Lee",
      "rating": {"value": 8.2, "system": "UTR"}
    }
  },
  "matchScore": {
    "sets": [
      {
        "games": {"P789": 7, "P101": 6},
        "tiebreak": {
          "points": {"P789": 7, "P101": 5},
          "winnerTeamId": "P789"
        },
        "winnerTeamId": "P789"
      },
      {
        "games": {"P789": 4, "P101": 6},
        "winnerTeamId": "P101"
      },
      {
        "games": {"P789": 6, "P101": 3},
        "winnerTeamId": "P789"
      }
    ]
  },
  "matchDate": "2024-01-15T14:30:00Z"
}'
```

**Response:**
```json
{
  "players": {
    "P789": {
      "playerId": "P789",
      "name": "Mike Wilson",
      "rating": {"value": 8.6, "system": "UTR"}
    },
    "P101": {
      "playerId": "P101",
      "name": "Sarah Lee",
      "rating": {"value": 8.1, "system": "UTR"}
    }
  },
  "ratingChanges": {
    "P789": {
      "change": 0.1,
      "percentChange": 1.18,
      "previousRating": {"value": 8.5, "system": "UTR"},
      "newRating": {"value": 8.6, "system": "UTR"}
    },
    "P101": {
      "change": -0.1,
      "percentChange": -1.22,
      "previousRating": {"value": 8.2, "system": "UTR"},
      "newRating": {"value": 8.1, "system": "UTR"}
    }
  }
}
```

---

## Validation Rules

### Request Validation

1. **Exactly 2 players required** for singles matches
2. **Map keys must match player IDs** in profiles
3. **Both players must use same rating system**
4. **Player IDs in scores must be valid** (exist in players map)
5. **Winner must be a valid player ID**

### Player Validation

1. **Player ID**: Non-blank, max 50 characters
2. **Name**: Non-blank, max 100 characters
3. **Rating value**: Must be valid for the rating system
4. **Rating system**: Must be "NTRP" or "UTR"

### Match Score Validation

1. **Sets**: 1-5 sets allowed
2. **Set games**: Winner must have at least 6 games
3. **Set margin**: Must win by at least 2 games (unless tiebreak)
4. **Tiebreak**: Winner must have at least 7 points and win by 2
5. **Set winner**: Must be one of the two players
6. **Consistency**: All sets must have the same players

---

## Error Codes

| Status Code | Description |
|-------------|-------------|
| 200 OK | Request successful |
| 400 Bad Request | Validation error or malformed JSON |
| 500 Internal Server Error | Unexpected server error |

---

## Rate Limiting

Currently no rate limiting is enforced.

---

## Testing

### Manual Testing

Use the provided test script:
```bash
./scripts/test-ranking-api.sh
```

### With curl

```bash
# Test health
curl http://localhost:8080/health

# Test ranking calculation
curl -X POST http://localhost:8080/api/v1/calculate-ranking \
  -H "Content-Type: application/json" \
  -d @test-data.json
```

### With HTTPie

```bash
# Install HTTPie
brew install httpie

# Test
http POST :8080/api/v1/calculate-ranking < test-data.json
```

---

## Current Limitations

1. **No Persistence**: Match results and player profiles are not stored
2. **No Authentication**: API is open without authentication
3. **No Rate Limiting**: Unlimited requests allowed
4. **Single-System Matches Only**: Cannot process matches between NTRP and UTR players

---

## Roadmap

### Phase 1: Core Functionality ✅
- ✅ Data model implementation
- ✅ API endpoint with validation
- ✅ Comprehensive test suite
- ✅ API documentation

### Phase 2: Ranking Algorithm ✅
- ✅ Performance-based Elo algorithm (K=0.16 NTRP, K=0.4 UTR)
- ✅ NTRP-specific and UTR-specific calculations
- ✅ Algorithm selection based on rating system
- ✅ Optional rating smoothing (see [RATING_SMOOTHING.md](./RATING_SMOOTHING.md))

### Phase 3: Persistence (In Progress)
- ✅ Database infrastructure (PostgreSQL, Flyway, Exposed)
- Store player profiles
- Store match results
- Historical ranking tracking

### Phase 4: Advanced Features
- Authentication & authorization
- Rate limiting
- Match metadata (tournament, surface, location)
- Statistical analysis
- API versioning

---

## Support

For issues, questions, or contributions:
- GitHub: [Repository URL]
- Documentation: `docs/` directory

---

**Version:** 0.0.1-SNAPSHOT
**Last Updated:** 2024-01-15
