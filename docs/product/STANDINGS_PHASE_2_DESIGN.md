# Standings Phase 2 — Points-Based Ranking (Design Discussion)

**Status:** 🟡 Draft / discussion — decisions below are *tentative*; open questions are called out explicitly.
**Issues:** [#146](https://github.com/cybergrouch/skopeo/issues/146) (independent points-based ranking), [#220](https://github.com/cybergrouch/skopeo/issues/220) (persist a recompute-on-change snapshot; per-band selector, paging, jump-to-me). Builds on [#113](https://github.com/cybergrouch/skopeo/issues/113) (Phase 1, ratings-derived standings) and [#212](https://github.com/cybergrouch/skopeo/issues/212) (sex split).

This document captures the technical points, options, and (tentative) decisions from the ongoing discussion about how Standings should work in Phase 2. It is a living design doc, not a spec — edit as decisions firm up.

---

## 1. Where we are vs. where we're going

- **Phase 1 (shipped, interim):** the Standings leaderboard is **derived from the player's rating** — each NTRP band is sorted by rating. It's recomputed on every read.
- **Phase 2 (this doc):** Standings become a **separate, points-based ranking that is independent of rating**. Players accumulate **ranking points** from tournaments/leagues/open-play/external grants; each NTRP band (× sex, #212) runs its own race ordered by points. The result is **precomputed, persisted, and published on a schedule**, not recomputed per request.

The two issues converge: #146 defines *what the number is* (accumulated points), #220 defines *how it's served* (a persisted snapshot, paged, with jump-to-me). Phase 2 is both.

---

## 2. Requirements distilled from the discussion

1. **Points-based, not rating-derived.** A player's standing = sum of their currently-valid ranking points, ranked within their band.
2. **Flexible award & removal — including from outside the ecosystem.** Points can be granted or removed **ad hoc**, even when the source isn't a system-tracked event (e.g. an external group asks us to credit players for an event we don't run). Everything is **traceable** (who granted, when, why, source).
3. **Expirable points, per-class validity.** Every award has a validity window (start/end). Different **classes** of points expire on different horizons:
   - Annual tournaments → ~12 months (bridging to next year's edition).
   - Seasonal tournaments (summer, August, December, Valentine's, …) → shorter: ~1 / 3 / 6 months.
   - Open play → points pegged to **dominance** and **match-up competitiveness** (competitive vs uneven), with their own (likely short) validity.
   - External / ad-hoc grants → validity set per grant.
4. **ATP/WTA-style tournament guarantees.** A tournament can **promise** points for reaching a stage (winner, finalist, semifinalist, quarterfinalist, R16, …) — a per-tournament points table.
5. **Scheduled, non-real-time computation.** Like ATP/WTA's fortnightly ranking release, Standings are **recomputed and published on a regular cadence**, not on every page view.
6. **Adjust-before-apply (dry-run → commit).** Mirror the ratings-calculation pattern: preview the recomputed standings, allow adjustments to points, then commit/publish. (See `RatingCalculationService`.)
7. **Per-band (× sex) races**, paginated, with a jump-to-me shortcut (#220).

---

## 3. Core insight: **expiry makes time a first-class input**

Phase 1 (and #220's original framing) assumes standings change **only when a rating changes**, so the snapshot can be rebuilt on that event. **Phase 2 breaks that assumption**: because points **expire**, a player's total — and therefore the ranking — changes **as time passes even with no new awards**.

➡️ **Therefore a purely event-triggered rebuild is insufficient.** Phase 2 *requires* a **time-based scheduled recompute** (e.g. fortnightly). This is the single most important architectural consequence and it's why #220's "recompute only on ratings change" evolves into "recompute on a schedule (and optionally on demand)".

---

## 4. Proposed domain model

### 4.1 The points ledger (`ranking_point_awards`) — the heart of the design

An **append-only ledger** of point awards (mirrors how rating history / audit are modeled — never mutate, only append + revoke). Each row:

| Field | Notes |
|---|---|
| `id` | PK |
| `user_id` | the player earning/losing points |
| `points` | the amount. **Open question:** integer (ATP-like) vs `BigDecimal` (needed if open-play dominance yields fractional points). Leaning `BigDecimal` for flexibility, formatted for display. |
| `point_class` | e.g. `ANNUAL_TOURNAMENT`, `SEASONAL_TOURNAMENT`, `OPEN_PLAY`, `EXTERNAL`/`AD_HOC` (see §4.2) |
| `source_type` / `source_id` | provenance: an internal event/tournament id, or `EXTERNAL` with free-text/ref. Nullable id for external. |
| `band` | **Open question** — see §5 (band tagging). |
| `sex` | for the (band, sex) race split (#212). Usually the player's; snapshot-time vs award-time is a decision. |
| `reason` | human-readable justification (required for ad-hoc/external grants) |
| `valid_from` / `valid_until` | validity window. Defaulted from `point_class` policy, overridable per award. |
| `status` | `ACTIVE` / `REVOKED` (revocation is an append, not a delete) |
| `granted_by` | actor (user id) — the audit actor |
| `awarded_at` | when the row was created |

**Removal / adjustment** = append a `REVOKED` marker (or a negative-points award), never a hard delete — keeps the ledger traceable. A player's contributing total on a given `asOf` date = `Σ points WHERE status=ACTIVE AND asOf ∈ [valid_from, valid_until)`.

### 4.2 Point classes & validity policy

A **`point_class`** carries a default validity duration and semantics. Options for where this lives:
- (a) An enum + a config/policy map (`ANNUAL_TOURNAMENT → 12mo`, `SEASONAL_3M → 3mo`, …). Simple, versioned in code.
- (b) A `point_classes` table (admin-editable durations). More flexible, more moving parts.

**Tentative:** start with (a) an enum + policy defaults, with **per-award override** of `valid_from`/`valid_until` (covers the "each class has its own validity, but a specific tournament can set its own window" need). Revisit a table if admins need to tune classes without a deploy.

### 4.3 Tournament points tables (ATP/WTA-style) — *depends on the tournaments model*

A tournament defines a **points schedule by finishing stage**: `{ WINNER: 500, FINALIST: 300, SF: 180, QF: 90, R16: 45, … }`. When results finalize, each participant is awarded the points for the stage they reached — as ledger rows of the tournament's `point_class` with the tournament's validity window.

⚠️ **Dependency (#146):** this needs a **tournaments/leagues model that does not yet exist** (relates to #108 occasions, #111 seeding). The ledger + ad-hoc/external grants can ship *without* it; tournament auto-awards come once that model lands. See phasing (§9).

### 4.4 Open-play points

Points pegged to **dominance** (margin) and **match-up type** (competitive / uneven). We already compute dominance for ratings (`RATING_CALCULATION_ALGORITHM.md`) — the open-play points formula can reuse those signals. **Open question:** the exact formula and its validity horizon. Likely a small, short-lived award per rated open-play match.

### 4.5 The standings snapshot

The published ranking = per `(band, sex)`, players ordered by their **valid points total** as of the computation date, ranked, paginated. Persisted (see §6). This is what reads serve (#220).

---

## 5. Band assignment — **open decision** (#146)

When a player's rating moves them to a new NTRP band, what happens to their points? Options:

- **(A) Band-agnostic total, ranked in current band.** A player has one points total; they appear in whatever band they currently sit in. Simplest; but a player promoted mid-season carries points earned against weaker opponents into a stronger band.
- **(B) Points tagged with the band they were earned in.** Each award records its band; a band's race only counts points earned *in that band*. Truest to "each band is its own race," and natural for tournaments (you enter a tournament at a band). Downside: a player straddling a promotion has split totals.
- **(C) Hybrid / season-scoped.** Points are band-tagged but a promotion mid-season carries or resets per a rule.

**Tentative lean:** **(B) band-tagged awards** — it fits tournaments (band-specific draws), the "each band its own race" goal, and expiry naturally retires old-band points. Record `band` on each award at award time. Revisit if it feels wrong for open-play/external grants.

> This is the biggest product decision open in #146 ("do points carry, reset, or run per season on band transition"). Expiry (§3) softens it: old-band points age out on their own.

---

## 6. Computation, persistence & scheduling (subsumes #220)

### 6.1 Snapshot storage
Persist the computed standings so reads are cheap and paginated in SQL (#220's recommended option (a)):
- `standings_snapshots` (a generation: `id`, `computed_at`, `published_at`, `status` = DRAFT/PUBLISHED, `as_of` date) +
- `standings_entries` (`snapshot_id`, `band`, `sex`, `rank`, `user_id`, `points`, tiebreak fields).

Reads serve the **latest PUBLISHED** snapshot. Keeping generations (not overwrite) gives history + the ability to publish a reviewed draft.

### 6.2 Recompute → review → publish (dry-run/commit, mirrors ratings)
A **`StandingsCalculationService`** analogous to `RatingCalculationService`:
1. **Recompute (dry-run):** read active, in-window awards `as_of` now; aggregate per `(band, sex)`; rank; produce a **DRAFT** snapshot / preview. No publish.
2. **Adjust:** allow point corrections (append ledger rows) before publishing.
3. **Publish (commit):** mark the snapshot PUBLISHED; it becomes what reads serve. Audited.

### 6.3 Schedule
A **Cloud Scheduler** job (like the DB backup schedule) triggers the recompute+publish on a cadence (fortnightly candidate). Because of expiry (§3), this runs on **time**, not just on award events. **Open question:** cadence (fortnightly vs weekly vs monthly), and whether publish is automatic or requires an admin's explicit commit each cycle. **Tentative:** scheduled **draft**, admin **publishes** (keeps the adjust-before-apply guarantee) — with an option to auto-publish if unattended.

### 6.4 Reads / API (from #220)
- `GET /api/v1/standings?band=&sex=&limit=25&offset=` → one page of a `(band, sex)` group from the latest published snapshot + `total` + available bands/sexes.
- `GET /api/v1/standings/me` (or a `locate`) → caller's band/sex/rank + the page containing them (jump-to-me).
- UI (#220): band selector + sex toggle + explicit "View", 25/page pager, "Find me". Default to the caller's band+sex.

---

## 7. Traceability & audit

Every award/revoke/adjust and every recompute/publish is recorded in the **audit log** (extends the #100/#333/#334 work). Proposed new `AuditAction`s under a `STANDINGS` (or `RANKING_POINTS`) category: `RANKING_POINTS_AWARDED`, `RANKING_POINTS_REVOKED`, `RANKING_POINTS_ADJUSTED`, `STANDINGS_RECALCULATED`, `STANDINGS_PUBLISHED`. Ad-hoc/external grants **must** carry actor + reason + source so a manual "credit these players" request is fully accountable.

**Open question — external-request intake:** how do external groups *request* a grant? Options: an admin enters it directly (simplest), or a request/approval workflow like rating re-requests (#115/#116). Tentative: **admin-entered ad-hoc grant** first; formal request flow later if volume warrants.

---

## 8. Open questions (decisions to make)

- **Points unit:** integer vs `BigDecimal` (fractional open-play points?). *Lean: `BigDecimal`.*
- **Band tagging (§5):** band-agnostic (A) vs band-tagged (B) vs hybrid (C). *Lean: (B).*
- **Point-class config:** enum+policy (a) vs table (b). *Lean: (a) + per-award override.*
- **Schedule cadence & publish mode:** fortnightly? auto-publish vs admin-commit. *Lean: scheduled draft + admin publish.*
- **Tie-breaking within equal points:** count of tournaments, most-recent award, head-to-head, or fall back to rating? (ATP breaks ties by number of results / higher-tier finishes.)
- **Open-play formula:** dominance × match-up → points, and its validity horizon.
- **External-request workflow:** direct admin grant vs request/approval.
- **Coexistence/rollout:** Phase 1 (rating-derived) stays until the ledger has enough points; how/when to flip the UI (§9).
- **Tournaments model dependency (#146):** build minimally as part of this, or gate tournament auto-awards behind #108/#111.

---

## 9. Suggested phasing (ship value before the full tournaments model)

1. **Ledger + manual/ad-hoc award/revoke + audit.** The `ranking_point_awards` store, admin endpoints to grant/revoke (incl. external), full traceability. No tournaments model needed. *(Unblocks the "flexible, external, traceable" requirement immediately.)*
2. **Snapshot recompute + schedule + publish** (`StandingsCalculationService`, dry-run/commit, Cloud Scheduler, `standings_snapshots`/entries). *(Delivers the precomputed, expiry-aware, published standings — the #220 persistence goal, now points-based.)*
3. **Read API + UI** (#220: paged per (band, sex), band/sex selector, jump-to-me) served from the published snapshot.
4. **Tournament points tables (ATP/WTA stage guarantees)** — once the tournaments/leagues model (#108/#111) exists: per-tournament stage→points, auto-awarded on result finalization.
5. **Open-play auto-points** (dominance × match-up), hooked into the rating-calculation commit.

Each step is independently useful; 1–3 give a working points-based, scheduled, published standings even before tournaments are modeled.

---

## 10. Relationship to existing code (reuse, don't reinvent)

- **Adjust-before-apply** already proven in `service/rating/RatingCalculationService.kt` (dry-run default, explicit commit, in-memory carry-forward, audited). Mirror it for `StandingsCalculationService`.
- **Scheduled job** pattern exists for DB backups (`scripts/schedule-backup.sh`, Cloud Scheduler). Reuse for the fortnightly recompute trigger.
- **Money-style precision** (`BigDecimalUtils`) if points are `BigDecimal`.
- **Audit** seam (`AuditService`, `respondError` logging) for traceability.
- **Per-(band, sex)** grouping + the own-row highlight already exist in Phase 1 `StandingsService`/`StandingsTab.tsx` — the read layer evolves rather than starts fresh.
- **Incremental migrations** (new `V…`) for the ledger + snapshot tables.
