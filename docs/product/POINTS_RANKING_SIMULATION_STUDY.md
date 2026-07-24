# Points Ranking — Monte Carlo Simulation Study

> **Status:** Study of record for the ranking-points design ([#525](https://github.com/cybergrouch/skopeo/issues/525)). The points formulas are **design-only** (not yet implemented); this study encodes them standalone — exactly as the rating studies encode the rating algorithm — to answer a policy question **before** we build.

## The question

**Is there a point where a player's reward points cap? Can we estimate that cap? Or do points grow to infinity?**

## The answer

**The score is bounded — it does not grow to infinity — because points expire.** A player's leaderboard score is the sum of their still-valid points, and each award drops out after its validity window. Once a player's *earning rate* equals their *expiry rate*, the score plateaus. The plateau is the cap:

> **Cap ≈ Σ over event types of ( events per day × expected points per event × validity days )**
>
> equivalently **`Cap ≈ (validity ÷ cadence) × E[points per event]`** per event type, summed.

The Monte Carlo mean matches this closed-form estimate to within noise (see §5), which is the empirical proof of boundedness: if the score diverged, no finite estimate would track it.

Three consequences fall out:

1. **Validity is a linear dial on the cap.** Doubling a validity window doubles that component's cap. Open play at 2 months caps at ~2× its 1-month value; tournament points at 12 months cap at ~4× their 3-month value (§2, §3).
2. **The cap is finite and estimable from known factors** — cadence, win/placement rate, and validity — with no free parameters once those are fixed.
3. **There is also a hard absolute ceiling** (all-wins upper bound) = `(validity ÷ cadence) × max points per event`, e.g. **45** open-play-only, **240** tournaments-only, **330** for a heavy player, under the default validity policy (§5). Real expected caps sit well below these.

**Bottom line:** points do **not** go to infinity. Under the default policy (open play 2 mo, tournament 6 mo), a realistic even-skill player who plays weekly and enters a tournament every two months plateaus around **80 points**; a strong, heavy-playing competitor around **120**; the theoretical ceiling for that behaviour is **330**. Validity length is the primary lever on where the plateau sits.

## How to run (reproducible)

```bash
./gradlew generatePointsSimulationReport
```

Writes `/tmp/points_ranking.txt` and `presentations/points_ranking.md` (git-ignored). Deterministic: **seed `20260724`**, **40,000 trials per cell**. Source: `src/test/kotlin/org/skopeo/service/calculator/impl/v2/PointsRankingSimulationReport.kt`. The tables below are copied from a run of that program.

## Methodology

Two independent player axes give a 3 × 4 grid of archetypes:

**Skill class** — win rate on open play and chance of a tournament placement:

| Class | Open-play win rate | Tournament placement chance |
| --- | ---: | ---: |
| Below 50% | 35% | 35% |
| Even 50% | 50% | 50% |
| Above 50% | 65% | 65% |

**Behaviour class** — attendance cadence (frequency for the "only" classes is taken to match the balanced player's respective cadence):

| Class | Open play | Tournament |
| --- | --- | --- |
| Open play only | 1×/week | never |
| Tournaments only | never | 1 per 2 months |
| Balanced | 1×/week | 1 per 2 months |
| Heavy-open | 2×/week | 1 per 2 months |

**Points model** (the [generalized open-play algorithm](./TOURNAMENTS_CIRCUITS_AND_OPEN_PLAY_POINTS.md#generalized-algorithm-implementation-spec) and the placement table, encoded standalone):

- Open play is single-set. Per match the player is **equal-band** (p = 0.40), the **higher band / favorite** (p = 0.30), or the **lower band / underdog** (p = 0.30); win/loss is a Bernoulli draw at the class win rate, independent of the band relation; on an unequal-band loss the loser clears the ALP ≥ 4-games threshold with p = 0.50. Points then follow the parameter table (equal 3/0; favorite-win 2, loser 1 + ALP; upset-win 5, loser −2 + ALP).
- A tournament yields a placement with probability = the class placement chance; given a placement it is 1st/2nd/3rd/4th with p = 0.10 / 0.20 / 0.30 / 0.40. Points use the **sanctioned** table (80/60/40/30). **Unsanctioned is exactly half**, so every tournament figure below halves for an unsanctioned circuit.

**Score metric.** Events occur at a fixed cadence (the randomness is in outcomes, not timing). The score is read at a **uniformly-random instant** in a steady window (final year of a 3-year horizon, so every window ≤ 12 months is fully warmed). A random phase offset places the snapshot at a random point on the event grid, so the expected active-event count is exactly `validity ÷ cadence`. Negative open-play awards net in.

**These assumptions are documented so they can be tuned; the relative findings (linearity in validity, boundedness, tournament dominance) are robust to them.** The band-relation mix (40/30/30) and placement distribution (10/20/30/40) are the two most load-bearing.

## Results

### 1. Expected points per event

| Skill class | Open-play match (avg pts) | Tournament (avg pts, sanctioned) |
| --- | ---: | ---: |
| Below 50% | 1.2 | 15.4 |
| Even 50% | 1.7 | 22.0 |
| Above 50% | 2.1 | 28.6 |

A single tournament placement is worth **~10–20× an open-play match** on average — the dominant driver of a high score.

### 2. Open-play steady-state score (mean active points)

| Skill class | Cadence | 1-month validity | 2-month validity | 2mo ÷ 1mo |
| --- | --- | ---: | ---: | ---: |
| Below 50% | 1×/wk | 5.0 | 10.1 | 2.0× |
| Below 50% | 2×/wk | 9.9 | 20.1 | 2.0× |
| Even 50% | 1×/wk | 7.1 | 14.4 | 2.0× |
| Even 50% | 2×/wk | 14.2 | 28.8 | 2.0× |
| Above 50% | 1×/wk | 9.2 | 18.7 | 2.0× |
| Above 50% | 2×/wk | 18.4 | 37.4 | 2.0× |

Validity is a clean linear multiplier; cadence (attendance) and win rate scale the plateau proportionally.

### 3. Tournament steady-state score (mean ± sd, 1 tournament / 2 months)

| Skill class | 3-month | 6-month | 12-month | 12mo ÷ 3mo |
| --- | ---: | ---: | ---: | ---: |
| Below 50% | 22.9 ± 29.0 | 45.9 ± 39.9 | 92.0 ± 56.3 | 4.0× |
| Even 50% | 32.9 ± 32.3 | 66.0 ± 43.0 | 131.8 ± 60.7 | 4.0× |
| Above 50% | 43.0 ± 33.6 | 86.1 ± 42.9 | 171.6 ± 60.6 | 4.0× |

Tournament points are **large and bursty** — the standard deviation rivals the mean at short validity because the score jumps on a placement and decays between tournaments. Longer validity both raises the plateau (linearly) and smooths the variability (more concurrent active tournaments).

### 4. Combined steady-state score — default policy (open play 2 mo, tournament 6 mo)

Mean total leaderboard points (p5 / median / p95 in parentheses).

| Behaviour class | Below 50% | Even 50% | Above 50% |
| --- | ---: | ---: | ---: |
| Open play only (1×/wk) | 10.1 (1/10/20) | 14.4 (4/14/24) | 18.7 (9/19/28) |
| Tournaments only (1 / 2 mo) | 46.3 (0/40/120) | 65.8 (0/60/140) | 86.0 (30/90/160) |
| 1×/wk open + 1 / 2 mo tourney | 56.4 (5/49/131) | 80.1 (13/78/156) | 104.3 (40/103/178) |
| 2×/wk open + 1 / 2 mo tourney | 66.1 (13/60/142) | 94.6 (26/92/172) | 123.3 (56/122/197) |

### 5. Is the score capped? Yes — it plateaus (default policy)

The Monte Carlo mean (the realised expected cap) matches the closed-form `rate · μ · V` estimate; the ceiling is the all-wins upper bound.

| Behaviour class | Skill | MC mean (expected cap) | Analytic rate·μ·V | Absolute ceiling |
| --- | --- | ---: | ---: | ---: |
| Open play only (1×/wk) | Below 50% | 10.1 | 10.1 | 45 |
| Open play only (1×/wk) | Even 50% | 14.4 | 14.4 | 45 |
| Open play only (1×/wk) | Above 50% | 18.7 | 18.7 | 45 |
| Tournaments only (1 / 2 mo) | Below 50% | 46.3 | 46.1 | 240 |
| Tournaments only (1 / 2 mo) | Even 50% | 65.8 | 66.0 | 240 |
| Tournaments only (1 / 2 mo) | Above 50% | 86.0 | 85.9 | 240 |
| Balanced | Below 50% | 56.4 | 56.2 | 285 |
| Balanced | Even 50% | 80.1 | 80.4 | 285 |
| Balanced | Above 50% | 104.3 | 104.5 | 285 |
| Heavy-open | Below 50% | 66.1 | 66.3 | 330 |
| Heavy-open | Even 50% | 94.6 | 94.7 | 330 |
| Heavy-open | Above 50% | 123.3 | 123.2 | 330 |

The MC-vs-analytic agreement across every cell is the evidence: the score converges to a **finite** plateau equal to `rate × E[pts/event] × validity`, not to infinity.

## Findings & policy implications

- **No runaway.** Expiry guarantees a finite plateau. The only way points would grow unbounded is to remove the validity window entirely.
- **Validity is the master lever.** The cap is linear in validity. Choosing tournament validity = 12 months roughly quadruples the tournament plateau vs 3 months and lets a single strong season dominate the table for a year; 3–6 months keeps standings fresher. This is a product choice, now quantified.
- **Tournaments dominate magnitude; open play provides a stable floor.** One 1st place (80) outweighs ~40 open-play matches. Open-play points are small but steady; tournament points are large but bursty (high variance at short validity).
- **Estimating any configuration.** For any cadence/validity, `Cap ≈ (validity ÷ cadence) × E[pts/event]`, with `E[open] ≈ 1.2–2.1` and `E[tourney, sanctioned] ≈ placementChance × 44` (halve for unsanctioned). No simulation needed for a first-order estimate — the sim confirms it and supplies the distribution.
- **Skill and attendance scale the plateau proportionally**, not explosively — a below-average player and an above-average player differ by well under 2× at equal behaviour.

---

# Part 2 — Point spread & collisions over time ([#530](https://github.com/cybergrouch/skopeo/issues/530))

Part 1 measured a single player's *expected* score. Part 2 asks a **population** question: as time passes, do players **spread out** on the leaderboard, or do many **collide** on the same total? And can we raise the ceiling (toward ~10,000) and make variance keep growing?

## Method (Part 2)

A fixed population of **2,000 players** is simulated from day 0. Each player is assigned a skill class (Below/Even/Above, weights **30/40/30**) and a behaviour class (open-only / tournaments-only / balanced / heavy-open, weights **30/10/40/20**), then given a full event timeline; their still-valid score is read at **1mo, 2mo, 4mo, 8mo, 1yr, 2yr, 3yr**. A **collision** is a player sharing an exact integer total with at least one other player; **collision %** is the share of players who are not unique. Seeded (`20260724`), reproducible; run `./gradlew generatePointsSimulationReport`.

## 6. Spread & collisions over time — baseline (current design)

| Horizon | mean | sd | IQR p25–p75 | min–max | collision % | distinct totals |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1mo | 15.3 | 19.3 | 4.0–16.0 | −6.0–108.0 | 99.4% | 101 |
| 2mo | 31.0 | 26.4 | 12.0–47.0 | −9.0–128.0 | 99.4% | 124 |
| 4mo | 45.9 | 38.8 | 14.0–73.0 | −6.0–197.0 | 98.8% | 170 |
| 8mo | 61.1 | 49.6 | 16.0–97.0 | −4.0–226.0 | 99.0% | 202 |
| 1yr | 61.4 | 50.9 | 17.0–94.0 | −6.0–264.0 | 98.8% | 212 |
| 2yr | 60.5 | 49.9 | 16.0–95.0 | −5.0–229.0 | 98.7% | 211 |
| 3yr | 61.2 | 50.2 | 16.0–100.0 | −5.0–257.0 | 99.2% | 204 |

**The concern is confirmed.** Spread grows only until the validity window fills (~8 months), then **freezes**: sd plateaus at ~50 and stays there through year 3, and **~99% of players collide** on a shared integer total at *every* horizon (only ~200 distinct totals across 2,000 players). Because points expire, variance cannot keep growing — the leaderboard stops separating players once steady state is reached.

## 7. Raising the ceiling & growing variance — scenario comparison

| Scenario | max @1yr | sd @1yr | coll% @1yr | max @3yr | sd @3yr | coll% @3yr |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Baseline (×1, 2mo/6mo) | 264 | 50.9 | 98.8% | 257 | 50.2 | 99.2% |
| Scaled ×30 (2mo/6mo) | 7140 | 1527.5 | 98.9% | 6930 | 1521.5 | 99.0% |
| Long validity (×1, 12mo/36mo) | 578 | 108.8 | 96.7% | 988 | 235.0 | 88.9% |
| Recommended (×10, 12mo/36mo) | 5360 | 1059.9 | 96.2% | **10430** | 2353.3 | **88.6%** |

Two independent levers:

- **Scaling raises the ceiling but does NOT reduce collisions.** ×30 lifts the top score to ~7,000, yet collision % stays ~99% — multiplying every award just re-labels the same clustered structure on a wider axis.
- **Longer validity grows variance over time and cuts collisions.** With 12-month open / 36-month tournament validity, points *accumulate*: sd rises from year 1 to year 3 (108→235) instead of freezing, and collisions fall from ~99% to ~89%. This is the only lever that makes the leaderboard keep separating players as time passes.
- **Combined ("Recommended", ×10 + long validity)** reaches a **~10,430** ceiling at 3 years with **growing** variance (sd 1060→2353) and the lowest collision rate (~89%).

## Findings & recommendation (Part 2)

- **To raise the ceiling to ~10,000:** scale the point values (a ~×10 multiplier on the whole schedule puts a strong, active player near 10k). Scaling alone is a cosmetic axis change — necessary for the ceiling target, not sufficient for separation.
- **To make variance grow over time (fewer collisions):** lengthen validity so points accumulate rather than plateau (e.g. open play 12 months, tournaments 24–36 months), or replace hard expiry with a slow decay/carry-over. This is the lever that actually spreads the field.
- **Recommended adjustment:** a **~×10 point scale** combined with **12-month open-play and 24–36-month tournament validity** — reaches a ~10k ceiling, keeps variance rising through year 3, and roughly halves the collision *gap* (99%→89%).
- **Residual collisions are a granularity limit.** Even the best scenario leaves ~89% of players tied, because points are small integers drawn from a discrete set — most players accumulate near-identical sums. Continuous/fractional points would dissolve this, but **non-integer points are a hard product constraint (ruled out)**, so the fix must stay integer — see [Recommendations — integer-only tuning](#recommendations--integer-only-tuning) below.
- **Trade-off to weigh:** longer validity and higher ceilings separate players but make the table slower to refresh and the numbers larger/less legible. The `10430` ceiling and `~89%` collision floor quantify both sides so the product choice is explicit.

## Recommendations — integer-only tuning

Points must remain **integers** (continuous/fractional points are a hard no), so the ~89% collision floor cannot be removed by adding granularity. The levers below preserve integer points and are the available knobs, in rough order of value; each feeds back as a tweak to the configurable schedules in the [#525 design](./TOURNAMENTS_CIRCUITS_AND_OPEN_PLAY_POINTS.md).

1. **Longer validity (primary lever).** The only knob that makes variance *grow over time* rather than plateau — more un-expired results accumulate before dropping off, so player histories diverge. Recommended: open play ~12 months, tournaments ~24–36 months (or replace hard expiry with a slow decay/carry-over).
2. **A dominance / margin component.** Let per-set points vary with *how* a set was won (games won / margin), not just win-vs-loss — e.g. a small bonus for a dominant set. This injects **varied increments**, which is what actually adds distinct reachable totals; it is the biggest collision-reducer that stays integer, and it rewards performance.
3. **Graduate points by band-gap size.** Reward a 2–3-band upset more than a 1-band one — revisiting the *binary* equal/unequal choice made in #525. Adds distinct values; the trade-off is a less-simple table.
4. **Standings tie-breakers.** Even when two players share a point total, order the leaderboard by a secondary key (current rating, recency, head-to-head). This resolves the *ranking position* users actually see **without changing the points model at all** — the highest value-for-effort option and fully integer-safe.
5. **More tournament placement tiers.** Extend the 1st–4th schedule to 5th–8th (etc.) so tournament-heavy players spread further.

**Why uniform scaling is not enough:** multiplying every value by a constant leaves all totals as multiples of that constant, so the collision *pattern* is unchanged — only **diverse, non-common-factor increments** (levers 2–3) add distinct totals. Scaling raises the ceiling; it does not separate the field.

**Recommended integer-only combination:** **longer validity + a margin/dominance component + standings tie-breakers**, with graduated bands and extra placement tiers as optional further separation. A follow-up can extend this simulation to quantify levers 2–3 (margin component, graduated bands) before adopting them.

### Fixed-point: keep integers without losing fractional granularity

The ~89% floor above is a *granularity* limit, not a *type* limit — and granularity does **not** require a fractional points type. Fractional points are simply **integer points at a finer scale**: pick a base unit small enough to represent every tuned increment exactly (like currency uses cents, not dollars), multiply the whole schedule by the least common denominator, and every value becomes a whole number with nothing lost. e.g. increments `{3, 2, 5, 0, −2, 1}` plus tuned `{0.5, 1.5, 0.25}` × 4 → `{12, 8, 20, 0, −8, 4, 2, 6, 1}` — all integers.

The condition that makes this reduce collisions (rather than just relabel them): the resulting integer increments must be **diverse (GCD = 1)**.

- **Uniform scaling of the current coarse set does nothing** — `{3,2,5,0,−2,1} × 100` are all multiples of 100, so the collision pattern is unchanged (this is why ×10 alone kept collisions ~99%).
- **Integerizing *diverse fractional tuning* works** — `{12,8,20,4,2,6,1}` has GCD 1, so partial sums fill the integer line densely and collisions fall. The granularity must come from the *diversity of the tuned increments* (levers 2–3); fixed-point scaling preserves that diversity exactly as integers.

So the practical path is: design the margin/graduated-band tuning in whatever fractional terms are natural, then **multiply the entire schedule by a common base unit** (e.g. ×100, "centi-points", for headroom) to ship **pure integers**. Storage is unaffected — the ledger `points` column is already a signed `DECIMAL` — and the display can be scaled down for legibility. The ×scale also raises the ceiling as a bonus. This removes the earlier "continuous points" caveat: the hard-no on a fractional *type* does not block the granularity benefit.

## References

- Design of record: [`TOURNAMENTS_CIRCUITS_AND_OPEN_PLAY_POINTS.md`](./TOURNAMENTS_CIRCUITS_AND_OPEN_PLAY_POINTS.md) · Issue [#525](https://github.com/cybergrouch/skopeo/issues/525)
- Sibling simulation studies: [`RATING_SIMULATION_STUDIES.md`](./RATING_SIMULATION_STUDIES.md), [`DOUBLES_RATING_STUDY.md`](./DOUBLES_RATING_STUDY.md)
- Program: `src/test/kotlin/org/skopeo/service/calculator/impl/v2/PointsRankingSimulationReport.kt`
