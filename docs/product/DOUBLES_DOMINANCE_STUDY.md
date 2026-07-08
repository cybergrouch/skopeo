<!--
SPDX-FileCopyrightText: 2026 Lange Pantoja
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# Doubles: dominance, within-team gap, and how a win splits between partners

A doubles analogue of the [NTRP matchup matrix](RATING_SIMULATION_STUDIES.md) study. It answers: for a
doubles win, how do the **dominance** (game margin) and the **within-team NTRP gap** of the winning pair
determine each partner's rating change under the shipped **v2 team-mean** scheme
(`service/calculator/impl/v2/DoublesMatchTypeHandler.kt`, #256)?

Regenerate with:

```bash
./gradlew generateDoublesDominanceReport   # -> /tmp/doubles_dominance.md, presentations/doubles_dominance.md
```

(Backed by `DoublesDominanceReport` in the v2 calculator test sources.)

## Method

To isolate the effect of the **within-team gap**, every winning pair is held at the **same team mean of
3.5** and plays the **same even opponent** — a 3.5 + 3.5 pair. Because the team rating in the team-mean
scheme is the mean of the partners, all five pairs enter the formula as the *same* mean-3.5 team against
the *same* mean-3.5 opponent, so the team-level change is identical across pairings; only the **split**
between the two partners differs. Five pairings, by within-team gap:

| Within-team gap | Weaker partner | Stronger partner |
|---|---|---|
| 0.0000 | 3.5000 | 3.5000 |
| 0.5000 | 3.2500 | 3.7500 |
| 1.0000 | 3.0000 | 4.0000 |
| 1.5000 | 2.7500 | 4.2500 |
| 2.0000 | 2.5000 | 4.5000 |

The score is swept over every legal single-set result (6-0 … 7-6), which varies the dominance factor
`(gamesWon − gamesLost) / (gamesWon + gamesLost)`. This is a what-if endpoint, so the match-type factor is
1.0. The team mean (3.5) equals the opponent mean, so the win is *competitive/expected* (not an upset):
scale = 1.0, and the team-mean move is simply `K × dominance = 0.16 × dominance`.

## Results — wins

Each cell is the winning partners' rating change as **stronger Δ / weaker Δ**. **Team Δ** is the
team-mean move — identical across every pairing for a given score.

| Score | Dominance | Team Δ | gap 0.0 (S / W) | gap 0.5 (S / W) | gap 1.0 (S / W) | gap 1.5 (S / W) | gap 2.0 (S / W) |
|---|---|---|---|---|---|---|---|
| 6-0 | 1.0000 | +0.160000 | +0.160000 / +0.160000 | +0.171429 / +0.148571 | +0.182857 / +0.137143 | +0.194286 / +0.125714 | +0.205714 / +0.114286 |
| 6-1 | 0.7143 | +0.114286 | +0.114286 / +0.114286 | +0.122449 / +0.106123 | +0.130613 / +0.097959 | +0.138776 / +0.089796 | +0.146939 / +0.081633 |
| 6-2 | 0.5000 | +0.080000 | +0.080000 / +0.080000 | +0.085714 / +0.074286 | +0.091429 / +0.068571 | +0.097143 / +0.062857 | +0.102857 / +0.057143 |
| 6-3 | 0.3333 | +0.053333 | +0.053333 / +0.053333 | +0.057143 / +0.049523 | +0.060952 / +0.045714 | +0.064762 / +0.041904 | +0.068571 / +0.038095 |
| 6-4 | 0.2000 | +0.032000 | +0.032000 / +0.032000 | +0.034286 / +0.029714 | +0.036571 / +0.027429 | +0.038857 / +0.025143 | +0.041143 / +0.022857 |
| 7-5 | 0.1667 | +0.026667 | +0.026667 / +0.026667 | +0.028572 / +0.024762 | +0.030477 / +0.022857 | +0.032381 / +0.020953 | +0.034286 / +0.019048 |
| 7-6 | 0.0769 | +0.012308 | +0.012308 / +0.012308 | +0.013187 / +0.011429 | +0.014066 / +0.010550 | +0.014945 / +0.009671 | +0.015825 / +0.008791 |

## Results — losses

The same pairs **losing** the even match, on four pairings (equal partners are omitted — they lose
equally). Each partner's loss is proportional to their share of the team mean, so this is the **exact
negation** of the win table.

| Score | Dominance | Team Δ | gap 0.5 (S / W) | gap 1.0 (S / W) | gap 1.5 (S / W) | gap 2.0 (S / W) |
|---|---|---|---|---|---|---|
| 6-0 | 1.0000 | -0.160000 | -0.171429 / -0.148571 | -0.182857 / -0.137143 | -0.194286 / -0.125714 | -0.205714 / -0.114286 |
| 6-1 | 0.7143 | -0.114286 | -0.122449 / -0.106123 | -0.130613 / -0.097959 | -0.138776 / -0.089796 | -0.146939 / -0.081633 |
| 6-2 | 0.5000 | -0.080000 | -0.085714 / -0.074286 | -0.091429 / -0.068571 | -0.097143 / -0.062857 | -0.102857 / -0.057143 |
| 6-3 | 0.3333 | -0.053333 | -0.057143 / -0.049523 | -0.060952 / -0.045714 | -0.064762 / -0.041904 | -0.068571 / -0.038095 |
| 6-4 | 0.2000 | -0.032000 | -0.034286 / -0.029714 | -0.036571 / -0.027429 | -0.038857 / -0.025143 | -0.041143 / -0.022857 |
| 7-5 | 0.1667 | -0.026667 | -0.028572 / -0.024762 | -0.030477 / -0.022857 | -0.032381 / -0.020953 | -0.034286 / -0.019048 |
| 7-6 | 0.0769 | -0.012308 | -0.013187 / -0.011429 | -0.014066 / -0.010550 | -0.014945 / -0.009671 | -0.015825 / -0.008791 |

## Findings

**1. Dominance sets the magnitude, not the distribution.** The team-mean move (Team Δ) is `0.16 × dominance`
— from the full `±0.160000` of a 6-0 shutout down to `±0.012308` for a 7-6 nailbiter — and is **identical
across all five pairings** for a given score. Margin of victory decides *how much* rating is at stake; it
says nothing about how it's shared.

**2. The within-team gap sets the split, proportional to each partner's share of the team mean.** Each
partner moves by `Δ_team × (partnerRating / teamMean)`. Equal partners (gap 0) split a win evenly. As the
gap widens the stronger partner takes a larger share and the weaker partner a smaller one — e.g. on a 6-0,
the stronger partner's gain climbs `0.160 → 0.171 → 0.183 → 0.194 → 0.206` across gaps 0 → 2.0 while the
weaker partner's falls `0.160 → 0.149 → 0.137 → 0.126 → 0.114`.

**3. The split ratio is dominance-independent.** For a fixed gap, the stronger:weaker ratio is constant
down every row — it equals the ratio of the partners' ratings (their shares of the mean):

| Gap | Stronger : weaker ratio |
|---|---|
| 0.0 | 1.00 (3.50 : 3.50) |
| 0.5 | 1.15 (3.75 : 3.25) |
| 1.0 | 1.33 (4.00 : 3.00) |
| 1.5 | 1.55 (4.25 : 2.75) |
| 2.0 | 1.80 (4.50 : 2.50) |

So dominance and the within-team gap act on **orthogonal axes**: dominance is the magnitude knob, the gap
is the distribution knob.

**4. Conserved and calibrated.** The two partners' changes always sum to `2 × Team Δ` — each partner is a
full member of the team, not half of one — while the team mean still moves by exactly the amount a single
player of that mean would move in singles. Total rating is conserved across the four players (before any
boundary clamping).

**5. Losses mirror wins exactly.** In an even match a loss is not an upset (scale is still 1.0), so the
losing pair's changes are the point-for-point negation of the win table: `Team Δ = −0.16 × dominance`,
split by the same rating-share proportions. So the *within-team gap governs losses the same way it governs
wins* — on a 6-0 loss the 2.0-gap stronger partner drops `−0.205714` while the weaker drops only
`−0.114286` (vs `−0.16` each for equal partners). The gap decides who absorbs more of the loss; the score
decides how big it is.

**6. Implication.** Pairing down concentrates the swing on the stronger partner **in both directions**: at
a 2.0 gap they gain *and* lose **1.8× the weaker partner on every result**, regardless of score — the
stronger partner is simply more volatile in doubles. This is the mechanism behind the partner-strength
discussion in the anti-farming analysis — it is bounded, symmetric between wins and losses, and (per the
[Monte-Carlo doubles study](DOUBLES_RATING_STUDY.md)) neutralised in practice by combined-rating
matchmaking, where opposing pairs share the same team mean.
