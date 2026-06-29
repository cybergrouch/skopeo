# Ratings & Assessment API

How a user's ratings are stored, read, **assigned by raters/administrators**, and how players ask
for a re-rate. A user must have a rating before they can be entered into a match.

> The canonical, machine-verified contract is the OpenAPI spec
> (`src/main/resources/openapi/documentation.yaml`, verified by `OpenAPIIntegrationTest`). This
> page is the human-readable companion.

## Model

A user has at most one rating in `user_ratings`: a continuous `value` paired with its discrete
published `level` (e.g. `4.3` → level `4.0`), a `confidence` (0–1, low at first, converges with
play), `matchesPlayed`, and `lastMatchDate`. Rating changes accrue in `user_rating_history`
(written by the calculation trigger; readable here).

**Privacy (#114).** The exact `value` is withheld from players: only a rating manager
(ADMINISTRATOR) sees it. A player reading their own rating gets the published `level` plus a
normalized `bandPosition` (0..1 within the band) — never the raw number. The `PUT` setter echoes
the value back to the rater who just set it.

## Assessment policy

- **No auto-seed.** A new user has *no* rating and is **pending assessment**.
- **A RATER or ADMINISTRATOR sets a rating** (the initial assessment, or a later adjustment).
- A user pending assessment is **ineligible to be added to a match**. Raters/administrators get a
  paginated **pending-assessment list** to work through.

## Endpoints

| Method | Path | Access |
|---|---|---|
| `GET` | `/api/v1/users/{userId}/ratings` | self-or-admin — the user's rating (a list, 0 or 1) |
| `GET` | `/api/v1/users/{userId}/rating-history` | self-or-admin — history |
| `PUT` | `/api/v1/users/{userId}/ratings` | **RATER/ADMIN** — set/adjust `{ "value": "4.0", "confidence"?: "0.5" }` |
| `GET` | `/api/v1/users/pending-assessment?limit=&offset=` | **RATER/ADMIN** — users with no rating yet (paginated) |
| `POST` | `/api/v1/ratings/calculations` | **ADMIN** — trigger the rating calculation (dry-run by default) |

Setting a rating validates the NTRP range (1.0–7.0) and derives the published level via the
calculator's `Level` logic. Values are stored/returned at `NUMERIC(10,6)` precision (e.g.
`"4.000000"`); `confidence`, when supplied, must be in `[0, 1]`.

`pending-assessment` returns a page: `{ "items": [...], "total": N }`, where each item carries the
user's `publicCode`, `displayName`, `sex`, `dateOfBirth`/`age`, and any `proposedRating`.

### Status codes

`200` ok · `400` invalid rating value/confidence · `401` missing/invalid token · `403` not
self/admin (reads) or not a rating manager (set / pending list / calculations) · `404` no such user.

## Re-rate requests (#140)

A player can ask for their rating to be reconsidered; a RATER/ADMINISTRATOR triages the queue.

| Method | Path | Access |
|---|---|---|
| `POST` | `/api/v1/rating-requests` | player — raise one open request `{ "justification": "…" }` |
| `GET` | `/api/v1/rating-requests/me` | player — their latest request (`204` if none) |
| `GET` | `/api/v1/rating-requests?status=&limit=&offset=` | **RATER/ADMIN** — paginated list (filter by `PENDING`/`APPROVED`/`DENIED`) |
| `POST` | `/api/v1/rating-requests/{id}/approve` | **RATER/ADMIN** — apply a new rating `{ "rating": "4.5" }` |
| `POST` | `/api/v1/rating-requests/{id}/deny` | **RATER/ADMIN** — `{ "reason": "…" }` |

A player may have at most one **open** request at a time, and must already have a rating to raise
one. A `RatingRequestResponse` carries `id`, `userId`, `status`, `justification`, the approved
`newRating` (as a **published band only**, never the raw value — privacy #114), `reason`,
`resolvedAt`, `createdAt`, and (on the RATER list only) the resolved `requester`. The RATER list
response is a page: `{ "items": [...], "total": N }`.

### Status codes

`201` created · `200` ok · `204` no own request · `400` blank justification / no rating yet /
invalid NTRP / missing reason · `401` · `403` not a player / not a RATER · `404` no such request ·
`409` player already has an open request, or the request is already resolved.

## Rating calculation trigger

Recording match results never moves ratings — an administrator triggers the calculation
deliberately:

`POST /api/v1/ratings/calculations` (ADMINISTRATOR), body `{ "dryRun": true }`.

- Gathers the matches **pending calculation** (active, `COMPLETED`, unrated) and processes them
  **oldest→newest by `completedAt`** against an in-memory `(user) → rating` snapshot — seeded from
  stored ratings and **carried forward** so the chain is correct (match N uses the rating match
  N-1 produced). Each match reuses the stateless `RankingCalculator`.
- **`dryRun` defaults to `true`** (an empty/absent/unparseable body is a dry run): returns the full
  per-match, per-player preview (`previousRating`/`newRating`/`change`/`percentChange`/level plus
  the calculator `breakdown`) with **zero writes**. Only an explicit `{"dryRun": false}` commits.
- **Commit** (`dryRun: false`) persists in one transaction: updates `user_ratings` (new
  rating/level, `matchesPlayed++`, `lastMatchDate`), appends `user_rating_history` (with
  `matchId` + stored breakdown), and stamps each match `ratedAt`/`ratedBy`. **Idempotent** — a
  re-run finds nothing pending. SINGLES only for now (the calculator's scope).
- Response shape: `{ "dryRun": bool, "matchesProcessed": N, "matches": [ { "matchId", "matchDate",
  "changes": [...] } ] }`.

Layering: `service/rating/RatingCalculationService.kt` orchestrates; `RatingRepository` performs
the match-driven write + history append, `MatchRepository` the `markRated` stamp.

## Layering

`routes/RatingRoutes.kt` + `routes/RatingRequestRoutes.kt` (thin) → `service/rating/*` (authz,
range/level derivation, request triage) → `repository/RatingRepository.kt` +
`RatingRequestRepository.kt` (Exposed over `user_ratings` / `user_rating_history` /
`rating_requests`).

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
   `ADMIN_ASSESSED`, `SELF_RATED`, `MATCH_DERIVED`, and later imported/`EXTERNAL_SYNCED`):
   - *Admin-assessed* (current) — used when there's data; starts at higher confidence.
   - *Self-rated* — the player rates themselves at sign-up; **flagged as provisional** and
     started at low confidence (and surfaced as "provisional" in the UI).
   - *Automated/derived* — e.g. a short calibration questionnaire, or syncing an external rating.

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

### Prior art — how established systems handle uncertainty

The common thread across these systems: **scale each update by the rating's uncertainty, flag
unverified inputs, and limit how much a noisy newcomer perturbs everyone else.**

- **Elo (provisional period).** A larger K-factor while few games have been played, shrinking as
  the record grows. FIDE uses `K = 40` for a player's first 30 rated games (and juniors below a
  cutoff), `K = 20` thereafter, and `K = 10` once rated ≥ 2400; the USCF uses an effective K that
  decreases with games played. Mechanism: **K large early → fast convergence → smaller later.**

- **Glicko / Glicko-2 (Glickman).** The principled version of the above: each rating carries a
  **Rating Deviation (RD)** — an explicit uncertainty, defaulting to ~350 for a new player. The
  size of each update scales with the player's *and* the opponent's RD; RD shrinks with play and
  grows during inactivity. Glicko-2 adds a **volatility** term for how erratic results are.
  Mechanism: **uncertainty is a stored number that drives swing size and self-reduces** — exactly
  the `confidence_score`-driven K we'd generalize to.

- **UTR (Universal Tennis Rating).** Each rating has a **reliability** (0–100%) derived from the
  count and recency of recent results (roughly the last ~30 matches within 12 months). Low
  reliability → the rating moves more; and **results against low-reliability opponents are
  discounted** when rating others. New players get an estimate that's refined by play.
  Mechanism: reliability gates **both** a player's own swing size **and** their influence on the
  pool.

- **USTA NTRP.** New players enter **self-rated** (flagged `S`) using published NTRP guidelines.
  A dynamic in-season rating (NTRP-D) updates per match; a year-end computer rating (`C`)
  consolidates it. Mis-self-rates are corrected via appeals, benchmarking, and dynamic/
  "three-strikes" disqualification. Mechanism: **flagged self-rate + dynamic correction +
  human/benchmark review.**

### K-factor calibration (proposal — keep for a later item)

Today the calibration anchor is `K = 0.16` for NTRP; see
[`RATING_CALCULATION_ALGORITHM.md`](../../product/RATING_CALCULATION_ALGORITHM.md) §3.3. A shutout between
equals moves a rating by the full `±0.160`, which may be **too large a step** for an established
player.

Proposal to evaluate later: because ratings are computed to 6 decimal places, there's room to
**shrink the established-player K by an order of magnitude — `0.16 → 0.016`**,
giving steadier, finer movement once a rating is trustworthy. Then reuse the
old value as the **provisional K-factor `0.16` (~10× the new baseline)** so unverified/new
ratings still converge aggressively. This dovetails with the confidence-driven-K idea above:
`0.016` is the low-uncertainty floor, `0.16` the high-uncertainty ceiling, with
`confidence_score` interpolating between them.

Caveat: K is the calibration anchor for the whole algorithm — changing it shifts every derived
per-match delta and the worked tables in `RATING_CALCULATION_ALGORITHM.md` (§3.3, dominance and
threshold tables). So this is a **calibration exercise**, not a one-line constant change: re-derive
the expected per-match changes, re-validate convergence speed, and refresh the documented tables.

### Convergence-based graduation (proposal — keep for a later item)

An alternative/companion to tuning K: keep a provisional rating and **detect when it has
converged** by watching the per-match delta sequence, graduating to "established" once movement
stabilizes.

- **Sound basis.** A mis-seeded rating yields systematically same-signed, decaying deltas (all
  gains if underrated, all losses if overrated); as it nears truth the deltas shrink and start
  alternating sign around zero. That trend is a real convergence signal — the hand-rolled
  analogue of Glicko's shrinking Rating Deviation.
- **Fix the threshold.** Graduation should *not* be "|delta| < K". K is the *maximum* single-match
  move; typical competitive deltas are already 0.03–0.16 (below K), and a mis-seeded player
  upsetting stronger opponents can swing up to ~2K. So "fell below K" only means "no longer
  producing upset-sized swings" — a first milestone, not convergence. Graduate on a tighter
  signal: the **drift** (mean of recent deltas) within ε of zero, with **ε ≪ K**, and/or deltas
  alternating sign — equivalent to "RD below a cutoff."
- **Avoid the extrapolation jump.** Curve-fitting the limit `r∞` from 3–5 noisy results is
  statistically fragile (over-fits noise, can overshoot to a confidently-wrong value) and injects
  a rating not earned by results. It's also just a brittle way to get the acceleration that a
  higher provisional K / RD provides robustly. Prefer letting results move the rating and use the
  delta analysis only to decide *when* to graduate.
- **Sample size & opponents.** 3–5 matches is too few given tennis variance — expect ~5–15, or
  gate on an uncertainty estimate rather than a fixed count, and only count matches against
  reliable, varied opponents.

**Synthesis of the last three sections.** Treat these as two separate jobs:
*acceleration* (how fast the rating moves toward truth) is best handled data-driven — a higher
provisional / confidence-driven K (or Glicko RD); *graduation* (when to call it established) is
best handled by the convergence/drift threshold above. The curve-fit "jump" tries to do both at
once and is the part to drop.
