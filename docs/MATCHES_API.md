# Matches API (PR2a of #4)

Match **fixtures** and **results**, recorded deliberately and **append-only**. Recording a
result does **not** move ratings — that's a separate, previewable calculation trigger (PR2b).

## Three-phase lifecycle
1. **Fixture** — a HOST/ADMINISTRATOR schedules a match (participants + proposed
   `matchDate`); `status=SCHEDULED`, no winner, no scores. Every participant must already be
   rated (a *pending-assessment* user can't be entered).
2. **Results** — a HOST/ADMINISTRATOR uploads the set scores; the server derives per-set and
   match winners, sets `status=COMPLETED` and `completed_at`. Now *pending calculation*.
3. **Calculation** (PR2b) — an administrator triggers the rating computation over pending
   matches; until then `rated_at` is null.

## Append-only & corrections
Matches are immutable like contacts/names. Corrections **disable the original and add a new
one** (`PUT /matches/{id}/state {isActive:false}`), preserving the audit trail. Disabling is
allowed **only while the match is unrated** (`rated_at IS NULL`); once committed into ratings it
locks (reversal is the deferred recompute problem — the dry-run preview in PR2b is what guards
against bad commits).

## Endpoints

| Method | Path | Access | Notes |
|---|---|---|---|
| `POST` | `/api/v1/matches` | HOST/ADMIN | create fixture (`SCHEDULED`) |
| `POST` | `/api/v1/matches/{id}/result` | HOST/ADMIN | upload scores → `COMPLETED`, pending calc |
| `PUT` | `/api/v1/matches/{id}/state` | HOST/ADMIN | enable/disable (disable only while unrated) |
| `GET` | `/api/v1/matches/{id}` | participant or HOST/ADMIN | detail |
| `GET` | `/api/v1/matches?filter=…` | **ADMIN** | oversight views (below) |

### Oversight views (`GET /api/v1/matches?filter=`)
- `pending-calculation` — active, `COMPLETED`, `rated_at IS NULL`, ordered by `completed_at` (what the PR2b trigger will process).
- `awaiting-results` — active, `SCHEDULED`, `match_date` in the past (fixtures overdue for results).

### Winner derivation
Per set: more games wins; if games are equal, the tiebreak points decide; otherwise the set is
rejected. The match winner is whoever won more sets; a tie (no clear winner) is rejected.

### Status codes
`200`/`201` · `400` invalid input (bad ids/date, unrated participant, no clear winner,
bad team composition) · `401` · `403` not staff / not a participant · `404` no such match ·
`409` results already uploaded, disabling a rated match, or a disabled match.

## Schema — Flyway `V6`
On `matches`: `winner_team_id` → nullable; add `completed_at`, `rated_at`, `is_active` /
`disabled_at`, and audit FKs `created_by` / `recorded_by` / `rated_by`; partial indexes for the
two oversight views. (Teams are created per-match as temporary teams.)

## Layering
`routes/MatchRoutes.kt` (thin) → `service/match/MatchService.kt` (authz, validation, winner
derivation) → `repository/MatchRepository.kt` (Exposed over teams/team_users/matches/match_sets/
tiebreaks). The existing stateless `/calculate-ranking` remains as a what-if preview.

## Rating calculation trigger (PR2b)

Recording results never moves ratings — an administrator triggers the calculation deliberately:

`POST /api/v1/ratings/calculations` (ADMINISTRATOR), body `{ "dryRun": true }`.

- Gathers the matches **pending calculation** (active, `COMPLETED`, `rated_at IS NULL`) and
  processes them **oldest→newest by `completed_at`** against an in-memory `(user) → rating`
  snapshot — seeded from stored ratings and **carried forward** so the chain is correct (match N
  uses the rating match N-1 produced). Each match reuses the existing `RankingCalculator`.
- **`dryRun` defaults to `true`** (an empty/absent body is a dry run): returns the full per-match,
  per-player preview (`previousRating`/`newRating`/`change`/`percentChange`/level) with **zero
  writes**. Only an explicit `{"dryRun": false}` commits.
- **Commit** (`dryRun: false`) persists in one transaction: updates `user_ratings` (new
  rating/level, `matches_played++`, `last_match_date`), appends `user_rating_history` (with
  `match_id`), and stamps each match `rated_at`/`rated_by`. **Idempotent** — a re-run finds nothing
  pending. SINGLES only for now (the calculator's scope).

Layering: `service/rating/RatingCalculationService.kt` orchestrates; `RatingRepository` gains the
match-driven write + history append, `MatchRepository` the `markRated` stamp.
