# Doubles Rating Calculation — Design Study (#256)

> **Status: analysis + Monte-Carlo complete; recommendation pending sign-off.** This document
> works through two candidate schemes for calculating NTRP rating changes in **doubles** matches
> (four players instead of two), evaluates their mathematics, and runs a Monte-Carlo fairness study
> to choose between them. **Headline: adopt Scheme 2 with the team _mean_ aggregate** (best skill
> recovery + exact rating conservation); reject the sum aggregate (off-scale). See
> [Results](#results) and [Recommendation](#recommendation).

Related: [#256](https://github.com/cybergrouch/skopeo/issues/256),
[RATING_CALCULATION_ALGORITHM.md](./RATING_CALCULATION_ALGORITHM.md),
[RATING_SIMULATION_STUDIES.md](./RATING_SIMULATION_STUDIES.md).

## 1. Background — the shared singles engine

Both doubles schemes reuse the production **v2** per-set calculation
(`service/calculator/impl/v2/PerformanceBasedRankingCalculatorImpl.stepFor`). For a
subject with rating `r_s` playing an opponent with rating `r_o` in one set:

```
d   = dominance   = (gamesWon − gamesLost) / (gamesWon + gamesLost)   ∈ [−1, 1]
adv = r_s − r_o
g   = normalizedGap = |adv| / RANGE                     RANGE = 7.0 − 1.0 = 6.0
upset = (won ∧ adv < 0) ∨ (lost ∧ adv > 0)
scale = upset ?  (g / T) · U                             T = 0.083 (= 0.5 / 6), U = 2.0
              :  max(0, (T − g) / T)
Δ     = K · |d| · scale · sign · matchTypeFactor         K = 0.16, sign = won ? +1 : −1
```

Key facts that matter for doubles:

- **Per-set chaining.** Sets are applied in order; each set uses the *current*
  (already-updated) ratings, and smoothing is applied once to the net change.
- **Zero-sum per set (singles).** Winner and loser see the same `|d|` and the same
  `scale` (both derive from `|adv|`, and `upset` is symmetric), so `Δ_winner = −Δ_loser`.
  The singles system therefore neither inflates nor deflates total rating.
- **Competitive band.** When `g ≤ T` (rating gap ≤ 0.5 NTRP) a non-upset result
  earns a positive `scale`; beyond it the favourite's expected win earns ~0. Upsets
  scale *up* with the gap (×2). This band is calibrated to the **individual** 1.0–7.0
  scale — a fact scheme 2 has to respect.

The only thing a doubles scheme must decide is **what `r_s` and `r_o` are for a
four-player match, and how the resulting change is shared between partners.**

## 2. Scheme 1 — each player vs the opponents' mean

For team `A = {a₁, a₂}` against `B = {b₁, b₂}`, evaluate **each player independently**
with their *own* rating as the subject and the *opponents' mean* as the opposition:

```
r̄_B = (r_b₁ + r_b₂) / 2
Δ_aᵢ = K · |d| · scale(r_aᵢ, r̄_B) · sign_A          for i ∈ {1, 2}
```

(symmetrically for `b₁, b₂` against `r̄_A`). Per-set chaining updates each player's own
rating, so `r̄_B` in the next set reflects the partners' new ratings.

**Mathematical properties**

- **Imputes responsibility by each partner's own expectation vs the opponents' mean.**
  Each partner is credited/debited by how the *team's* result compares to *their own* gap
  to the opposition mean. A weaker partner who helps win is, relative to their own rating,
  an underdog → upset multiplier → gains more; a stronger partner winning "as expected"
  gains ~0. This is Elo-consistent and expectation-correcting — but note it rests on the
  **same rating-level assumption** as scheme 2: a higher-rated player is compared to the
  opponents' *average* (usually below them), so they are presumed the favourite. The flip
  side is that it can be **harsh on a strong player carrying a weak partner** — they gain
  ~0 for the wins they're "expected" to deliver, yet take a large hit whenever the team
  loses, even though we cannot see whether the loss was the partner's doing.
- **Not zero-sum.** `ΣΔ_A = K·|d|·(scale(r_a₁,r̄_B)+scale(r_a₂,r̄_B))` and
  `ΣΔ_B = −K·|d|·(scale(r_b₁,r̄_A)+scale(r_b₂,r̄_A))`. These magnitudes are equal only
  when the two sides' scale-sums coincide, which is not generally true. **Total system
  rating can drift** (inflate or deflate) over many matches — the main risk to watch.
- **Minimal implementation.** Reuses `stepFor` unchanged; the handler just supplies
  `opposition = mean(other team)` and applies each player's own delta.

## 3. Scheme 2 — team aggregate evaluated as singles, split by ratio

Treat each team as a single "virtual player", run the **singles** calculation
team-vs-team, then split the team's change between partners by their rating ratio.

The issue originally proposed **sum** as the aggregate; sum falls off the 1.0–7.0 scale the
reused engine is calibrated to (see below), so the canonical form here uses the **mean** —
the same 1–7-scaled aggregate scheme 1 uses for the opposition — which reuses the singles
evaluation cleanly and still conserves rating:

```
R_A = mean(r_a₁, r_a₂)             R_B = mean(r_b₁, r_b₂)
Δ_team,A = K · |d| · scale(R_A, R_B) · sign_A          (singles step on the team means)
δ_aᵢ = Δ_team,A · r_aᵢ / R_A                           (ratio split, normalized by the mean)
```

The split normalizes by the team **mean** so the mean actually moves by `Δ_team` (the
average-strength partner moves the full `Δ_team`, a stronger partner more, a weaker one
less; `Σδ = 2·Δ_team`, so the mean moves by `Δ_team`). Normalizing by the *sum* instead
just halves every step — a per-match speed knob, not a fairness change; conservation holds
either way. **Sum** is retained only as a simulation variant to show why it is off-scale.

**Mathematical properties**

- **Zero-sum at the team level → conserves total rating.** As in singles,
  `Δ_team,A = −Δ_team,B` (same `|d|`, same `scale`, symmetric `upset`). Splitting each
  team's change *within* the team keeps the four-player total at 0. **No drift/inflation.**
  This is scheme 2's headline advantage.
- **The team aggregate must live on the 1.0–7.0 scale ⚠️.** This is about the *team*
  rating's scale, not about players. The reused step math is hard-calibrated to a rating
  that spans the individual NTRP range: `RANGE = 6.0` and the competitive threshold
  `T = 0.083` assume the quantity runs 1.0–7.0, and — decisively — the per-set `clamp`
  and the `Rating` type both pin the value back into `[1.0, 7.0]`. A team **mean** stays
  on that scale, so the singles evaluation transfers unchanged. A team **sum** spans
  2.0–14.0 and breaks the reuse:
  - **Clamping.** A 4.0 + 4.0 team sums to 8.0, which the `[1.0, 7.0]` clamp (and `Rating`)
    would pin down to 7.0 — the team rating is corrupted before the calculation even runs.
  - **Threshold placement.** `T` is a *fixed* constant meaning "a 0.5 gap on the 1–7
    scale." The real difference in team strength is of course invariable — but interpreting
    a *sum* against this fixed `T`, a "0.5 gap" is two teams whose **average** strengths
    differ by only 0.25. So the "competitive vs expected-win/upset" boundary lands at half
    the average-strength difference `T` was tuned for. Using the **mean** keeps `T` meaning
    a 0.5 average-strength gap, as intended.

  So if scheme 2 is to *reuse the singles evaluation as-is*, the team rating has to be a
  **1–7-scaled aggregate — i.e. the mean** (or another concave aggregate). Sum would
  require re-deriving `RANGE`, `T`, and the clamp for a 2–14 scale. We therefore simulate
  scheme 2 with **mean** as the primary and **sum** only as a variant.
- **Ratio split imputes responsibility by rating share.** Per-player contribution is
  *unobservable* — a doubles result is a team-level signal; we never record how many points
  each partner produced. So **every** scheme must impute individual responsibility from
  ratings, and rating level is the only signal we have. Scheme 2's imputation is "share
  proportional to rating": on the compressed 1.0–7.0 scale the split is gentle (5.0 & 3.0 →
  62.5% / 37.5%) and the higher-rated partner absorbs the larger share of the team's change
  **in both directions**. Note this is the *opposite* within-team distribution from scheme 1
  (which hands the larger move to the *lower*-rated partner on a win — see below), and unlike
  Elo it is not expectation-correcting. Whether "higher-rated carries more" converges each
  player to true skill or slowly **inflates the stronger partner** in recurring partnerships
  is a hypothesis for the Monte-Carlo, not a foregone conclusion.
- **Aggregation realism.** Neither sum nor mean captures that "two 3.5s" ≠ "one 7.0" and
  usually play a touch above their mean; mean is the closest simple proxy. (A concave
  aggregate is out of scope for a first cut.)

## 4. Head-to-head (mathematics)

| Property | Scheme 1 (per-player vs opp mean) | Scheme 2 (team aggregate + ratio split) |
|---|---|---|
| Individual contribution signal used | Rating level (via each player's gap to opp mean) | Rating level (via rating share) — *neither observes true contribution* |
| Within-team split on an even-teams win | **Larger move to the lower-rated partner** (underdog → ×2 upset) | **Larger move to the higher-rated partner** (bigger rating share) |
| Expectation-correcting (Elo-like convergence) | Yes | No — proportional to level, not deviation |
| Conserves total rating (no inflation) | **No** — sides' scale-sums differ → drift | **Yes** — team-level zero-sum |
| Works with the reused 1–7-calibrated engine | Native (per-player ratings) | Only with a 1–7-scaled aggregate (**mean**); **sum is clamped to 7.0** |
| Team treated as a coherent unit | No (independent players) | Yes (single team expectation) |
| Implementation cost | Lowest (reuse `stepFor`, opp = mean) | Low (singles on aggregate + split) |
| Main risk | Rating drift/inflation over time | Strong-player inflation; sum mis-scaling |

**Tension in one line:** Scheme 1 is fairer to the *individual* but may not conserve
rating; Scheme 2 conserves rating but distributes it by *status* rather than *performance*.
The Monte-Carlo study exists to quantify which effect dominates in practice.

## 5. Monte-Carlo fairness study

**Goal.** Decide which scheme best recovers each player's *true* skill from doubles
results alone, without systematic bias or drift. Mirrors the existing
[singles Monte-Carlo study](./RATING_SIMULATION_STUDIES.md) (real calculator, seeded,
thousands of players × many matches).

**Model.**
- Each simulated player has a hidden **true skill** `θ ∈ [1.0, 7.0]` (drawn from a
  realistic NTRP-ish distribution) and a **rating** initialised away from `θ` (e.g. all
  at 3.5, or `θ` + noise) so we can measure convergence.
- **Partnering & matchmaking:** random partners and opponents each round (so a fixed-skill
  player is seen across many partner strengths — the crucial test of individual fairness).
  A secondary regime uses *fixed* partnerships to probe strong-carries-weak inflation.
- **Outcome model:** a set's winner and game margin are drawn from the two teams' true
  skills — team true strength = mean of partners' `θ` (a neutral generator that favours
  neither scheme), with logistic win probability in the gap and a margin that grows with it,
  plus noise. Both schemes score the *same* simulated matches.

**Fairness / quality metrics** (per scheme, after N rounds):
1. **Skill-recovery RMSE / MAE** — `rating − θ` across all players (lower = fairer).
2. **Correlation** of final rating vs `θ` (Spearman/Pearson).
3. **Rating conservation / drift** — mean and total `rating − initial` over time
   (scheme 1's suspected inflation; should be ~0 for scheme 2).
4. **Partner-independence** — variance of a fixed-`θ` player's rating across different
   partner strengths (does a good player converge to the same rating regardless of who
   they're paired with?).
5. **Upset fairness** — for known upsets, does the *responsible* (over-performing) player
   gain more? (directly contrasts the two split philosophies).
6. **Convergence speed** — rounds to reach a stable RMSE.

**Harness.** Implemented in the test source set as a runnable report (a
`DoublesRatingSimulationReport`, same pattern as the singles reports), reusing the real
`stepFor`/set-step engine so we characterise the actual maths, not a re-implementation.
Both schemes are prototyped behind the `MatchTypeHandler` seam introduced in #258.
Seeded for reproducibility; artefacts written under `presentations/` (git-ignored).

### Results

Harness: `DoublesRatingSimulationReport` (test source set), reusing the real v2 engine. Setup:
1000 players, 50 rounds each (≈ 50 matches/player), **single-set** matches, **random** partners and
opponents each round, every player initialised at the true population mean, outcomes driven only by
true skill (team strength = mean of partners' θ). Figures below are **averaged over 5 seeds**.

| Metric | Scheme 1 | Scheme 2 (mean) | Scheme 2 (sum) |
|---|---|---|---|
| Skill-recovery RMSE (lower = better) | 0.735 | **0.675** | 0.871 |
| RMSE, mean-centred | 0.735 | **0.675** | 0.794 |
| Rating vs θ (Pearson) | 0.802 | **0.837** | 0.837 |
| Total-rating drift | −0.001 | **0.000** | −0.357 |
| Within-band σ (partner-independence; lower = better) | **0.108** | 0.150 | 0.060 |

Illustration — **A = {5.0, 3.0} beats even B = {4.0, 4.0}, 6-2** (who gains?):

| | strong partner (5.0) | weak partner (3.0) |
|---|---|---|
| Scheme 1 | +0.000 (favourite, met expectation) | **+0.321** (underdog upset) |
| Scheme 2 (mean) | **+0.100** (larger rating share) | +0.060 |

**Findings**

1. **Scheme 2 (mean) recovers skill best** — lowest RMSE and highest correlation — *and* conserves
   total rating exactly (drift 0.000). Reusing the singles evaluation on the team mean works cleanly.
2. **Scheme 1's feared inflation did not appear** — drift is ≈0 (−0.001). Under random matchmaking the
   non-zero-sum asymmetry washes out. Its real weakness is different: it has the **lowest within-band σ
   (most partner-independent) yet the highest RMSE**, i.e. it **compresses ratings toward the mean**
   (regression/bias). That matches the predicted "harsh on a strong player carrying a weak partner":
   the strong player gets ~0 for expected wins but a full hit for losses, so recurring strong carriers
   drift toward the pack.
3. **Scheme 2 (sum) is disqualified** — the `[1,7]` clamp of over-7 team sums causes systematic
   **deflation (drift −0.357)** and the worst RMSE; correlation/ordering survive but the bias is large.
   Confirms sum is off-scale.
4. **The two within-team splits are genuinely opposite** (illustration): scheme 1 rewards the
   over-performing underdog partner; scheme 2 gives the higher-rated partner the larger move.

**The trade-off is accuracy vs partner-consistency.** Scheme 2 (mean) is more accurate on average and
conserves, but a player's rating is somewhat more partner-dependent (higher within-band σ) and is
credited by rating share. Scheme 1 is more partner-robust and credits over-performance (Elo-intuitive),
but compresses the rating spread (higher RMSE) and is harsh on strong-carries-weak.

### Recommendation

**Adopt Scheme 2 with the team _mean_ aggregate** as the default doubles calculation: it gives the best
skill recovery, conserves total rating exactly, reuses the existing engine without re-calibration, and
has no off-scale pathology. **Reject the sum aggregate** (off-scale → deflation). Scheme 1 is a
reasonable alternative if *partner-independence* is valued above aggregate accuracy, but its
compression/regression is a real downside.

**Candidate refinement (future):** a **hybrid** — keep scheme 2's team-mean expectation and conservation,
but replace the pure rating-ratio split with one that leans on each partner's gap to the opponents'
mean (scheme 1's expectation signal). That could retain conservation + accuracy while improving
partner-independence and restoring intuitive upset credit. Worth a follow-up simulation round if the
plain ratio split proves unpopular.

**Caveats.** Single-set matches and a **team-strength = mean-of-θ** outcome model; the latter is neutral
but does align with scheme 2 (mean)'s aggregate, so before locking in it is worth a sensitivity check
with an alternative generator (e.g. the stronger partner weighted higher, `0.6·max + 0.4·min`) to
confirm the ranking is robust. Reproduce with:
`./gradlew test --tests "*DoublesRatingSimulationReport"` (deterministic; prints the summary above).
