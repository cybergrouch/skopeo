# Points Awarding & Budget — Design Discussion

**Status:** 🟡 Draft / discussion — decisions below are tentative; open questions are called out.
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
- **Event creation** captures:
  - **Min & max points per match fixture** — validated against the global per-type min/max (`event.min ≥ global.min`, `event.max ≤ global.max`).
  - **Point validity start & end** — validated against the global per-type **max validity days**.
  - A **points policy** by which the event draws from the club's allocated budget.

### 2.4 Match fixtures (where points are designated)
- Fixture creation asks **how many points** to award; convenience default = **round(avg(min, max))**.
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

### 5.3 Club-owner visibility (public/external club page)
- CLUB_OWNERs (and the public club page) surface points **per event** — designated/awarded amounts + club utilization — for transparency into their allocation. Extends `ClubService.publicByCode` / `ClubPage`.

---

## 6. Auditability
Every step — global-policy set, club allocation, event points-policy, fixture designation, **finalize/award**, reservation release, and (emergent) expiry — must be **traceable** (who, when, why, how much, which type). Builds on the ledger's `granted_by`/`reason`/`source` and the audit seam; add audit actions for the new events (allocation set, event finalized, points awarded on finalize).

---

## 7. Open decisions (to lock before building)
1. **Reservation timing** — reserve-on-designation vs check-only. *Lean: reserve on designation, convert on finalize, release unused; `Free = Budgeted − Reserved − ActiveAwarded`.*
2. **Recompute band-scoping** — extend #400 to count an award only when `award.band == player's current band`. (A real change to the current sum-by-tag logic.)
3. **Finalize ↔ rating-queue** — define behavior for **event-less/ad-hoc matches** (queue immediately as today?), and whether finalize is **terminal** or has an audited **un-finalize** escape hatch.
4. **League (team) award semantics** — points to the winning team → each member gets the designated amount, or split? Define "winner" for team play.
5. **Coexistence with manual/ad-hoc grants (#392)** — do admin grants draw from the club budget, or are `EXTERNAL`/ad-hoc grants a **non-budgeted** class? *Lean: event awards are budgeted; EXTERNAL is non-budgeted (else external credits break accounting).*
6. **Units** — integer vs `BigDecimal` (the ledger uses `BigDecimal`).
7. **Budget seeding** — who sets the master policy + total; how club allocations are provisioned/adjusted (admin UI; monetization later).

---

## 8. Suggested phasing (ship in slices)
- **A.** Event **types** (open play / league / tournament) + **FINALIZE** state + move rating-queue to finalize.
- **B.** Global master policy + club budgets + **POINTS_MANAGER** + **Points Management** tab.
- **C.** Fixture **point designation** (default = round(avg)) + **budget verify/reserve** on save.
- **D.** **Finalize-time award** to the ledger + **band-scoped counting** in the recompute (extends #400).
- **E.** **Club-owner public visibility** of per-event points.

Each slice is independently useful; A–C establish the controls, D wires the awards into the standings, E adds transparency.

---

## 9. Relationship to existing code / issues
- **Ledger:** `service/ranking/RankingPointService.kt`, `repository/RankingPointRepository.kt` (`band`, `valid_from`/`valid_until`, `status`, `source`, `granted_by`) — #392.
- **Recompute / serving:** `service/standings/StandingsCalculationService.kt` (#400), `StandingsService`/`StandingsSnapshotRepository` (#393); band-scoped counting extends the recompute.
- **Events / clubs / rating queue:** the events + clubs model, `service/rating/RatingCalculationService.kt` (rating-queue trigger moves to finalize), match occasion (#108).
- **Standings design:** `docs/product/STANDINGS_PHASE_2_DESIGN.md` (§4.3 ATP sketch — now subsumed here).
- **Related issues:** #146 (points ranking), #390 (tournaments = the TOURNAMENT event type), #389 (scheduler, after this), #108 (occasion).
