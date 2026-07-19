# Rating Handicap

> **Status:** Implemented — [#486](https://github.com/cybergrouch/skopeo/issues/486).

A **handicap** is a temporary, per-side rating deduction an organizer can apply to a fixture so that a physically lopsided — but competitively *rated* — matchup produces fairer rating changes. It affects only the rating-delta computation for that one match; it never changes anyone's stored rating baseline.

This is a **fairness safety-valve, not a routine tool.** It is intentionally manual and discouraged by design (see [UX](#ux--discouraged-by-design)) so organizers use it sparingly and with prudence.

## Why

The rating algorithm treats a large game margin between similarly-rated sides as evidence of a skill gap, and moves ratings accordingly. In some matchups the margin instead reflects a physiological or circumstantial difference, not skill:

- **Mixed singles** — a man vs a woman: men tend to dominate on outcome even at equal ratings.
- **Adaptive players** against able-bodied opponents.
- **Large age mismatches** — a junior or senior against a strong competitive player.

In these cases a dominant, "expected-by-reality" result unfairly deflates the disadvantaged side's rating and inflates the opponent's. The handicap lets an organizer tell the algorithm "expect this side to be outscored," so the result moves ratings less (or not at all).

There is **no sex or eligibility restriction** — the organizer decides which matchups warrant a handicap.

## Model

The handicap is set **per side (team), per fixture**. A *side* is one player in singles and two partners in doubles, so a single model covers both formats.

| Property | Value |
| --- | --- |
| Range | `0 < h ≤ 1.0` |
| Units | **Team-mean (player-level NTRP) points** — "knock this side down by `h`", the same for singles and doubles |
| Direction | Always a **deduction** from the designated side |
| Granularity | One value per side, per fixture |
| Who can set it | HOST, CLUB_OWNER, ADMINISTRATOR |
| When | At fixture creation or edit, any time **before the match is rated** (a rated match is frozen) |

Deducting from one side is mathematically equivalent to adding to the other; the deduction form is the standard.

## How it works

The handicap widens the *perceived* rating gap used to compute the match delta. The delta is then applied to the **true** (non-adjusted) rating.

### Singles (side = one player)

1. Compute the delta using the player's **adjusted** rating `= trueRating − h`. The opponent uses their true rating.
2. Apply the resulting delta to the player's **true** rating.

### Doubles (side = two partners)

1. Deduct `h` from the side's **mean** rating. This widens the gap vs the opponent side and yields the team delta `Δ_team`.
2. Split `Δ_team` to the two partners by their **true** rating share (`rᵢ / true_mean`) — the existing proportional distribution, unchanged.
3. Apply each partner's split to their **true** rating.

A per-*player* handicap in doubles is deliberately **not** used: the delta split is proportional to each player's rating, so handicapping an individual would break the split's rating conservation. Deducting from the side **mean** keeps the distribution true-rating-based and only scales the delta's magnitude.

In all cases the **true** rating continues to drive band, ranking points, and confidence — the handicap touches only the delta math for that match.

### Worked example (singles)

- Man `4.0`, Woman `4.0` (competitively equal). Handicap `h = 0.3` on the woman.
- Adjusted woman rating for the calc = `3.7`; the match is computed as **4.0 vs 3.7**, so the man is now *expected* to win.
- Man wins dominantly (6-2, 6-2). A dominant win by the higher-rated side is largely "as expected," so the deltas are small.
- Both deltas apply to the **true 4.0** ratings → the woman is protected from unfair deflation, while a genuine upset (she wins) would still reward her true rating.

### Larger handicaps can neutralize the result entirely

The competitive threshold is `0.5` points (8.3% of the NTRP range): expected wins beyond it yield **zero** rating change. Because a handicap widens the perceived gap, values approaching `1.0` will often push the gap past `0.5`, so an as-expected dominant win produces **no rating adjustment at all**. This is an intended outcome — a strong handicap can fully neutralize the rating impact of an expected result.

## UX — discouraged by design

- On the fixture create/edit form the handicap is **hidden by default**. The organizer must explicitly tick an **"Apply handicap"** checkbox to reveal and enter a per-side value. The friction is deliberate — the feature is exceptional, not routine.
- The checkbox carries a **tooltip** reminding the organizer that the handicap is a **fairness tool to be used prudently** — the goal is a result that is fair to **both** players, not an advantage for one. Suggested copy:
  > A handicap is a fairness adjustment for lopsided-but-competitive matchups. Use it prudently: the goal is a result that's fair to **both** sides, not an advantage for one.
- Once set, the handicap is **shown transparently** to the match participants and organizers. It is a rating-affecting adjustment and is not hidden from those it affects.

## Auditability

- The handicap is persisted per fixture per side and surfaced on the match/fixture.
- **Breakdown caveat:** rating-history breakdown fields (dominance, gap, scale) reflect the *handicapped* gap, while `previousRating` / `newRating` are the **true** values. The applied handicap is recorded in the breakdown/audit context (`appliedHandicap`) so history rows remain interpretable.

## Relationship to the algorithm

The handicap feeds the **existing** gap/dominance/upset logic (see [RATING_CALCULATION_ALGORITHM.md](./RATING_CALCULATION_ALGORITHM.md)) with no special-casing. New behavior lives behind the `RankingCalculator` interface / algorithm versioning.

## References

- Issue: [#486](https://github.com/cybergrouch/skopeo/issues/486)
- [RATING_CALCULATION_ALGORITHM.md](./RATING_CALCULATION_ALGORITHM.md)
- [DOUBLES_RATING_STUDY.md](./DOUBLES_RATING_STUDY.md) — team-mean model and proportional delta split
