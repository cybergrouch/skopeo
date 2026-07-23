# Tournaments, Circuits & Open-Play Points

> **Status:** Proposed — discussion of record for [#525](https://github.com/cybergrouch/skopeo/issues/525). Not yet implemented. All design decisions are resolved (marked **Resolved** below); remaining detail (band matrices) lives in the working sheet referenced on #525.

This document captures the design discussion for two related additions to the competitive model and the ranking-points system:

1. **Tournaments & Circuits** — a first-class **Tournament** (sanctioned or unsanctioned) that belongs to an admin-defined **Circuit**, where the sanction status selects the points a tournament contributes.
2. **Open-play points reimplementation** — replace host-*designated* per-match points for **open play** with a **computed** formula driven by the difference between the two teams' NTRP **band** ratings.

The fuller working model (band matrices, edge cases) lives in the working Google Sheet referenced on #525; this document records the rules confirmed so far and how they map onto the existing code.

---

## Part A — Tournaments & Circuits

### Concepts

| Concept | Definition |
| --- | --- |
| **Circuit** | An **administrator-defined** grouping of tournaments. Flexible — admins create/rename/retire circuits; it is **not** a hard-coded enum. Seed data: **NORTH**, **SOUTH**. Selected on the tournament event. |
| **Tournament** | An `EventType.TOURNAMENT` event that belongs to exactly one Circuit and is **associated with a Club**. Its sanction status is **inherited from that club** (not set per-tournament). |
| **Sanction status** | A flag on the **Club** — whether tournaments hosted by that club are **sanctioned**. It selects which **placement → points** table applies (see below); it does **not** scale per-match points. Putting it on the club (an admin-governed entity) rather than the event closes the governance hole where a host could self-declare their own tournament sanctioned. |

These are net-new: today `EventType.TOURNAMENT` is only an event flavor — there is no circuit, and no club-level sanction flag, anywhere in the codebase.

### Tournament points distribution (by final placement)

Points are awarded **once per participant** by **finishing placement**, and **only for placement** — tournaments do **not** award per-match points the way open play does (a tournament's regular rounds earn nothing; rating changes still apply). The sanction status selects the table (sanctioned = exactly **2×** unsanctioned):

| Placement | Sanctioned | Unsanctioned |
| --- | --- | --- |
| 1st | **80** | **40** |
| 2nd | **60** | **30** |
| 3rd | **40** | **20** |
| 4th | **30** | **15** |

**Placement comes from designated placement matches** (not host-entered standings). When creating a tournament match, the organizer marks whether it is a **placement match** and, if so, *which* placement it decides:

| Placement match | Winner → | Loser → |
| --- | --- | --- |
| **Super Finals** | **1st** | **2nd** |
| **Plate Finals** | **3rd** | **4th** |

At finalize, the awarder reads the result of each placement match and awards the winner/loser their placement points from the sanction-selected table. A tournament that plays only a Super Finals awards just 1st/2nd; a Plate Finals adds 3rd/4th. In **doubles**, **each partner receives the full placement amount** (consistent with the existing per-member awarding — one ledger row per team member, each carrying the full points).

- **Resolved:** placements beyond 4th earn **0 points** — the table above is the full schedule (Super Finals + Plate Finals cover 1st–4th).
- **Resolved:** ties for a placement are **the tournament's choice** — a tournament resolves 3rd/4th by playing a **Plate Finals** (the standard path); if it chooses not to play one out, it simply doesn't award those placements. The platform derives placement from the placement matches that were actually played rather than imposing a global tie rule.
- **Resolved (for now):** payout does **not** depend on **draw size** — a single table applies to all tournament sizes, since local (Manila) tournaments are small due to limited courts and cost. However, the implementation should be **flexible enough** to later define the points spread by tournament size (i.e. model the placement → points table as a configurable/selectable schedule, not a hard-coded constant), so a draw-size-tiered table can be introduced when tournaments grow.

### Model decisions

- **Circuit as its own entity.** A `circuits` table (`id`, `name`, `is_active`, audit cols) + admin CRUD, seeded with NORTH/SOUTH — preferred over a free-text field so seeding/renaming and reporting-by-circuit are clean.
- **Resolved — circuit is required for tournaments (no legacy to migrate).** A tournament event **must** reference a circuit (`circuit_id` NOT NULL for `EventType.TOURNAMENT`). There are **no tournaments in the system yet**, so this can be introduced cleanly with no back-compat/data-fix step.
- **Resolved — one entity, one UI.** A tournament is an **`EventType.TOURNAMENT` event**, not a separate entity. The **Circuit** is a conditional field in the existing **Event Organizer** flow when the type is TOURNAMENT; **sanction is inherited from the event's Club** (see below), not entered per event. Rationale: an event already provides everything a tournament needs operationally (participants, fixtures, result upload, finalize); the genuinely new work — placement matches + placement-based awarding — exists regardless of entity shape, so a separate entity buys nothing there. If a single tournament ever needs to span **multiple events** (multi-day, multiple sub-draws) under one standing, a parent Tournament entity can be added later without redoing the event work — but given small local draw sizes, one-event-per-tournament fits now.
- **Resolved — sanction is a Club flag, inherited by the tournament.** Sanction status lives on the **Club** (a new boolean, e.g. `tournaments_sanctioned`), and a tournament event inherits it via its existing **event↔club** association. This reuses the current club-scoping of events and keeps sanctioning a club-governance decision rather than a per-event choice by the organizing host. The flag is toggled by **CLUB_OWNER or ADMINISTRATOR**. A tournament event with **no club** is implicitly **unsanctioned**.
- **Resolved — placement source is designated placement matches.** Placement is derived from the results of **placement matches** the organizer flags at match creation (Super Finals → 1st/2nd, Plate Finals → 3rd/4th), not a host-entered final-standings step and not a full bracket ([#390](https://github.com/cybergrouch/skopeo/issues/390) stays deferred). This needs a new per-match input: an `isPlacementMatch` flag + a placement-bracket selector (e.g. `SUPER_FINALS` \| `PLATE_FINALS`). Regular (non-placement) matches award no points.

---

## Part B — Open-play points

Points for an **open-play** result are computed from the **NTRP band difference** between the two teams, who won, and games won in the lost set — replacing the current host-designated amount for open play.

### Band comparison

- A team's band is `Level.fromValue(rating)` — a 0.5-wide floor over the 1.0–7.0 NTRP range ("4.0", "4.5", …).
- For **doubles**, a team's band is the band of the **mean** of the partnership's two NTRP ratings (matching how `DoublesMatchTypeHandler` already means partners).
- Higher band = higher-ranked team.
- **Comparison is binary — equal vs. unequal only.** Scoring depends solely on whether the two bands are the same or different (and, when different, which side won); it does **not** scale with the *size* of the gap. A one-band gap and a three-band gap award the same points. This keeps all awards **integral** (no fractional parts) and lets the whole open-play schedule be stated in the single table below.

### Points table

Define **RLP** (Regular Loser Points, a constant per case) and **ALP** (Additional Loser Points):

> **ALP = 1 if the losing team won ≥ 4 games in the set they lost, else 0.**

**Tiebreaks count as games (open play only).** When a set is decided by a tiebreak, the **tiebreak points count as that set's games** for the ALP threshold — so a set/match tiebreak that stands in for a full set (recorded with 0 games) uses the loser's tiebreak points as their "games won" (e.g. a 10–8 match tiebreak → the loser's 8 clears the ≥ 4 threshold). A conventional 7–6 set already records the loser's 6 games, which clears the threshold on its own. ALP is an open-play concept only; it does not apply to tournaments (which are placement-based).

| Case | Winner points | Loser points |
| --- | --- | --- |
| **Equal bands** | **3** | **0** |
| **Unequal — higher-ranked team wins** | **2** | **RLP + ALP**, `RLP = 1` |
| **Unequal — lower-ranked team wins (upset)** | **5** | **RLP + ALP**, `RLP = −2` |

Worked out:

- **Equal bands** → winner **3**, loser **0**.
- **Favorite (higher band) wins** → winner **2**; loser **1** (or **2** with ALP).
- **Underdog (lower band) upsets** → winner **5**; loser **−2** (or **−1** with ALP). **Loser points can be negative.**

### Per-set aggregation

Points are computed **per set and summed across the sets** of the match:

- Open play in Manila is generally **single-set**, but **3-set** open play is emerging. The formula runs **per set**: for each set, the set's winner/loser drives the table and **ALP is evaluated on that set's games** ("the set they lost" = each set the team lost).
- The **band comparison is constant** across the match (same two teams), so only the set outcome and per-set games vary.
- The **overall match `winnerTeamId` is not used** for open-play points — each set is scored independently.
- **Consequence:** a 3-set match can total **more (or, for an upset loser, more negative) points** than the single-set table values. This is intended.

### Edge cases

- **Retirement / walkover (open play):** the match awards **0 points** — no computed award for either side.
- **Retirement / walkover (tournament):** placement is unaffected — the recorded winner of a placement match still receives that placement's points (and the loser their placement's points), since tournament points depend on placement, not on games played.

### Open decisions (Part B)

- **Resolved:** points are computed **per set and summed** (see [Per-set aggregation](#per-set-aggregation)). Each set's winner/loser and games drive the table; the overall match winner is not used; multi-set matches can exceed the single-set table values.
- **Resolved — net, no floor.** The upset-loser case yields **−2 / −1**; these are stored as-is (the ledger `points` column is a signed `DECIMAL`) and **net straight into** the band-race total, with **no floor at zero**. Two existing mechanisms naturally bound the effect, so a floor is unnecessary: (1) **band-tagging** — if a run of upset losses actually deflates a player's rating into a lower band, they begin a **fresh points race in the new band** and the old-band negatives no longer weigh on them; and (2) the **validity window** — every award carries a start date and expiry (per the existing points contract), so a negative award ages out. Displays may floor the *shown* number at zero if a negative leaderboard value is undesirable, but the stored/aggregated value is not floored.
- **Resolved — bands locked at event entry.** The rating basis is **fixed at the start of the event** (a player's rating *entering* the tournament / open-play event) and is **not dynamic**. If a team is bumped to a higher or lower NTRP band *during* the event, it does **not** change the points basis — what counts is the band they entered with. This is fairer (matchups are scored on how players stood entering the competition) and stable (every match in the event uses the same snapshot). It is neither match-play-time nor finalize-time — it is an **as-of-event-start** snapshot.
- **Resolved — each recipient's award is tagged with their own entry band.** Consistent with "locked at entry" and the existing per-user, band-scoped ledger: winner and loser each get a row tagged with **their own** band as of event start (not the opponent's, not a finalize-time band).
- **Resolved — validity window is an event input, defaulting to 2 months from event end.** The award validity window stays a field on the event set at creation (the existing `pointValidity{Start,End}` inputs are retained — only the per-fixture *designation* goes away). The default is **`validFrom` = the event's end date** and **`validUntil` = end date + 2 months**; the organizer may override either. (Note this differs from the old behavior where the window was open-ended input with no default and drove the `PointClass` mapping.)

---

## Points policy — removed, superseded by rule-based awarding

The existing **global points policy** (`points_policies`, seeded in V16: per-`EventType` `min`/`max`/`max_validity_days`, e.g. OPEN_PLAY 1–10) exists to bound a **host-designated** per-fixture points amount: an organizer picks a number within the event's `[min, max]` window, which is itself capped by the policy, validated in `MatchService.resolveDesignation`.

Under this design, points are **determined by rules, not designated by a host**:

- **Open play** — computed from the band-difference table (integral values `−2 … 5`).
- **Tournaments** — fixed placement schedule (80/60/40/30, halved when unsanctioned).

So the designation-plus-policy machinery is **obsolete for both**, and two of the computed values (**0** and **negative**) would actually *violate* the policy's positive `min` and the `points > 0` guard in `RankingPointService.grant`.

**Decision:** **remove the global points policy.** The new rule-based requirement supersedes it. Concretely this means retiring the `points_policies` table (new down/forward migration), the `PointsPolicy` domain/validation, and the per-event `[min,max]` designation path *for the rule-based event types*, and allowing **0 and negative** awards on the finalize path.

**Resolved — League is excluded from awarding for now.** `EventType.LEAGUE` currently shares the *same* designated-points + policy machinery as open play and tournaments. Removing the global policy and the designation path leaves league without a points model, and this issue's scope (open play + tournaments) does not define league's new behavior. **Decision: (c) — league is temporarily excluded from awarding** until a league points model is defined in a later issue. Concretely: **remove `LEAGUE` from `AWARDING_TYPES`** so finalizing a league event produces no ranking points (ratings still compute as normal). A follow-up issue will define league's rule-based points.

---

## How this maps onto the current code

Grounded in a read of the points/band/event code (file references for implementers):

### Points are awarded at event finalize
`service/event/EventFinalizeAwarder.kt` (`awardForFinalizedEvent`) is the single choke point. It filters fixtures that are `COMPLETED`, have a `winnerTeamId`, and carry a non-null `designatedPoints`; resolves each winner's **current band** (`ratings.findCurrentRatings`) and sex; maps the event's `pointValidity{Start,End}` to a `PointClass`; and writes one ledger row **per winning-team member** with the host-designated amount.

- **Open play (Part B) plugs in here.** Most inputs are available at finalize time: `match.winnerTeamId` (per set), per-set games (`match.sets[].team1Games/team2Games`), `event.type`, `match.matchType`. The one change from today's flow is the band basis: instead of `findCurrentRatings` (finalize-time), the bands must be resolved **as of event start** (entry band — see the resolved decision above), e.g. from `user_rating_history` as-of the event start date or an entry snapshot taken at registration. The awarder change: for `EventType.OPEN_PLAY` fixtures, replace "read `designatedPoints`" with `computeOpenPlayPoints(entryBandTeamA, entryBandTeamB, sets)` iterating the sets, and **also emit a loser award** (today only winners are paid) — including zero/negative amounts.
- **Tournaments (Part A) need a different branch.** Tournaments award **only** by placement (no per-match points). Requires a new per-match input (`isPlacementMatch` + placement bracket `SUPER_FINALS`/`PLATE_FINALS` — a `Match`/`MatchTables` column + DTO/OpenAPI + Event Organizer field). At finalize, the awarder finds the placement matches, maps **Super Finals winner→1st / loser→2nd** and **Plate Finals winner→3rd / loser→4th**, and writes one ledger row per team member with the sanction-selected table amount. Regular tournament fixtures write nothing.

### Band model already supports the comparison
- `model/Level.kt` — `Level.fromValue(rating)` floors a rating to a 0.5 band; bands compare numerically via `minRating`. Equal/higher/lower is straightforward.
- `service/calculator/impl/v2/DoublesMatchTypeHandler.kt` — team rating = mean of partners; feed that mean into `Level.fromValue` for the doubles team band.

### Match result already carries games-in-lost-set
- `model/MatchDomain.kt` — `MatchSetResult(setNumber, team1Games, team2Games, winnerTeamId, …)` and `Match.sets: List<MatchSetResult>`. The losing team's games in a set: `if (winnerTeamId == team1) team2Games else team1Games`. ALP (≥ 4) is directly computable per set. Points are summed over `Match.sets` (per-set aggregation, resolved), so the awarder iterates the sets rather than reading only the overall `winnerTeamId`.

### Ledger already supports what we need
- `repository/RankingPointRepository.kt` / `RankingPointAwardsTable` — `points` is a **signed** `DECIMAL(10,4)` (negatives OK); rows are **band-tagged** and **sex-tagged**, carry a validity window and `event_id`/`match_id` links, and are append-only/revocable. No schema change is needed for negative open-play awards or loser rows.

### Upset detection exists but is rating-based, not band-based
- `service/calculator/impl/v2/PerformanceBasedRankingCalculatorImpl.kt` computes `isUpset` from the continuous **rating** advantage, not the discrete **band** difference. The open-play formula keys off **band** difference, so it should compute the band comparison independently (cheap; both ratings are in hand) rather than reuse `isUpset`.

### Net-new for Part A
No `circuit` or `sanction` concept exists (`EventType` / `MatchType` / `WeightClass` are the only competition-shape enums). Net-new: a **`circuits`** table + admin CRUD; a **`circuit_id`** column on the **event** (not a separate tournament table — one entity); a **sanction flag on the Club** (inherited by the event via its club association); a per-match **`is_placement_match` + placement-bracket** input (`SUPER_FINALS`/`PLATE_FINALS`); and a **placement-based awarding branch**. Plus the corresponding models, repositories, services, routes, DTOs, OpenAPI entries, and Event Organizer / club-admin web fields.

---

## Rollout

**Going forward only.** The new open-play formula and tournament placement points apply to events finalized **after** the change ships. Already-finalized open-play events are **not** recomputed or re-awarded — their existing ledger rows stand. No historical migration of awards.

## Suggested increments (not a commitment)

1. **Open-play computed points** (Part B) — a new `computeOpenPlayPoints(...)`, wired into `EventFinalizeAwarder` for `OPEN_PLAY`, resolving each team's **entry band** (as-of-event-start), iterating sets, and paying losers too, with tests. Needs an as-of-event-start band lookup (rating-history query or an entry snapshot); a new V-migration if we persist an entry snapshot rather than deriving it from history.
2. **Circuits** — `circuits` table + admin CRUD (seed NORTH/SOUTH); model/repo/service/routes/DTO/OpenAPI + an Admin web tab.
3. **Tournaments + sanction** — `circuit_id` on the event (conditional Event Organizer field when type = TOURNAMENT), a **sanction flag on the Club** (admin-toggled, inherited by the event), a per-match `is_placement_match` + bracket input, and the placement → points table applied at finalize via a new awarding branch. Model the table as a **configurable schedule** so a draw-size-tiered spread can be added later.

## References

- Issue: [#525](https://github.com/cybergrouch/skopeo/issues/525)
- Related deferred work: [#390](https://github.com/cybergrouch/skopeo/issues/390) (tournament brackets)
- Existing points design: [`POINTS_AWARDING_AND_BUDGET.md`](./POINTS_AWARDING_AND_BUDGET.md)
- Rating algorithm & bands: [`RATING_CALCULATION_ALGORITHM.md`](./RATING_CALCULATION_ALGORITHM.md)
- Working model (band matrices, source of truth): Google Sheet linked from #525.
