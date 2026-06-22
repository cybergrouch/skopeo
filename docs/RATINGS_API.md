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

## Future work — onboarding ratings & provisional convergence

**Today's shortcut:** an administrator sets the initial seed rating. This assumes some signal
(personal/anecdotal knowledge) to base it on, and gets people onboard quickly. Its weakness is
the **zero-data player**: the admin has to guess. A bad guess *does* self-correct through play,
but it can take many matches to stabilize — a poor early experience for that player and noisy
inputs for their opponents in the meantime. We want a more efficient path before scaling
onboarding.

**Direction (not yet built).** Offer more than one way to obtain a starting rating, and make
new/uncertain ratings converge faster:

1. **Multiple seed sources**, each recorded on the rating (a `source` flag, e.g.
   `ADMIN_ASSESSED`, `SELF_RATED`, `MATCH_DERIVED`, and later imported/`UTR_SYNCED`):
   - *Admin-assessed* (current) — used when there's data; starts at higher confidence.
   - *Self-rated* — the player rates themselves at sign-up; **flagged as provisional** and
     started at low confidence (and surfaced as "provisional" in the UI).
   - *Automated/derived* — e.g. a short calibration questionnaire, or syncing an external UTR.

2. **Accelerated convergence for uncertain ratings.** A provisional/self-rated player's rating
   should move faster toward its true value. The simplest first cut is an **elevated K-factor
   for that user's first ~3–5 matches**. The more principled generalization — which the schema
   already anticipates via `user_ratings.confidence_score` (0–1) and `matches_played`, and the
   calculator's existing dynamic K + smoothing — is to **make K a function of confidence**
   (low confidence → high K), with confidence rising as matches accrue. Both onboarding paths
   then fall out of one mechanism: admin-assessed starts at higher confidence (small swings),
   self-rated starts low (fast convergence). This mirrors Elo's provisional period, Glicko's
   rating-deviation, and UTR's reliability.

3. **Protect opponents from noisy inputs.** A provisional player's result is uncertain input,
   so consider **down-weighting how much it moves the opponent's** rating until the provisional
   player is established (UTR discounts matches against unreliable opponents).

4. **Anti-gaming.** Self-rating invites manipulation (rate low to farm wins, or high to appear
   strong). Mitigations: the provisional flag + administrator review/benchmarking, rating
   floors/ceilings, and the accelerated convergence itself (a deliberate misrating corrects
   quickly). Keep an audit of the rating source and any admin overrides.

This stays compatible with the current API: the seed source and confidence are attributes of
`user_ratings` (the columns exist), and elevated/confidence-driven K is internal to the
calculator — no breaking changes to the endpoints above.
