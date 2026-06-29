# Skopeo API Documentation

## Scope of this document

The **canonical, complete API reference is the OpenAPI spec**:
`src/main/resources/openapi/documentation.yaml` (hand-maintained and verified by
`OpenAPIIntegrationTest`). Serve/browse it via the running app's docs route, or open the YAML
directly.

This page covers only the **stateless ranking calculator** (`POST /api/v1/calculate-ranking`) — a
pure "what-if" endpoint that persists nothing. For the persistent API surface, see the spec and the
per-resource companions:

- [RATINGS_API.md](RATINGS_API.md) — ratings, assessment, re-rate requests, calculation trigger
- [MATCHES_API.md](MATCHES_API.md) — fixtures, results, public match pages, events tie-in
- [CAPABILITIES_API.md](CAPABILITIES_API.md) — roles (PLAYER/HOST/CLUB_OWNER/RATER/RESEARCHER/ADMINISTRATOR)
- [CONTACT_INFORMATION_API.md](CONTACT_INFORMATION_API.md) · [USER_NAMES_API.md](USER_NAMES_API.md) — user sub-resources
- [AUDIT_TRAIL.md](../architecture/AUDIT_TRAIL.md) — calculator audit trail **and** the domain audit/activity log

**Base URL (local):** `http://localhost:8080`

---

## Stateless ranking calculator

### POST `/api/v1/calculate-ranking`

A pure, side-effect-free calculation: given two teams and a match score, returns each player's
updated rating and the rating change. It **stores nothing** — no auth, no persistence. NTRP only,
SINGLES only.

**Request Headers:** `Content-Type: application/json`

**Request Body:**

```json
{
  "teams": {
    "T1": {
      "teamId": "T1",
      "name": "John Doe",
      "teamType": "SINGLES",
      "players": [
        { "playerId": "P123", "name": "John Doe", "rating": { "value": "4.5" } }
      ]
    },
    "T2": {
      "teamId": "T2",
      "name": "Jane Smith",
      "teamType": "SINGLES",
      "players": [
        { "playerId": "P456", "name": "Jane Smith", "rating": { "value": "4.0" } }
      ]
    }
  },
  "matchScore": {
    "sets": [
      {
        "games": { "T1": 7, "T2": 6 },
        "winnerTeamId": "T1",
        "tiebreak": { "points": { "T1": 7, "T2": 5 }, "winnerTeamId": "T1" }
      }
    ]
  },
  "matchDate": "2026-01-15T14:30:00Z",
  "options": { "smoothingEnabled": true, "smoothingFactor": 0.5, "matchTypeFactor": 1.0 }
}
```

- `teams` must be **exactly two SINGLES teams of one player each**; the map key must equal each
  team's `teamId`, and each team carries a `name` and a one-element `players` list.
- **`games`, `winnerTeamId`, and tiebreak `points`/`winnerTeamId` are keyed by *team id*** (not
  player id). Winner/loser are derived from the scores when omitted.
- `matchScore` has no `matchFormat` field. `matchDate` and `options` are optional. A `Rating`'s
  `value` is a **string** (e.g. `"4.5"`).
- `tiebreak` only decides the set winner when games are level; tiebreak points do **not** feed the
  dominance calculation.

**Response (200 OK):**

```json
{
  "ratingChanges": {
    "P123": {
      "change": "0.080000",
      "previousRating": { "value": "4.500000" },
      "newRating": { "value": "4.580000" },
      "percentChange": "1.78",
      "levelChanged": false
    }
  },
  "players": { "...": "..." },
  "teams": { "...": "..." }
}
```

`ratingChanges` is keyed by **player id**; `players` and `teams` echo the (updated) inputs. Numeric
fields are strings.

**Errors:** `400` validation error / malformed JSON · `500` unexpected server error. Bodies are
`{ "error": "...", "message": "..." }`.

### Example

```bash
curl -X POST http://localhost:8080/api/v1/calculate-ranking \
  -H "Content-Type: application/json" \
  -d '{
    "teams": {
      "T1": { "teamId": "T1", "name": "John Doe",  "teamType": "SINGLES", "players": [ { "playerId": "P123", "name": "John Doe",  "rating": { "value": "4.5" } } ] },
      "T2": { "teamId": "T2", "name": "Jane Smith", "teamType": "SINGLES", "players": [ { "playerId": "P456", "name": "Jane Smith", "rating": { "value": "4.0" } } ] }
    },
    "matchScore": {
      "sets": [
        { "games": { "T1": 6, "T2": 4 }, "winnerTeamId": "T1" },
        { "games": { "T1": 6, "T2": 3 }, "winnerTeamId": "T1" }
      ]
    }
  }'
```

Ratings are NTRP (1.0-7.0), computed to 6 decimal places. The algorithm is documented in
[RATING_CALCULATION_ALGORITHM.md](../../product/RATING_CALCULATION_ALGORITHM.md); optional smoothing
in [RATING_SMOOTHING.md](../../product/RATING_SMOOTHING.md).

---

## Health & monitoring

- `GET /health` — liveness/version JSON.
- `GET /metrics` — Prometheus metrics (JVM, HTTP, application). See
  [LOGGING_AND_METRICS.md](../operations/LOGGING_AND_METRICS.md).

---

## Authentication

The persistent API (everything except `/api/v1/calculate-ranking`, `/health`, `/metrics`) requires
a **Firebase ID token** (`Authorization: Bearer <token>`) and capability-based authorization. See
[AUTHENTICATION.md](../architecture/AUTHENTICATION.md) and
[CAPABILITIES_API.md](CAPABILITIES_API.md).
