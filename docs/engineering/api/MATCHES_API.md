# Matches API

Match **fixtures** and **results**, recorded deliberately and **append-only**. Recording a
result does **not** move ratings — that's a separate, previewable calculation trigger (see
[`RATINGS_API.md`](RATINGS_API.md) → *Rating calculation trigger*).

> The canonical, machine-verified contract is the OpenAPI spec
> (`src/main/resources/openapi/documentation.yaml`, verified by `OpenAPIIntegrationTest`). This
> page is the human-readable companion.

## Two-dimension match model (#108)

Every match is described by two independent axes:

- **`matchFormat`** — the team shape: `SINGLES` (1 player/side), `DOUBLES`, or `MIXED_DOUBLES`
  (2 players/side). *Only `SINGLES` is exercised by the rating calculator today*; doubles
  fixtures can be recorded but the calculator's scope is singles.
- **`matchType`** — the competitive context, which scales how much a result moves a rating:
  `OPEN_PLAY`, `LEAGUE_PLAY`, `TOURNAMENT_INITIAL_ROUND`, `LEAGUE_PLAYOFFS`,
  `TOURNAMENT_PLAYOFFS` (rising pressure → larger factor; see
  [`RATING_CALCULATION_ALGORITHM.md`](../../product/RATING_CALCULATION_ALGORITHM.md)).

## Three-phase lifecycle

1. **Fixture** — a HOST/ADMINISTRATOR schedules a match (participants + `matchFormat`,
   `matchType`, `matchDate`); `status=SCHEDULED`, no winner, no scores. Every participant must
   already be rated (a *pending-assessment* user can't be entered).
2. **Results** — a HOST/ADMINISTRATOR uploads the set scores; the server derives per-set and
   match winners, sets `status=COMPLETED` and `completedAt`. Now *pending calculation*.
3. **Calculation** — an administrator triggers the rating computation over pending matches; until
   then `ratedAt` is null. See *Rating calculation trigger* in [`RATINGS_API.md`](RATINGS_API.md).

Every match also carries a short, shareable **`publicCode`** that addresses its read-only public
page (see `GET /api/v1/matches/code/{code}`).

## Events (#138)

A fixture may belong to an **event** (a meet/tournament) via the optional `eventId`. When set,
both sides must already be participants of that event. Events are managed under `/api/v1/events`
(create/list/get + participant management; HOST/ADMINISTRATOR). The match list can be scoped to an
event's awaiting fixtures with `?eventId=`.

## Append-only & corrections

Matches are immutable like contacts/names. Corrections **disable the original and add a new one**
(`PUT /matches/{id}/state {isActive:false}`), preserving the audit trail. Disabling is allowed
**only while the match is unrated** (`ratedAt` is null); once committed into ratings it locks.

## Endpoints

| Method | Path | Access | Notes |
|---|---|---|---|
| `GET` | `/api/v1/matches?filter=…` | HOST/ADMIN | oversight views (below); HOST sees own fixtures, ADMIN sees all. `?eventId=` scopes `awaiting-results` |
| `POST` | `/api/v1/matches` | HOST/ADMIN | create fixture (`SCHEDULED`) |
| `GET` | `/api/v1/matches/code/{code}` | any authenticated user | public match page by code (#136) |
| `GET` | `/api/v1/matches/{id}` | participant or HOST/ADMIN | full match detail |
| `GET` | `/api/v1/matches/{id}/calculation` | participant or HOST/ADMIN | the match result + its stored rating calculation (#97) |
| `POST` | `/api/v1/matches/{id}/result` | HOST/ADMIN | upload scores → `COMPLETED`, pending calc |
| `PUT` | `/api/v1/matches/{id}/state` | HOST/ADMIN | enable/disable (disable only while unrated) |

All endpoints require a Firebase token.

### Oversight views (`GET /api/v1/matches?filter=`)

`filter` is **required**, one of:

- `pending-calculation` — active, `COMPLETED`, unrated, ordered by `completedAt` (what the
  calculation trigger will process).
- `awaiting-results` — active, `SCHEDULED` fixtures overdue for results. With `?eventId=` it
  returns that event's awaiting fixtures to any staff member.

An ADMINISTRATOR sees every match in the view; a HOST sees only the fixtures they created.

## Create a fixture

`POST /api/v1/matches` (HOST/ADMINISTRATOR)

```json
{
  "matchFormat": "SINGLES",
  "matchType": "LEAGUE_PLAY",
  "matchDate": "2026-06-20",
  "team1": ["11111111-1111-1111-1111-111111111111"],
  "team2": ["22222222-2222-2222-2222-222222222222"],
  "venue": "Center Court",
  "tournamentName": "Spring League",
  "eventId": "33333333-3333-3333-3333-333333333333"
}
```

`venue`, `tournamentName`, and `eventId` are optional. The response is a `MatchResponse`
(`status: "SCHEDULED"`, no scores, with the generated `id` and `publicCode`).

## Upload results

`POST /api/v1/matches/{id}/result` (HOST/ADMINISTRATOR)

```json
{
  "sets": [
    { "team1Games": 6, "team2Games": 4 },
    { "team1Games": 7, "team2Games": 6, "tiebreakTeam1Points": 7, "tiebreakTeam2Points": 5 }
  ]
}
```

At least one set is required; games must be non-negative. The server derives the winner and
returns the updated `MatchResponse` (`status: "COMPLETED"`, `completedAt` set, per-set
`winnerTeamId`, and `ratedAt` still null).

### Winner derivation

Per set: more games wins; if games are equal, the tiebreak points decide; otherwise the set is
rejected. The match winner is whoever won more sets; a tie (no clear winner) is rejected.

## Enable / disable a match

`PUT /api/v1/matches/{id}/state` (HOST/ADMINISTRATOR)

```json
{ "isActive": false }
```

## `MatchResponse` (shape)

```json
{
  "id": "…",
  "publicCode": "M-AB12CD",
  "matchFormat": "SINGLES",
  "matchType": "LEAGUE_PLAY",
  "matchDate": "2026-06-20",
  "status": "COMPLETED",
  "team1": { "teamId": "…", "userIds": ["…"] },
  "team2": { "teamId": "…", "userIds": ["…"] },
  "winnerTeamId": "…",
  "sets": [
    { "setNumber": 1, "team1Games": 6, "team2Games": 4, "winnerTeamId": "…" }
  ],
  "venue": "Center Court",
  "tournamentName": "Spring League",
  "isActive": true,
  "completedAt": "2026-06-20T18:30:00",
  "ratedAt": null,
  "createdBy": "…",
  "recordedBy": "…",
  "eventId": "…"
}
```

`status` is one of `SCHEDULED`, `IN_PROGRESS`, `COMPLETED`, `CANCELLED`.

## Public match page (#136)

`GET /api/v1/matches/code/{code}` — visible to any authenticated user (the same "public"
semantics as a player's public profile). Returns a `MatchPublicResponse`: no internal team/user
ids, players resolved to `displayName` + `publicCode`, and the winner named by side
(`"TEAM1"` | `"TEAM2"` | `"NONE"`).

```json
{
  "publicCode": "M-AB12CD",
  "matchFormat": "SINGLES",
  "matchType": "LEAGUE_PLAY",
  "matchDate": "2026-06-20",
  "status": "COMPLETED",
  "team1": [{ "displayName": "John D.", "publicCode": "P-7788" }],
  "team2": [{ "displayName": "Jane S.", "publicCode": "P-9900" }],
  "winner": "TEAM1",
  "sets": [{ "setNumber": 1, "team1Games": 6, "team2Games": 4 }],
  "venue": "Center Court",
  "tournamentName": "Spring League"
}
```

## Match calculation detail (#97)

`GET /api/v1/matches/{id}/calculation` — the match result plus the per-player rating calculation
**persisted at commit time** (not recomputed, so it stays faithful even if the algorithm constants
change). Limited to a participant or HOST/ADMINISTRATOR. Returns a `MatchCalculationDetailResponse`
(`match` + `changes[]` with `previousRating`/`newRating`/`change`/level + optional per-set
`breakdown`). `404` if no calculation has been committed for the match yet.

### Status codes

`200`/`201` · `400` invalid input (bad ids/date, unrated participant, no clear winner, bad team
composition, missing/invalid filter) · `401` missing/invalid token · `403` not staff / not a
participant · `404` no such match (or no committed calculation) · `409` results already uploaded,
disabling a rated match, or a disabled match.

## Layering

`routes/MatchRoutes.kt` (thin) → `service/match/MatchService.kt` (authz, validation, winner
derivation) → `repository/MatchRepository.kt` (Exposed over teams/team_users/matches/match_sets/
tiebreaks). The stateless `/api/v1/calculate-ranking` remains as a what-if preview.
