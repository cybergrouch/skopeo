# Points Ranking — Monte Carlo Simulation Study

> **Status:** Study of record for the ranking-points design ([#525](https://github.com/cybergrouch/skopeo/issues/525)). Encodes the points formulas standalone — exactly as the rating studies encode the rating algorithm — to answer policy questions with Monte Carlo simulation.

## Study focus — three questions, in order

This study has evolved through three questions. The first is **settled**; the latter two are the **live focus** and are treated in detail below.

1. **Capping — resolved (yes).** Does a player's score cap, or grow to infinity? It **caps** (points expire → the score plateaus). Estimable in closed form. Covered briefly in [The answer](#the-answer) / §5; not revisited further.
2. **Variance / spread — live focus.** How widely are player scores spread, and does the spread *grow over time*? Treated in detail in Part 2 (§6–7) and compared across schemes in Part 3 (§8) and Part 4 (§9).
3. **Collisions — live focus.** How many players are *tied* on the same total (a congested, uninformative leaderboard)? Treated in detail across §6–9 (Part 4), then reframed **per NTRP band cohort** in Part 5 (§10–12) — which is how the standings actually run and the single biggest legitimate reducer.

**One-line takeaway on 2 & 3:** *diverse increments* widen variance and the ceiling but don't fix collisions on their own; **longer validity** is the real, legitimate collision lever (pooled 99%→85%). A **finer (sub-integer) increment carried as fixed-point integers** appears to push pooled collisions lower still (→46%), but as implemented it separates players by *random noise*, not merit — so it is **dropped** (see [Cons of the finer increment](#cons-of-the-finer-increment) in Part 4). The real fix is structural: because points are **band-tagged**, measuring collisions **within band cohorts** ([Part 5](#part-5--band-scoped-collisions--band-movement-542)) drops them to **~35% at steady state** — on merit, with legible ~60–1,300-point ranges and no fixed-point. Uniform ×N scaling is pure relabeling.

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

> **Headline: the lever is increment _diversity_, not the number type.** Collisions arise when scores are built from a small set of increments that share a common factor — the reachable totals are sparse, so players pile onto the same few values. They fall when the increments are **varied and coprime (GCD = 1)**, which fills the number line densely. Non-integer points only ever helped as *one way to manufacture diversity* — the identical separation is achievable with **integers** (see [fixed-point](#fixed-point-keep-integers-without-losing-fractional-granularity) below). Two corollaries: **uniform scaling never helps** (it preserves the common factor, only stretching the ceiling), and **longer validity helps** because it accumulates more of those diverse increments over time. So the design question is *"are our point increments diverse?"* — not *"are they integers or fractions?"*

Points must remain **integers** (continuous/fractional points are a hard no), and — per the headline above — that costs nothing once increments are diverse. The levers below preserve integer points and are the available knobs, in rough order of value; each feeds back as a tweak to the configurable schedules in the [#525 design](./TOURNAMENTS_CIRCUITS_AND_OPEN_PLAY_POINTS.md).

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

> **Caveat — this only holds for a *diverse, performance-tied* increment (see [Part 4 cons](#cons-of-the-finer-increment)).** The granularity benefit materializes only if the finer increments are (a) genuinely diverse (GCD = 1 *after* scaling) and (b) derived from *performance*, not drawn at random. The Part 4 experiment satisfied neither — its ×100 tiers are all multiples of 25 (GCD = 25) and are drawn independently of the match — so its collision drop is injected noise, not the legitimate granularity effect described here. Pick the base unit to just clear the finest tuned increment (e.g. ×4 for quarter-point tuning), not an arbitrary ×100.

# Part 3 — Alternative open-play scheme: game-margin Fibonacci ([#530](https://github.com/cybergrouch/skopeo/issues/530))

An exploration (for discussion, **not** an adopted change to the #525 formula): does awarding open-play points by **game margin** instead of band difference give better spread / fewer collisions?

## The scheme

Per set, the **winner** gets `fib(2 + margin)` where `margin = winnerGames − loserGames` (clamped at 6); the loser gets 0; a draw gets 0. No bands, no ALP, no negatives.

| Margin | Example set scores | Award = fib(2+margin) |
| ---: | --- | ---: |
| 1 | 6-5, 5-4, 4-3 | **2** |
| 2 | 6-4, 5-3, 7-5 | **3** |
| 3 | 6-3, 5-2, 3-0 | **5** |
| 4 | 6-2, 5-1 | **8** |
| 5 | 6-1, 5-0 | **13** |
| ≥6 | 6-0, 7-0, 8-0 | **21** |
| draw | 6-6, 5-5, … | **0** |

Points are summed per set (same per-set model as the band scheme). So the increment set is **{2, 3, 5, 8, 13, 21}** (Fibonacci) versus the band scheme's **{−2, 0, 1, 2, 3, 5}**.

## Method (Part 3)

Both schemes run on the **same** 2,000-player population and the same default policy (×1 scale, open 2 mo / tournament 6 mo); **only the open-play point function differs** (the tournament component is identical, so the comparison isolates the open-play scheme). Win/loss is the class win rate as before; for the margin scheme the winning margin is drawn from a fixed, documented set-score mix (weights by margin 1→6: 0.18 / 0.25 / 0.20 / 0.15 / 0.12 / 0.10). Seeded/reproducible.

## 8. Head-to-head — band difference vs game-margin Fibonacci

Range = min–max player total; distinct = number of distinct integer totals; collision % = share of players tied on an exact integer total.

| Horizon | band sd | band range | band distinct | band coll% | fib sd | fib range | fib distinct | fib coll% |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1mo | 19.3 | −6–108 | 101 | 99.4% | 23.7 | 0–146 | 115 | 99.4% |
| 2mo | 26.4 | −9–128 | 124 | 99.4% | 36.0 | 0–200 | 161 | 99.3% |
| 4mo | 38.8 | −6–197 | 170 | 98.8% | 46.9 | 0–233 | 204 | 98.7% |
| 8mo | 49.6 | −4–226 | 202 | 99.0% | 58.5 | 0–323 | 238 | 98.7% |
| 1yr | 50.9 | −6–264 | 212 | 98.8% | 56.7 | 0–305 | 236 | 98.9% |
| 2yr | 49.9 | −5–229 | 211 | 98.7% | 56.8 | 0–280 | 238 | 98.7% |
| 3yr | 50.2 | −5–257 | 204 | 99.2% | 57.6 | 0–308 | 243 | 98.2% |

## Findings & recommendation (Part 3)

- **Fibonacci-margin wins on variance and ceiling.** SD is higher at every horizon (e.g. +36% at 2 mo, +11% at 1 yr), the max/range is wider (1-yr ceiling ~305 vs ~264), and it yields **more distinct totals** (e.g. 161 vs 124 at 2 mo; 243 vs 204 at 3 yr). It also removes negatives (min is 0, vs −5…−9 for the band scheme). This is exactly the **"diversity of increments"** effect from Parts 1–2: `{2,3,5,8,13,21}` is more varied and larger-magnitude than `{−2,0,1,2,3,5}`, so it spreads the field further.
- **But it does *not* fix collisions.** Both schemes sit at **~99%** tied throughout. The reason is the same pigeonhole limit as before: with only ~240 distinct reachable totals and 2,000 players, almost everyone shares a total regardless of the increment set. Margin diversity raises the distinct-total count (~15–30%), but nowhere near the thousands needed to separate the field.
- **Verdict.** The Fibonacci-margin scheme is a **genuine improvement in variance, ceiling, and diversity** and is worth considering for #525 on those merits (and for being simpler — no bands/ALP/negatives). But collisions are governed by *distinct-totals vs population size*, so cutting them still requires the earlier levers — **longer validity** (more accumulated events) and/or **fixed-point scaling** (finer granularity) — on top of a diverse increment set. Fibonacci margin is a strong *ingredient*, not a standalone fix. That combination is the subject of Part 4.

# Part 4 — Combined levers: Fibonacci margin + longer validity + fixed-point ([#539](https://github.com/cybergrouch/skopeo/issues/539))

The culminating experiment: apply the three levers **together** and isolate which one actually moves **variance** and **collisions**. All rows use the Fibonacci-margin open-play scheme (diverse increments); they differ only in **validity length**, **scale**, and — the last row — a **finer sub-integer increment** carried as fixed-point integers. Measured on the same 2,000-player population at the **3-year** (fully-warmed) horizon. "Long validity" = open play 12 months / tournaments 36 months (tournaments strictly longer). Range = min–max player total; distinct = number of distinct integer totals; collision % = share of players tied on an exact total.

## 9. Combined-levers comparison (3-year horizon, 2,000 players)

| Scenario | sd (variance) | range (min–max) | distinct totals | collision % |
| --- | ---: | ---: | ---: | ---: |
| Fibonacci, short validity (2mo/6mo), ×1 | 56.6 | 0 – 263 | 234 | 98.9% |
| Fibonacci, **long** validity (12mo/36mo), ×1 | 270.0 | 55 – 1,280 | 804 | **85.1%** |
| Fibonacci, long validity, **×100** (relabel) | 26,997 | 5,500 – 128,000 | 804 | **85.1%** |
| Fibonacci + **finer increment**, long, ×100 | 27,338 | 3,000 – 140,275 | 1,432 | **46.1%** |

## Findings (Part 4) — what actually moves variance and collisions

- **Longer validity is the real lever for both.** Going from short (2mo/6mo) to long (12mo/36mo) validity, on the *same* Fibonacci scheme, roughly **5×'s the variance** (sd 56.6 → 270), widens the range from `0–263` to `55–1,280`, more than **3×'s the distinct totals** (234 → 804), and cuts collisions **98.9% → 85.1%**. More un-expired results accumulate before dropping off, so player histories diverge. This is the single biggest mover of collisions — and, notably, it makes the minimum non-zero (55): with a long window even a modest player always holds some points.
- **Uniform ×100 scaling is pure relabeling — zero effect on collisions.** Scaling the long-validity scheme by ×100 multiplies sd and the range by exactly 100 (ceiling → ~128,000, easily past the ~10,000 target) but leaves **distinct totals (804) and collisions (85.1%) unchanged** — every total is just a multiple of 100. This is the concrete confirmation of the [fixed-point caveat](#fixed-point-keep-integers-without-losing-fractional-granularity): scale changes the axis, never the separation.
- **A finer increment moves the numbers on paper — but see the cons.** Adding a sub-integer sub-tier (integerized by the ×100 scale) drops distinct totals **804 → 1,432** and collisions **85.1% → 46.1%** in the table above. As *implemented*, though, the sub-tier is an **independent random draw**, not a measure of dominance, so the separation it buys is by *noise*, not merit. This disqualifies it as a legitimate lever — see [Cons of the finer increment](#cons-of-the-finer-increment) below.
- **The ceiling target (~10k) is trivially met** once you scale — but the study's whole point is that the ceiling was never the interesting variable; **collisions and variance are**, and they are governed by *distinct-totals vs population size*.

## Cons of the finer increment

The 46.1% figure looks like the win of Part 4, but it is **not** a legitimate collision fix. Four cons, in order of severity:

- **It separates players by random noise, not merit.** As implemented (`fibMarginFractionalOpenPlayPoints`), the sub-tier is an **independent uniform draw** from `{0, 0.25, 0.50, 0.75}`, *uncorrelated* with the game margin that drives the Fibonacci award — i.e. `D = fib(margin) + random × 100`. Two players with identical match histories are pulled apart purely by their random draws. The collision drop is genuine entropy, but it is a **coin-flip tie-breaker** dressed up as a rating difference — arguably worse than an honest tie.
- **If it were instead tied to dominance, it would do nothing.** Make the sub-tier a deterministic function of the margin — `D = fib(margin) + f(margin) × 100` — and it collapses to a single monotonic function of margin: still only 6 reachable per-set values, just relabeled. No new distinct totals, no added variance — the same "pure relabel" as ×N scaling. So the scheme *only* moves the numbers **because** it is random. Either reading disqualifies it: correlated ⇒ relabeling; random ⇒ noise.
- **The framing oversold it, and the mechanism claim was wrong.** It was described as a "dominance sub-tier," implying finer *performance* measurement, while the code draws it at random — a mismatch between design intent and implementation. And the [fixed-point rationale](#fixed-point-keep-integers-without-losing-fractional-granularity) attributes the gain to increments becoming *diverse (GCD = 1)*; at ×100 the reachable awards are all multiples of 25 (**GCD = 25, not 1**), so the drop comes from **4× more reachable values**, not a coprime set.
- **The ×100 over-inflates the cap.** The ~140,000 ceiling is driven by the **×100 magnitude**, not the increment (which adds ≤ 75 per award). ×100 was arbitrary "headroom"; merely integerizing quarters needs only **×4** (`{0,.25,.5,.75} × 4 = {0,1,2,3}`, and `fib × 4 = {8,12,20,32,52,84}` — GCD 1, cap ~25× smaller). The cap blow-up is an over-scaling artifact, fully separable from any granularity claim.

**Legitimate alternative:** reduce collisions *structurally*, not by noise — **longer validity** (accumulates genuine performance history) and **per-band-cohort scoping** ([#542](https://github.com/cybergrouch/skopeo/issues/542): the standings race is per NTRP band, so ties only matter *within* a band). Those separate the field on merit; the finer increment does not.

## Recommendation (Part 4)

To get a leaderboard that both **spreads players** and **keeps separating them over time**, the legitimate recipe is: **diverse increments (Fibonacci-margin) + long validity (tournaments > open play)**, with residual collisions reduced *structurally* via **per-band-cohort scoping** ([#542](https://github.com/cybergrouch/skopeo/issues/542)) rather than by injected noise. Longer validity does most of the collision reduction on merit. The **finer sub-integer increment is dropped** from the recipe — see [Cons of the finer increment](#cons-of-the-finer-increment): its reduction is a random tie-breaker, not real separation. Plain ×N scaling is likewise dropped — it buys legibility-of-magnitude at best, never separation. These are tunable knobs for the #525 validity settings and increment table if a well-separated, evolving leaderboard is desired.

# Part 5 — Band-scoped collisions & band movement ([#542](https://github.com/cybergrouch/skopeo/issues/542))

Every collision figure up to here is **pooled** — it treats all 2,000 players as one race. But ranking points are **band-tagged** ([#525](./TOURNAMENTS_CIRCUITS_AND_OPEN_PLAY_POINTS.md)): the standings run **per NTRP band**, so two players only "collide" if they are tied *and in the same band*. Part 5 recomputes the metrics **within band cohorts**, and then adds a rare **band-movement** model. Per the [Part 4 cons](#cons-of-the-finer-increment), the finer increment and the ×100 fixed-point are **excluded** — this part measures the honest, merit-based recipe only: **Fibonacci-margin open play + long validity (open 12 mo / tournament 36 mo), ×1.**

## Method (Part 5)

Each of the 2,000 players is assigned an NTRP band from a documented recreational mix (seeded, weighted toward the 3.0–4.5 middle). Collisions are then counted *inside* each band cohort and aggregated as a population-weighted rate. The band-movement model moves ~**8% of players per month** (≈ the observed 8-of-100-in-month-1); on a move the player's **current-band total resets to 0** and the old-band points go **dormant** (they reactivate only on a move back), and their active score counts only points earned since the last move. Same seeded population throughout; the exact band split and move rate are tunable and the relative findings are robust to them.

### 10. Band-scoped vs pooled collisions

Population NTRP-band mix (2.5–5.5): 8.0% / 17.8% / 23.5% / 22.4% / 15.2% / 8.4% / 4.8%.

Collision % — pooled (one race) vs band-scoped (within each cohort), for the **current** scheme (band-difference, 2mo/6mo, ×1) and the **long** recipe (Fibonacci-margin, 12mo/36mo, ×1):

| Horizon | pooled (current) | band-scoped (current) | pooled (long) | band-scoped (long) |
| --- | ---: | ---: | ---: | ---: |
| 1mo | 99.4% | 92.0% | 99.1% | 91.7% |
| 2mo | 99.4% | 89.8% | 98.9% | 85.6% |
| 4mo | 98.8% | 85.0% | 98.0% | 75.4% |
| 8mo | 99.0% | 81.9% | 94.5% | 59.6% |
| 1yr | 98.8% | 80.5% | 92.2% | 49.4% |
| 2yr | 98.7% | 80.5% | 88.8% | 41.3% |
| 3yr | 99.2% | 79.5% | 84.9% | **35.5%** |

### 11. Per-band cohort detail at 3 years (long recipe)

| NTRP band | players | sd | min–max points | distinct totals | collision % |
| --- | ---: | ---: | ---: | ---: | ---: |
| 2.5 | 159 | 274.6 | 77 – 1,113 | 142 | 20.8% |
| 3.0 | 355 | 270.6 | 70 – 1,203 | 286 | 34.1% |
| 3.5 | 471 | 274.9 | 59 – 1,229 | 342 | 47.3% |
| 4.0 | 448 | 259.9 | 62 – 1,313 | 340 | 41.7% |
| 4.5 | 304 | 265.7 | 62 – 1,168 | 248 | 32.9% |
| 5.0 | 168 | 292.7 | 70 – 1,305 | 150 | 20.2% |
| 5.5 | 95 | 267.9 | 99 – 1,221 | 89 | 12.6% |

### 12. Band movement (~8% of players move band per month)

| Horizon | moved players | band-scoped (no move) | band-scoped (with move) | mean pts (with move) | mean pts (no move) |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1mo | 6.7% | 91.1% | 91.6% | 22.1 | 23.9 |
| 2mo | 14.2% | 85.8% | 86.4% | 42.7 | 48.9 |
| 4mo | 25.7% | 75.1% | 75.8% | 79.1 | 97.6 |
| 8mo | 42.4% | 58.5% | 62.6% | 132.6 | 193.5 |
| 1yr | 51.4% | 50.1% | 51.7% | 174.0 | 289.7 |
| 2yr | 64.4% | 41.4% | 48.3% | 205.0 | 383.7 |
| 3yr | 70.1% | 34.4% | 47.9% | 215.9 | 471.7 |

### 13. Validity-window recommendation

"Long validity" needs to be **defined and qualified**, not left as "as long as possible." This sweep runs the legitimate recipe (Fibonacci-margin, ×1, band-scoped) across four candidate `(open-play, tournament)` validity stances — tournaments always strictly longer than open play — on the **same seeded draws** (only the window differs), read at the 3-year horizon. Windows map onto the existing `PointClass` tiers (open-play, `SEASONAL_TOURNAMENT_{1,3,6}M`, `ANNUAL_TOURNAMENT` = 12 mo).

| Stance (open / tourney) | pooled coll% | band-scoped coll% | sd | mean pts | range |
| --- | ---: | ---: | ---: | ---: | ---: |
| Current (1 mo / 6 mo) | 98.9% | 82.8% | 52.5 | 61.8 | 0 – 240 |
| Seasonal (3 mo / 12 mo) | 97.1% | 64.3% | 94.2 | 140.5 | 0 – 502 |
| Extended (6 mo / 12 mo) | 95.9% | 57.9% | 113.3 | 191.7 | 0 – 633 |
| Long (12 mo / 36 mo) | 84.9% | 35.5% | 270.7 | 472.2 | 59 – 1,313 |

**Reading the curve.** Every metric is monotonic in validity — longer windows lower collisions and widen spread — but the marginal gains and the costs are not uniform:

- **The biggest single step is Current → Seasonal** (band-scoped 82.8% → 64.3%, −18.5 pp) for a modest change that stays inside existing tiers: open play 1 → 3 months, tournaments 6 → 12 months (annual).
- **Extended adds little** (64.3% → 57.9%) for doubling open-play validity (3 → 6 mo) with tournaments unchanged at annual.
- **Long is the only stance under 50%** (35.5%) — but it requires **36-month tournament validity**, which (a) exceeds every existing `PointClass` tier and (b) means a placement from *three years ago* still counts at full weight, which is hard to defend as "current form."

**Recommendation: `Seasonal` (open play 3 months / tournament 12 months, annual) as the default, with `Extended` (open 6 months) as the tunable upper bound.** Rationale:

1. **Currency of form.** Open play is weekly and low-signal; a **3-month (one-season)** window keeps the leaderboard reflecting recent play while accumulating enough matches to separate. Tournaments are prestige events; a **12-month (annual)** window lets a placement stay meaningful across the competitive cycle and bridges to next year's edition — exactly what `ANNUAL_TOURNAMENT` already encodes.
2. **Maps onto existing config** — no new `PointClass` tier is needed (unlike Long's 36 mo).
3. **Collisions are not validity's job to finish.** Validity should be chosen for *meaning*, not pushed to extremes to chase collisions — the residual is the job of a **tie-breaker** ([#544](https://github.com/cybergrouch/skopeo/issues/544): rating confidence). Picking `Long` purely to reach 35% trades away currency-of-form for a number the tie-breaker resolves anyway.

So `long validity` is qualified as **tournaments outliving open play by roughly 4× and spanning the annual cycle** — concretely **open 3 mo / tournament 12 mo** — not "maximal."

## Findings (Part 5)

- **Band-scoping alone is the biggest legitimate collision lever — bigger than any point-formula change.** Even the *current* scheme drops from **~99% pooled to ~80% band-scoped**, purely by measuring the race the way it is actually run. It costs nothing — the points design is unchanged; only the denominator (who competes with whom) changes.
- **Band-scoping + long validity gets collisions to ~35% with no tricks.** The long recipe falls from 84.9% pooled to **35.5% band-scoped at 3 years** — comparable to the discredited finer-increment result (46% *pooled*), but achieved on merit: diverse Fibonacci increments accumulated over a long window, separated within the cohort that actually competes. No random sub-tier, no fixed-point, no cap inflation.
- **Reward-points ranges are clean and legible.** Per band at 3 years the totals span roughly **60 – 1,300 points** (sd ≈ 260–290), versus the ~140,000 the discarded ×100 finer-increment scheme produced. This is a leaderboard a human can read.
- **Smaller cohorts separate better.** Collisions track cohort size: the 5.5 band (95 players) sits at **12.6%**, the crowded 3.5 band (471 players) at **47.3%**. Fewer players chasing the same reachable totals ⇒ fewer ties — the pigeonhole principle working *for* us once the population is partitioned.
- **Band movement pushes collisions back up modestly and roughly halves the active mean.** With ~8%/month movement, 70% of players have changed band by 3 years; each move resets the active race, so band-scoped collisions rise **35.5% → 47.9%** at 3 years (negligible at ≤1 yr: 50.1% → 51.7%) and the mean active score drops **471.7 → 215.9** (resets keep wiping accumulated points, and freshly-moved players cluster near zero). The dormant-points model means those points are not lost — they reactivate on return — but they do not count toward the current-band race.

## Recommendation (Part 5)

**Measure and display standings per NTRP band cohort** — this is the single most effective, zero-cost collision reducer, and it matches how #525 already tags points. Combined with **qualified validity** (§13: open play 3 mo / tournament 12 mo, annual — *not* maximal), it reaches ~64% band-scoped collisions with **legible point ranges (0–502)** and no reliance on the disqualified finer-increment / fixed-point machinery. Band movement is a real, second-order effect (it re-congests the low end and trims active means as players reset), so the standings UI should expect a meaningful churn of near-zero, freshly-promoted players — but it does not undermine the band-scoping benefit. The residual collisions (which saturate with cohort population — [#544](https://github.com/cybergrouch/skopeo/issues/544)) are the job of a **rating-confidence tie-breaker**, not of pushing validity to extremes. Net: **band-scoping + qualified validity is the recipe; the finer increment and fixed-point are dropped, and a confidence tie-breaker handles the rest.**

## References

- Design of record: [`TOURNAMENTS_CIRCUITS_AND_OPEN_PLAY_POINTS.md`](./TOURNAMENTS_CIRCUITS_AND_OPEN_PLAY_POINTS.md) · Issue [#525](https://github.com/cybergrouch/skopeo/issues/525)
- Sibling simulation studies: [`RATING_SIMULATION_STUDIES.md`](./RATING_SIMULATION_STUDIES.md), [`DOUBLES_RATING_STUDY.md`](./DOUBLES_RATING_STUDY.md)
- Program: `src/test/kotlin/org/skopeo/service/calculator/impl/v2/PointsRankingSimulationReport.kt`
