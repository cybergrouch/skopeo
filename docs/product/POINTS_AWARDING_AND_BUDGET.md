# Points Awarding & Budget — Design Discussion

**Status:** 🟢 Decisions locked (agreed on [#403](https://github.com/cybergrouch/skopeo/issues/403)); implementation proceeds by the A–E phases (§8). Phase A in progress.
**Issue:** [#403](https://github.com/cybergrouch/skopeo/issues/403). Builds on [#146](https://github.com/cybergrouch/skopeo/issues/146)/[#392](https://github.com/cybergrouch/skopeo/issues/392) (points ledger), [#393](https://github.com/cybergrouch/skopeo/issues/393) (serving layer) and [#400](https://github.com/cybergrouch/skopeo/issues/400) (recompute). Subsumes the ATP stage-table sketch and reshapes [#390](https://github.com/cybergrouch/skopeo/issues/390) (tournaments). Sequenced **before** [#389](https://github.com/cybergrouch/skopeo/issues/389) (scheduler).

This is the **"how points are earned"** layer beneath Standings Phase 2: how ranking points get associated with matches, awarded to winners conveniently by hosts, and kept **budget-controlled and fully auditable**.

---

## 1. Two-layer model — assignment vs accounting (the ATP analogy)

The single most important framing: **assigning points and counting them in the ranking are distinct steps.**

1. **Assignment (award) — on event *finalize*.** Points are written to the ledger and **owned** by the player (with a validity window + band tag). Mirrors ATP: tournament points aren't paid until the event concludes — nothing is assigned mid-event.
2. **Accounting (ranking) — at the Standings *snapshot recompute*.** The published snapshot is computed from the **already-assigned, currently-valid** points; reads serve that snapshot. Mirrors the ATP ranking release.

**Assigned ≠ accounted:** a point can be owned by a player yet not reflected in a published ranking until the next recompute. **Reads never compute ad-hoc/real-time** — they serve the last published snapshot (already true today: #393 serves the published snapshot, #400 recomputes only on the manual trigger, #389 will add the scheduled cadence = the "release").

**Consequence:** the fixed ATP stage→points table is **subsumed**, not separate — per-match, budget-bounded **designations** replace it (and can be shaped to mimic stage-weighting: later-round fixtures designate more). A tournament is simply the `TOURNAMENT` event type whose fixtures carry the designations, so **#390 folds into this model**.

### Layer map
- **#392 ledger** → the *assignment* layer (owned, valid-windowed, band-tagged awards).
- **#403 (this)** → *how* awards are created (on finalize, from the club budget, per fixture).
- **#400 recompute + #393 serving** → the *accounting/publishing* layer (band-scoped counting applied here; served snapshot).
- **#389** → automates the accounting cadence (the "release").

---

## 2. Domain model

### 2.1 Global / master points policy (the master control)
- A **finite total** number of ranking points exists for the whole player pool.
- Points are **classified by intended use** = the event types: **OPEN_PLAY / LEAGUE / TOURNAMENT** (extensible).
- Per type, the global policy sets:
  - **Min / Max points per match** reward. Events must reward **≥ global min** and **≤ global max** for their type — bounded flexibility so clubs can compete on rewards (and a future **monetization** channel: clubs buy allocable points).
  - **Max validity days** a point may stay valid (used to validate event validity windows).

### 2.2 Club allocation
- Each club receives a **budget allocation, per point type**, from the global pool.
- The club **sub-allocates** to its own activities per type. Accounting per club × type:
  - **Budgeted** — the club's total allocation (per type).
  - **Allocated** — points currently in use (reserved by events + awarded and still valid).
  - **Free** — remaining allocable = **Budgeted − Allocated** (expired awards return to Free automatically; see §3).

### 2.3 Events (typed + budgeted)
- Every event is **OPEN_PLAY**, **LEAGUE** (multi-day / season-long, team-format, has an intended winner), or **TOURNAMENT** (1–2 days).
- **Unified points model (all three types):** OPEN_PLAY now rewards points using the **same** model as LEAGUE/TOURNAMENT — event-level min/max/validity config (validated vs the global OPEN_PLAY policy), per-fixture manual designation (default `round(avg(min, max))`), budget reservation against the club, and finalize-time awards. This supersedes the earlier "open-play formula / non-budgeted open play" design. The **"no club ⇒ no points"** rule still applies to all types: a clubless event carries no config/designation/awards.
- **Event creation** captures:
  - **Min & max points per match fixture** — validated against the global per-type min/max (`event.min ≥ global.min`, `event.max ≤ global.max`).
  - **Point validity start & end** — validated against the global per-type **max validity days**.
  - A **points policy** by which the event draws from the club's allocated budget.

### 2.4 Match fixtures (where points are designated)
- Fixture creation asks **how many points** to award; convenience default = **round(avg(min, max))**. This applies to **every event type with a club**, OPEN_PLAY included (unified — no separate open-play formula).
- On **saving the fixture**, the backend **verifies/reserves** against the club budget (see §3 for reserve-vs-check).
- The fixture is the point where points are **designated** for the eventual winner.

### 2.5 Event finalization (NEW event state)
- A new **FINALIZE** action, triggered explicitly by **HOST / CLUB_OWNER / ADMINISTRATOR**, **closes the event to further changes**.
- On finalize: (a) **points are awarded** to each match's winner (ledger rows), and (b) the event's matches are **queued for rating**.
- ⚠️ **Behavioral change:** today a match is queued for rating immediately when its result is logged; this moves queuing to **finalize time**. (Open: rule for event-less/ad-hoc matches — see §7.)

---

## 3. Points lifecycle & budget accounting
- A point has a **start/end validity** (set at creation).
- **Reserve vs award (open decision, §7):** designation reserves against the budget; finalize converts a reservation into an award; unused reservations (voided / no-winner matches) are released. Recommended accounting: **`Free = Budgeted − Reserved − ActiveAwarded`**.
- On **expiry**, a point **returns to Free** — but this is **emergent** from the accounting formula (append-only ledger + validity window), not a mutating "return" action. This keeps accounting simple and auditable.
- **Clean books:** club accounting depends **only on the point lifecycle**, never on where players currently sit (see §4).

---

## 4. Points ownership & band-scoped counting
- Points are **awarded to a player** and owned for their life.
- A point is only **counted** within the player's NTRP band **at the start of the match** — the point **tracks its designated band** (the ledger already has a `band` tag).
- **Band jump:** if the player moves to a new band, the point is **not invalidated and stays owned**, but is **not counted** on the new band. If the player **returns** to the point's band, it's **counted again**.
- **Uncounted points do NOT return to the club pool** — they remain awarded to the player, just uncounted. So the club's Free/Allocated depend only on lifecycle, not on owner NTRP.
- **Where enforced:** at the **recompute (accounting) step** — count an award only if `award.band == player's current band` (and valid). Concretely, a player's standing = valid awards whose tag matches their **current** band. ⚠️ This **extends #400's recompute**, which today sums by `award.band` without checking the player's live band.

---

## 5. Roles, management UI & visibility

### 5.1 `POINTS_MANAGER` capability (new)
- A staff role overseeing the points economy. **For now, default-grant to ADMINISTRATOR** (treat ADMINISTRATOR as implicitly a points manager); split to real assignees later. Add to the `Capability` enum + gating (backend + `web/src/auth/capabilities.ts`).

### 5.2 Admin "Points Management" tab (ADMINISTRATOR / POINTS_MANAGER)
- Per **club × event type**, show **Budgeted / Allocated / Free**; manage club allocations and monitor utilization. New read API for the accounting.

### 5.3 Club-owner visibility (public/external club page) — Phase E ✅
- **Per-event points are public.** The anonymous club page (`ClubService.publicByCode` / `ClubPage`) surfaces, per event, the **event type** and a **points** figure — the **awarded** total for a finalized event, else the **designated** total. No login required.
- **Club utilization is NOT public.** Budgeted / Allocated / Free per event type is visible only to that club's **CLUB_OWNER** (and ADMINISTRATOR / POINTS_MANAGER), served by a **separate gated endpoint** `GET /api/v1/clubs/{clubId}/points-summary` (utilization + a per-event breakdown). Kept as its own endpoint — never a conditional field on the public response — so nothing can leak. The web club page fetches it only when the authenticated viewer owns that club (or is an admin/points-manager).

---

## 6. Auditability
Every step — global-policy set, club allocation, event points-policy, fixture designation, **finalize/award**, reservation release, and (emergent) expiry — must be **traceable** (who, when, why, how much, which type). Builds on the ledger's `granted_by`/`reason`/`source` and the audit seam; add audit actions for the new events (allocation set, event finalized, points awarded on finalize).

---

## 7. Resolved decisions (locked on #403)
1. **Reservation model** — reserve on **designation** (`designated × team_size`), convert to awards on **finalize**, release unused (voided/no-winner); `Free = Budgeted − Reserved − ActiveAwarded`.
2. **Recompute band-scoping** — count an award only when `award.band == player's *current* band` (extends #400's sum-by-tag). Phase D.
3. **Finalize / rating-queue** — finalize is **terminal** (no un-finalize; audited). Rating **queues on finalize**. Event-less/ad-hoc matches aren't a supported flow and **carry no points** (no budget source); the `event_id IS NULL → queue immediately` branch is a defensive fallback only (rated, never awarded).
4. **League (team) award** — **each winning-team member gets the full designated points** (not split). Budget cost per fixture = `designated × team_size` (deterministic — both sides same size). Fixture validation is **cumulative per event**: `Σ(event fixtures' designated × team_size) ≤ the event/club allocation for the type`; editing a fixture re-checks.
5. **Manual/ad-hoc grants (#392)** — event awards are **budgeted**; `EXTERNAL`/ad-hoc grants are a **non-budgeted** class (outside the club budget).
6. **Units** — `BigDecimal` stored type, **integer values only** (reject any fractional part at every entry point; the `round(avg(min, max))` designation default is an integer). Supersedes the earlier "fractional open-play points" note. (There is no separate open-play formula: OPEN_PLAY uses the same designation/award model as LEAGUE/TOURNAMENT — see §2.3.)
7. **Budget seeding** — ADMINISTRATOR / POINTS_MANAGER sets the master policy + total and provisions/adjusts club allocations (Points Management tab, §5.2); monetization later.

---

## 8. Suggested phasing (ship in slices)
- **A.** Event **types** (open play / league / tournament) + **FINALIZE** state + move rating-queue to finalize.
- **B.** Global master policy + club budgets + **POINTS_MANAGER** + **Points Management** tab.
- **C.** Fixture **point designation** (default = round(avg)) + **budget verify/reserve** on save.
- **D.** **Finalize-time award** to the ledger + **band-scoped counting** in the recompute (extends #400).
- **E.** ✅ **Club-owner public visibility** of per-event points (public per-event points; CLUB_OWNER/admin-gated utilization via `/clubs/{clubId}/points-summary`). Completes the #403 arc.

Each slice is independently useful; A–C establish the controls, D wires the awards into the standings, E adds transparency.

- **Unify (post-E).** OPEN_PLAY joined the budgeted model: it now carries event config, per-fixture designation, budget reservation, and finalize awards exactly like LEAGUE/TOURNAMENT (the "budgeted types" set is now all three; still gated by club presence). Supersedes Phase C/D's exclusion of OPEN_PLAY.

---

## 9. Relationship to existing code / issues
- **Ledger:** `service/ranking/RankingPointService.kt`, `repository/RankingPointRepository.kt` (`band`, `valid_from`/`valid_until`, `status`, `source`, `granted_by`) — #392.
- **Recompute / serving:** `service/standings/StandingsCalculationService.kt` (#400), `StandingsService`/`StandingsSnapshotRepository` (#393); band-scoped counting extends the recompute.
- **Events / clubs / rating queue:** the events + clubs model, `service/rating/RatingCalculationService.kt` (rating-queue trigger moves to finalize), match occasion (#108).
- **Standings design:** `docs/product/STANDINGS_PHASE_2_DESIGN.md` (§4.3 ATP sketch — now subsumed here).
- **Related issues:** #146 (points ranking), #390 (tournaments = the TOURNAMENT event type), #389 (scheduler, after this), #108 (occasion).
