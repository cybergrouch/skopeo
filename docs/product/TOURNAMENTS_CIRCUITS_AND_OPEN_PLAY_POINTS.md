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
| **Circuit** | An **administrator-defined** grouping of tournaments. Flexible — admins create/rename/retire circuits; it is **not** a hard-coded enum. Seed data: **NORTH**, **SOUTH**. |
| **Tournament** | Belongs to exactly one Circuit. Carries a **sanction status**: `SANCTIONED` \| `UNSANCTIONED`. |
| **Sanction status** | Selects which **placement → points** table applies (see below). It does **not** scale per-match points. |

All three are net-new: today `EventType.TOURNAMENT` is only an event flavor — there is no tournament entity, no circuit, and no sanction flag anywhere in the codebase.

### Tournament points distribution (by final placement)

Points are awarded **once per participant** by **finishing placement**. The sanction status selects the table (sanctioned = exactly **2×** unsanctioned):

| Placement | Sanctioned | Unsanctioned |
| --- | --- | --- |
| 1st | **80** | **40** |
| 2nd | **60** | **30** |
| 3rd | **40** | **20** |
| 4th | **30** | **15** |

- **Resolved:** placements beyond 4th earn **0 points** — the table above is the full schedule.
- **Resolved:** ties for a placement (e.g. two semi-final losers both "3rd/4th") are **the tournament's choice** — each tournament defines how it resolves placements it doesn't play out to a definite finish (e.g. play a 3rd-place match, or split/assign the tied points per its own rule). The platform stores the resulting per-participant placement + points rather than imposing a single global tie rule.
- **Resolved (for now):** payout does **not** depend on **draw size** — a single table applies to all tournament sizes, since local (Manila) tournaments are small due to limited courts and cost. However, the implementation should be **flexible enough** to later define the points spread by tournament size (i.e. model the placement → points table as a configurable/selectable schedule, not a hard-coded constant), so a draw-size-tiered table can be introduced when tournaments grow.

### Model decisions

- **Circuit as its own entity.** A `circuits` table (`id`, `name`, `is_active`, audit cols) + admin CRUD, seeded with NORTH/SOUTH — preferred over a free-text field so seeding/renaming and reporting-by-circuit are clean.
- **Resolved — one entity, one UI.** A tournament is an **`EventType.TOURNAMENT` event**, not a separate entity. Circuit + sanction status are conditional fields shown in the existing **Event Organizer** flow when the type is TOURNAMENT. Rationale: an event already provides everything a tournament needs operationally (participants, fixtures, result upload, finalize); the genuinely new work — placement capture + placement-based awarding — exists regardless of entity shape, so a separate entity buys nothing there. The **Circuit** supplies the grouping. If a single tournament ever needs to span **multiple events** (multi-day, multiple sub-draws) under one standing, a parent Tournament entity can be added later without redoing the event work — but given small local draw sizes, one-event-per-tournament fits now.
- **Placement source.** The placement-based table requires each tournament to produce a final ranking of participants. For small draws this is a **host-entered final standings** step at finalize; a full bracket is deferred [#390](https://github.com/cybergrouch/skopeo/issues/390) and not required to ship this.

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

### Open decisions (Part B)

- **Resolved:** points are computed **per set and summed** (see [Per-set aggregation](#per-set-aggregation)). Each set's winner/loser and games drive the table; the overall match winner is not used; multi-set matches can exceed the single-set table values.
- **Resolved — net, no floor.** The upset-loser case yields **−2 / −1**; these are stored as-is (the ledger `points` column is a signed `DECIMAL`) and **net straight into** the band-race total, with **no floor at zero**. Two existing mechanisms naturally bound the effect, so a floor is unnecessary: (1) **band-tagging** — if a run of upset losses actually deflates a player's rating into a lower band, they begin a **fresh points race in the new band** and the old-band negatives no longer weigh on them; and (2) the **validity window** — every award carries a start date and expiry (per the existing points contract), so a negative award ages out. Displays may floor the *shown* number at zero if a negative leaderboard value is undesirable, but the stored/aggregated value is not floored.
- **Resolved — bands locked at event entry.** The rating basis is **fixed at the start of the event** (a player's rating *entering* the tournament / open-play event) and is **not dynamic**. If a team is bumped to a higher or lower NTRP band *during* the event, it does **not** change the points basis — what counts is the band they entered with. This is fairer (matchups are scored on how players stood entering the competition) and stable (every match in the event uses the same snapshot). It is neither match-play-time nor finalize-time — it is an **as-of-event-start** snapshot.
- **Resolved — each recipient's award is tagged with their own entry band.** Consistent with "locked at entry" and the existing per-user, band-scoped ledger: winner and loser each get a row tagged with **their own** band as of event start (not the opponent's, not a finalize-time band).

---

## How this maps onto the current code

Grounded in a read of the points/band/event code (file references for implementers):

### Points are awarded at event finalize
`service/event/EventFinalizeAwarder.kt` (`awardForFinalizedEvent`) is the single choke point. It filters fixtures that are `COMPLETED`, have a `winnerTeamId`, and carry a non-null `designatedPoints`; resolves each winner's **current band** (`ratings.findCurrentRatings`) and sex; maps the event's `pointValidity{Start,End}` to a `PointClass`; and writes one ledger row **per winning-team member** with the host-designated amount.

- **Open play (Part B) plugs in here.** Most inputs are available at finalize time: `match.winnerTeamId` (per set), per-set games (`match.sets[].team1Games/team2Games`), `event.type`, `match.matchType`. The one change from today's flow is the band basis: instead of `findCurrentRatings` (finalize-time), the bands must be resolved **as of event start** (entry band — see the resolved decision above), e.g. from `user_rating_history` as-of the event start date or an entry snapshot taken at registration. The awarder change: for `EventType.OPEN_PLAY` fixtures, replace "read `designatedPoints`" with `computeOpenPlayPoints(entryBandTeamA, entryBandTeamB, sets)` iterating the sets, and **also emit a loser award** (today only winners are paid) — including zero/negative amounts.
- **Tournaments (Part A) need a different branch.** Tournaments award once per **participant** by final placement, not per fixture. The awarder (or a sibling) reads each participant's placement at tournament finalize and writes a single ledger row from the sanctioned/unsanctioned table.

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
No `circuit` or `sanction` concept exists (`EventType` / `MatchType` / `WeightClass` are the only competition-shape enums). Net-new: a **`circuits`** table + admin CRUD; **`circuit_id` + `sanction_status`** columns on the **event** (not a separate tournament table — one entity); a **final-placement** capture per participant; and a **placement-based awarding branch**. Plus the corresponding models, repositories, services, routes, DTOs, OpenAPI entries, and Event Organizer web fields.

---

## Suggested increments (not a commitment)

1. **Open-play computed points** (Part B) — a new `computeOpenPlayPoints(...)`, wired into `EventFinalizeAwarder` for `OPEN_PLAY`, resolving each team's **entry band** (as-of-event-start), iterating sets, and paying losers too, with tests. Needs an as-of-event-start band lookup (rating-history query or an entry snapshot); a new V-migration if we persist an entry snapshot rather than deriving it from history.
2. **Circuits** — `circuits` table + admin CRUD (seed NORTH/SOUTH); model/repo/service/routes/DTO/OpenAPI + an Admin web tab.
3. **Tournaments + sanction** — `circuit_id` + `sanction_status` on the event (conditional Event Organizer fields when type = TOURNAMENT), final-placement capture per participant, and the placement → points table applied at finalize via a new awarding branch. Model the table as a **configurable schedule** so a draw-size-tiered spread can be added later.

## References

- Issue: [#525](https://github.com/cybergrouch/skopeo/issues/525)
- Related deferred work: [#390](https://github.com/cybergrouch/skopeo/issues/390) (tournament brackets)
- Existing points design: [`POINTS_AWARDING_AND_BUDGET.md`](./POINTS_AWARDING_AND_BUDGET.md)
- Rating algorithm & bands: [`RATING_CALCULATION_ALGORITHM.md`](./RATING_CALCULATION_ALGORITHM.md)
- Working model (band matrices, source of truth): Google Sheet linked from #525.
