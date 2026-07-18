# Rating Confidence (#459, revising #343)

Every NTRP rating shown in Skopeo carries a **confidence** — a percentage that says how much to
trust that the band reflects the player's *current* form. It is displayed next to the NTRP band
everywhere a band appears.

Confidence is **computed on read, never stored**. It is derived server-side; the UI only formats the
value it receives as a percentage (it does no calculation of its own).

## What it means

- **High (→100%)** — the player has a dense recent run of meaningful matches, so their current form is
  well evidenced.
- **Low (→0%)** — the player has little or no recent play in the evaluation window (or only a few light
  matches), so their current form is thinly evidenced.

## How it's calculated — sparsity + match-type weighting

Confidence rewards **consistent, meaningful activity** over a fixed **30-day window**. Higher-stakes
matches tell us more about true skill, so each is weighted before summing:

```
weightedCount = 3.0·tournaments + 1.5·leagues + 0.5·openPlays   # completed, in the last 30 days
if weightedCount <= 0: confidence = 0                           # no qualifying play → 0%
averageGap    = 30 / weightedCount                              # days per weighted match
confidence    = 1 / (1 + (averageGap / 35)^2.5)                 # log-logistic, midpoint 35 days
```

Denser / higher-weight play shrinks the average gap and lifts confidence toward `1`; light or absent
play widens the gap toward — and past — the 35-day midpoint, dropping confidence toward `0`.

### Match-type weights

| Match type | Weight class | Weight |
| --- | --- | --- |
| `TOURNAMENT_INITIAL_ROUND`, `TOURNAMENT_PLAYOFFS` | Tournament | **3.0** |
| `LEAGUE_PLAY`, `LEAGUE_PLAYOFFS` | League | **1.5** |
| `OPEN_PLAY` | Open play | **0.5** |

### Tunables

`WINDOW_DAYS` (30), `TARGET_MIDPOINT_GAP` (35), `DECAY_SHAPE` (2.5), and the per-class weights (3.0 /
1.5 / 0.5) are centralized as named constants in `model/RatingConfidence.kt`.

### Which matches count

**COMPLETED** matches the player took part in (either team), whose `matchDate` falls within the last
30 days as of now (inclusive lower bound — a match exactly 30 days back still counts; future-dated
matches are excluded). Sourced from `MatchRepository.weightedMatchCountsInWindow`, batched for the
list views (standings, seeding, match/history pages) so a page of N players costs one query, not N.

## Design notes (#459)

- **Density, not true clustering.** `window / weightedCount` measures matches-per-window, so two
  players with the same weighted count but different spacing score identically (model A in
  `docs/product/RATING_CONFIDENCE_SPARSITY.md`). This rewards volume + match quality and no longer
  over-rewards a recent burst, but it does not by itself penalize clustering.
- **Reset / ramp dropped.** The former days-since-last-match decay and the post-reset ramp
  (`min(1, matchesSinceReset/5)`) plus the NTRP band-jump reset semantics are removed — a low weighted
  count already yields low confidence. The `user_ratings.match_rated_at` / `matches_since_reset`
  columns remain (no migration) but are now **vestigial for confidence**.

## Examples (30-day window)

| Player | Matches | Weighted count | Average gap | Confidence |
| --- | --- | --- | --- | --- |
| A — the "gulf" scenario | 2 open play | 1.0 | 30 d | ≈ 59.5% |
| B — consistent casual | 8 open play | 4.0 | 7.5 d | ≈ 97.9% |
| C — efficient tournament player | 2 tournament | 6.0 | 5.0 d | ≈ 99.2% |
| No qualifying play in the window | — | 0 | — | 0% |
