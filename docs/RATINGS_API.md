# Ratings & Assessment API (PR1 of #4)

How a user's ratings are stored, read, and **assigned by administrators**. This is the
foundation for match-driven rating changes (PR2): a user must have a rating before they can be
entered into a match.

## Model

A user has at most one rating **per system** (`NTRP`, `UTR`) in `user_ratings`: a continuous
`value` paired with its discrete published `level` (e.g. `4.3` → level `4.0`), a `confidence`
(0–1, low at first, converges with play), `matchesPlayed`, and `lastMatchDate`. Rating changes
accrue in `user_rating_history` (written by the match flow in PR2; readable here).

## Assessment policy

- **No auto-seed.** A new user has *no* rating and is **pending assessment**.
- **Only an ADMINISTRATOR sets a rating** (the initial assessment, or a later adjustment).
- A user pending assessment is **ineligible to be added to a match** (enforced by the match
  flow in PR2). Administrators get a **pending-assessment list** to work through.

## Endpoints

| Method | Path | Access |
|---|---|---|
| `GET` | `/api/v1/users/{userId}/ratings` | self-or-admin — current NTRP/UTR ratings |
| `GET` | `/api/v1/users/{userId}/rating-history` | self-or-admin — history (`?system=NTRP`) |
| `PUT` | `/api/v1/users/{userId}/ratings/{system}` | **admin only** — set/adjust `{ "value": "4.0", "confidence"?: "0.5" }` |
| `GET` | `/api/v1/users/pending-assessment` | **admin only** — users with no rating yet |

Setting a rating validates the system range (NTRP 1.0–7.0, UTR 1.0–16.0) and derives the
published level via the calculator's `Level` logic. Values are stored/returned at `NUMERIC(10,6)`
precision (e.g. `"4.000000"`).

### Status codes

`200` ok · `400` invalid rating value/confidence or unknown system · `401` missing/invalid
token · `403` not self/admin (reads) or not admin (set / pending list) · `404` no such user.

## Layering

`routes/RatingRoutes.kt` (thin) → `service/rating/RatingService.kt` (authz, range/level
derivation) → `repository/RatingRepository.kt` (Exposed over `user_ratings` /
`user_rating_history`). No schema migration — the tables already exist (V1).

## Next (PR2 — fixtures & results)

A HOST/ADMINISTRATOR creates a **match fixture** (participants, who must already be rated;
system; date) then later **uploads results**, which runs the calculator on the stored ratings
and writes the new ratings + history. Adds the `matches`/`teams`/`match_sets` mappings and a
small migration (nullable `winner_team_id` for scheduled fixtures + `created_by`/`recorded_by`).
