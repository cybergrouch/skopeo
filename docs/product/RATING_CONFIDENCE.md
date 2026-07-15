# Rating Confidence (#343)

Every NTRP rating shown in Skopeo carries a **confidence** — a percentage that says how much to
trust that the band reflects the player's *current* form. It is displayed next to the NTRP band
everywhere a band appears.

Confidence is **computed on read, never stored**. It is derived server-side; the UI only formats the
value it receives as a percentage (it does no calculation of its own).

## What it means

- **High (→100%)** — the rating was set by a recent match-result calculation and has enough matches
  behind it to be settled.
- **Low (→0%)** — the rating is stale (no recent matches), was just reset by a big move, or was never
  earned through play (a sign-up self-rating or an admin/RATER override).

## How it's calculated

Confidence is the product of a **time-decay** factor and a **match-count ramp**:

```
decay      = 1 / (1 + (days / 35)^2.5)     # days since the last match-calc rating (log-logistic)
scale      = min(1, m / 5)                 # m = matches since the last reset
confidence = decay × scale                 # 0..1, shown as a percentage
```

### Time decay — how fresh the rating is

A **log-logistic** curve over the number of **days** since the rating was last set by a match-result
calculation:

- `1.0` on the day of the calculation,
- `≈0.5` around **35 days** later,
- trailing smoothly toward `0` thereafter.

### Match-count ramp — how settled the rating is

`scale = min(1, m / 5)`, where `m` is the number of matches applied **since the last reset**. A rating
climbs from ~0 to full confidence over roughly **5 matches**.

### Resets — when confidence drops back to ~0

Three events reset the ramp (`m → 0`, so confidence restarts near 0 and rebuilds over the next ~5
matches):

1. a **sign-up self-rating**,
2. an **administrator / RATER override**,
3. an **NTRP band jump** — when a match calculation moves the player to a new band, we treat it like an
   override: the rating just changed materially, so we're less sure of it until it stabilises.

A rating that isn't match-derived at all (a self-rating or an override that hasn't yet been followed by
a match calculation) has **no** match-calc timestamp and therefore reads **0%** until the next match.

## Where it comes from in the data

- Confidence is computed in `service`/`repository` read paths from two facts recorded on the player's
  current rating: the timestamp of the match calculation that last set it, and the count of matches
  since the last reset. Neither the confidence value nor the old stored `confidence_score` column is
  persisted.
- A band jump during a calculation and any override both reset the match count; the calculation stamps
  the match-calc timestamp, an override clears it.

## Examples

| Situation | Confidence |
| --- | --- |
| Rated by a match calc today, 6+ matches since the last reset | ~100% |
| Match-rated ~35 days ago, well past 5 matches | ~50% |
| Just jumped to a new NTRP band (0 matches since) | ~0%, rebuilding |
| Sign-up self-rating, no matches played yet | 0% |
| Admin/RATER override, no match since | 0% |
