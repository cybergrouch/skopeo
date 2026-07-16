# Standings Phase 2 — Points-Based Ranking (Design & Decisions)

**Status:** 🟢 Decisions locked for the initial slice (see §0). The detailed design (§1+) stands; a few later-phase questions remain open and are marked.
**Issues:** [#220](https://github.com/cybergrouch/skopeo/issues/220) (serving layer — persisted snapshot, paging, jump-to-me), [#146](https://github.com/cybergrouch/skopeo/issues/146) (points model — ledger + manual recompute), [#389](https://github.com/cybergrouch/skopeo/issues/389) (scheduled auto-recompute, later), and [#390](https://github.com/cybergrouch/skopeo/issues/390) (tournaments/leagues model — gates #146 tournament auto-awards). Builds on [#113](https://github.com/cybergrouch/skopeo/issues/113) (Phase 1, ratings-derived standings) and [#212](https://github.com/cybergrouch/skopeo/issues/212) (sex split).

This document is the single source of truth for Standings Phase 2 technical decisions. §0 records the locked decisions, scope split, and ticket map; §1+ is the detailed design behind them.

---

## 0. Decisions & ticket map (locked 2026-07-16)

### 0.1 Locked decisions
| # | Decision | Choice | Notes |
|---|---|---|---|
| D1 | **Sequencing of #220 vs #146** | Ship **#220 first** as a standalone serving layer on today's rating-derived standings; then #146 swaps the source to points. | #146 reuses #220's read/paging layer rather than rebuilding it. |
| D2 | **Band transition (points on promotion)** | **Band-tagged awards** (doc option B): each award records the band it was earned in; a band's race counts only points earned in that band. | Fits tournaments (band-specific draws); expiry retires old-band points. See §5. |
| D3 | **Recompute trigger** | **Manual admin trigger now** (dry-run→commit, like `RatingCalculationService`). Automated/scheduled recompute is **deferred to #389** (reuses the same path). | #220 also rebuilds event-triggered on rating change. See §6.2–6.3. |
| D4 | **Tournaments dependency** | **Gate tournament auto-awards (phase 4) behind [#390](https://github.com/cybergrouch/skopeo/issues/390)** (tournaments/leagues model). Ship ledger + manual/ad-hoc grants without it. | #108 (occasion) / #111 (seeding) are closed but are *not* that model. See §4.3, §9. |
| D5 | **Points unit** | **`BigDecimal`** (fractional open-play points), formatted for display. | Reuse `BigDecimalUtils`. |
| D6 | **Point-class config** | **Enum + policy defaults + per-award override** of `valid_from`/`valid_until`. | Revisit an admin-editable table later. See §4.2. |
| D7 | **Publish mode** | **Scheduled/manual run produces a DRAFT; an admin explicitly publishes** (preserves adjust-before-apply). | See §6.2. |
| D8 | **Tie-break within equal points** | Higher **current rating**, then **earliest-achieved** total. Stored in `standings_entries` tiebreak fields. | Revisit (ATP uses #results / higher-tier finishes) once tournaments exist. |

Still open (later phases, non-blocking): open-play points formula & horizon (§4.4); external-request intake workflow (§7); exact tournament stage tables (§4.3, needs the tournaments model); scheduled cadence & auto-publish option (#389).

### 0.2 Scope split — #220 (serving) vs #146 (points)
The guiding principle: **#220 owns *how standings are stored & served*; #146 owns *what the number is & when it's recomputed*.** #146 plugs into #220's layer.

**#220 — Standings serving layer (build now, rating-derived):**
- **Source-agnostic** persisted snapshot: `standings_snapshots` (`id`, `computed_at`, `as_of`, `status`) + `standings_entries` (`snapshot_id`, `band`, `sex`, `rank`, `user_id`, `ordering_value`, tiebreak fields). `ordering_value` = rating today, points later — the read path never changes.
- Recompute **event-triggered** on rating change (calc commit / admin set/override).
- Read API `GET /api/v1/standings?band=&sex=&limit=25&offset=` + `GET /api/v1/standings/me` (jump-to-me); UI band selector + sex toggle + "View" + 25/page pager + "Find me".

**#146 — Points-based ranking (later, reuses #220's layer):**
- `ranking_point_awards` ledger (append-only, **band-tagged**, expiring, `status` ACTIVE/REVOKED, `granted_by`/`reason`/`source`); admin grant/revoke incl. external/ad-hoc; audited.
- `StandingsCalculationService` (dry-run→commit) recomputes the snapshot **from the ledger** and adds **DRAFT/PUBLISHED** generations; **manual** admin trigger (automation → #389).
- **Swaps the snapshot's source** rating→points; read API/UI/paging/jump-to-me untouched (delivered by #220).
- Tournament stage→points **gated** on the tournaments-model issue.

### 0.3 Ticket map
- **#220** — serving layer (above). Ships first, independently useful.
- **#146** — points ledger + manual recompute/publish; consumes #220's layer.
- **#389** — automates #146's manual trigger on a schedule (Cloud Scheduler), later.
- **[#390](https://github.com/cybergrouch/skopeo/issues/390)** — tournaments/leagues model (brackets/rounds/stages); gates #146 phase 4 (ATP/WTA stage auto-awards).

### 0.4 Differences reconciled (previously overlapping/conflicting across the tickets)
| Tension | Resolution |
|---|---|
| **Trigger** — #220 said "recompute *only* on rating change"; #146 needs time-based (expiry) | #220 stays event-triggered; #146 adds a **manual** recompute; **scheduled** automation is **#389**. #220's "only on rating change" wording is superseded. |
| **Data source** — #220 rating-derived vs #146 points | Snapshot stores a generic `ordering_value` + tiebreak; #146 changes only the *producer*. |
| **Paging / jump-to-me / band-sex selector** — described in both | Belongs to **#220**; removed from #146's scope (delivered by #220). |
| **Snapshot semantics** — #220 "the snapshot" vs #146 DRAFT/PUBLISHED generations | `status` column exists from #220 (ships PUBLISHED-only); #146 adds DRAFT + adjust-before-publish on the same table. |
| **Tie-break** — unspecified | Locked (D8): higher current rating, then earliest-achieved. |

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

➡️ **Therefore a purely event-triggered rebuild is insufficient for points.** Phase 2 eventually needs a **time-based recompute**. **Decision (D3):** for now #146 ships a **manual admin recompute** (on demand, dry-run→commit); the **scheduled automation is deferred to [#389](https://github.com/cybergrouch/skopeo/issues/389)**, which reuses the same path. #220's original "recompute only on ratings change" is therefore superseded: #220 stays event-triggered while it's rating-derived, and #146 adds the manual (later scheduled) recompute.

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

⚠️ **Dependency (#146):** this needs a **tournaments/leagues model that does not yet exist** — now tracked as **[#390](https://github.com/cybergrouch/skopeo/issues/390)** (relates to #108 occasions, #111 seeding, but is neither). The ledger + ad-hoc/external grants ship *without* it; tournament auto-awards come once #390 lands. See phasing (§9).

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

### 6.3 Trigger — manual now, scheduled later (D3, #389)
**Now:** an **admin manually triggers** the recompute → review → publish (the §6.2 flow). No scheduler in the initial slice.

**Later ([#389](https://github.com/cybergrouch/skopeo/issues/389)):** a **Cloud Scheduler** job (like the DB-backup schedule) invokes the *same* recompute path on a cadence, because expiry (§3) means the ranking changes with time. Deferred so #146 isn't gated on it. **Open (in #389):** cadence (fortnightly vs weekly vs monthly) and whether the scheduled run auto-publishes or produces a DRAFT an admin commits. **Lean (D7):** DRAFT + admin publish, with an option to auto-publish if unattended.

### 6.4 Reads / API (from #220)
- `GET /api/v1/standings?band=&sex=&limit=25&offset=` → one page of a `(band, sex)` group from the latest published snapshot + `total` + available bands/sexes.
- `GET /api/v1/standings/me` (or a `locate`) → caller's band/sex/rank + the page containing them (jump-to-me).
- UI (#220): band selector + sex toggle + explicit "View", 25/page pager, "Find me". Default to the caller's band+sex.

---

## 7. Traceability & audit

Every award/revoke/adjust and every recompute/publish is recorded in the **audit log** (extends the #100/#333/#334 work). Proposed new `AuditAction`s under a `STANDINGS` (or `RANKING_POINTS`) category: `RANKING_POINTS_AWARDED`, `RANKING_POINTS_REVOKED`, `RANKING_POINTS_ADJUSTED`, `STANDINGS_RECALCULATED`, `STANDINGS_PUBLISHED`. Ad-hoc/external grants **must** carry actor + reason + source so a manual "credit these players" request is fully accountable.

**Open question — external-request intake:** how do external groups *request* a grant? Options: an admin enters it directly (simplest), or a request/approval workflow like rating re-requests (#115/#116). Tentative: **admin-entered ad-hoc grant** first; formal request flow later if volume warrants.

---

## 8. Open questions

**Resolved — locked in §0:** sequencing (D1, #220 first), band tagging (D2, band-tagged), trigger (D3, manual now → #389), tournaments dependency (D4, gated on a new issue), points unit (D5, `BigDecimal`), point-class config (D6, enum+policy+override), publish mode (D7, DRAFT + admin publish), tie-break (D8, current rating then earliest-achieved).

**Still open (later phases, non-blocking):**
- **Open-play formula:** dominance × match-up → points, and its validity horizon (§4.4).
- **External-request workflow:** direct admin grant vs request/approval (§7).
- **Scheduled cadence & auto-publish option:** tracked in [#389](https://github.com/cybergrouch/skopeo/issues/389).
- **Tournament stage tables:** the exact per-tournament stage→points schedules, once the tournaments/leagues model exists.
- **Coexistence/rollout:** Phase 1 (rating-derived, served via #220) stays until the ledger has enough points; when to flip the snapshot's source to points (§9).

---

## 9. Suggested phasing (ship value before the full tournaments model)

1. **Ledger + manual/ad-hoc award/revoke + audit.** The `ranking_point_awards` store, admin endpoints to grant/revoke (incl. external), full traceability. No tournaments model needed. *(Unblocks the "flexible, external, traceable" requirement immediately.)*
2. **Snapshot recompute + schedule + publish** (`StandingsCalculationService`, dry-run/commit, Cloud Scheduler, `standings_snapshots`/entries). *(Delivers the precomputed, expiry-aware, published standings — the #220 persistence goal, now points-based.)*
3. **Read API + UI** (#220: paged per (band, sex), band/sex selector, jump-to-me) served from the published snapshot.
4. **Tournament points tables (ATP/WTA stage guarantees)** — once the tournaments/leagues model ([#390](https://github.com/cybergrouch/skopeo/issues/390)) exists: per-tournament stage→points, auto-awarded on result finalization.
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
