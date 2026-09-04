# Match Score Correction — How Changing a Rated Score Affects Ratings, Points, Confidence, and Clamping

**Status:** Implemented (#776) · **Tracking issue:** [#776](https://github.com/cybergrouch/skopeo/issues/776) · Related: event-scoped reversal [#478](https://github.com/cybergrouch/skopeo/issues/478) (`service/event/EventRatingsReverser.kt`), persisted calculation breakdown [#97](https://github.com/cybergrouch/skopeo/issues/97), confidence [#459](https://github.com/cybergrouch/skopeo/issues/459).

This is the reference for **what happens when an already-rated match's score is corrected**. Read it before changing anything in the correction path — several of the decisions here are deliberate approximations, and one of the open questions people keep re-asking (confidence) is already settled by the code.

## 1. The problem

A match is entered with the wrong score. It has already been rated (`matches.rated_at` is set), and it sits **in the middle** of the players' history — both players have played and been rated for matches *after* it.

The two pre-existing paths do not cover this:

| Path | Why it doesn't apply |
| --- | --- |
| Score edit (`POST /api/v1/matches/{id}/result` and friends) | Refuses once `rated_at != null` (`MatchService.kt:364`, `MatchService.kt:422`). |
| Event **Reverse Ratings** (#478) | Restores each participant to their **pre-event** rating snapshot, so it is only sound **at the rated tip** — and `EventService.reverseRatings` guards for exactly that. On a mid-history event it would discard every rating change that happened afterwards. |
| Full cascading recalculation | The textbook fix, but it re-rates every subsequent match those players played, which moves their opponents' ratings, and so on outward. Rejected — see §6. |

## 2. The correction rule — swap one delta for another

For each player `p` in the corrected match:

```
newCurrent(p) = currentRating(p) − oldDelta(p) + newDelta(p)
```

- **`oldDelta(p)`** — the `rating_change` on that player's **live** `user_rating_history` row for this match. Reversed **verbatim**, because that is the value that was actually applied (post-clamp, post-smoothing).
- **`newDelta(p)`** — the delta the calculator produces for the **corrected score**, computed from the **historical inputs** persisted on the superseded history row (#97): `previous_rating`, `previous_level`, the per-side handicaps, `scale`, `k_factor`, `competitive_threshold_pct`.

> **`newDelta` is NOT computed from the players' current ratings.** This is the single most important rule in this document. Using the historical `previous_rating` makes the operation a clean *swap of one delta for another*, and keeps the result faithful to "what should have been calculated at the time". Computing from the current rating would conflate the correction with every match played since.

Nothing downstream is re-rated. The players' subsequent matches, and their history rows, are left exactly as they are.

### 2.1 A player whose rating was never applied (#881)

Since calibration, **not every participant necessarily has a delta to reverse.** While one player is in a calibration window the others' computed changes are discarded rather than applied, so those players have **no history row for the match**.

The rule is therefore per player: **reverse exactly what was applied, or nothing.**

- A player **with** a row is corrected exactly as above.
- A player **without** one is left untouched: no delta reversed, no replacement row written, no rating change. `reversedChange`, `newChange` and `netAdjustment` all report `0`, and the response flags them `wasSuppressed` so those zeroes are distinguishable from a correction that happened to cancel out.

Two consequences worth stating plainly, because both are easy to get wrong:

1. **A player suppressed at rating time stays suppressed on correction**, even if the calibration window has since closed. The decision belongs to the state as it was when the match was rated — which the presence or absence of a row records — not to the state now. Otherwise correcting an old match would retroactively start moving a settled opponent's rating, applying a change that was deliberately withheld.
2. **A suppressed player's rating is still an input.** It is reconstructed from their most recent history row at or before the match's rating time (falling back to their current rating), because the rating gap it feeds decides the *other* side's corrected delta. It is read, never written.

The precondition is correspondingly *"at least one player has a live row"*, not *"every player does"* — the latter made any match involving a calibrating player permanently uncorrectable. A rated match with **no** rows for anyone remains a conflict: suppression only ever applies to players who were not calibrating while someone else was, so it can never remove them all.

## 3. What each subsystem does

### 3.1 Ratings

`user_ratings.current_rating` / `current_level` move by `(newDelta − oldDelta)`, re-clamped (§3.4). The match stays `rated_at != null` — it must **not** re-enter the pending-calculation queue, or the delta would be applied twice.

### 3.2 Rating history — append-only, with a correction marker

The ledger is never rewritten in place:

1. The original rows are **superseded** by stamping `user_rating_history.reversed_at` (the #478 primitive; all read paths already exclude non-null `reversed_at`).
2. A **new row** is inserted for the recomputed calculation, carrying an explicit **correction marker** so the rating-history UI can label it as a correction rather than presenting it as an ordinary match rating.

### 3.3 Ranking points — reverse and re-apply, mirroring the rating

If the corrected score changes the points outcome, the match's awards are **revoked and re-issued**, using the same append-only pattern as the rating side: `RankingPointRepository.revoke(...)` appends a REVOKED marker rather than deleting.

Every award written on event finalize is **match-scoped** — `EventFinalizeAwarder.awardWrite` takes a non-nullable `matchId: UUID`, so both awarding paths attribute to a specific match:

| Event type | How it pays | Cross-match coupling? |
| --- | --- | --- |
| OPEN_PLAY | Per match, computed from band difference, to **both** winner and loser | **No** — self-contained per match. |
| TOURNAMENT | Per **placement match** (Super Finals → 1st/2nd, Plate Finals → 3rd/4th, semis → 3rd/4th flat), from the sanction-selected schedule | **Yes** — see below. |

(`ranking_point_awards.match_id` is nullable only because the manual POINTS_MANAGER award and EXTERNAL adjustment paths in `RankingPointService` aren't match-derived at all. There are no event-scoped, match-less awards from finalize.)

**Placement matches are coupled across matches, and that is the real constraint.** There is no separate "bracket winner" record — placement is *derived* at finalize time from `matches.winner_team_id` + `placement_bracket`. But placement points are paid **by place**, so who won a placement match determines who receives 1st- vs 2nd-place points; it is not merely record-keeping. The coupling is the **best-placement guard** in `EventFinalizeAwarder.awardPlacement`: *"a player earns exactly one placement award — their best"*, enforced by processing placement matches best-place-first (`ctx.awarded`). So a naive per-match revoke/re-issue on a placement match can leave a player holding two placement awards, or holding one that is no longer their best.

**Therefore: a correction to a placement match must recompute the event's whole placement award set**, re-running the `awardPlacement` logic, rather than re-issuing that one match's awards in isolation. Corrections to non-placement matches (and to any OPEN_PLAY match) stay safely confined to per-match revoke/re-issue.

One thing that does *not* need handling: `hasCompletedPlate` — which shifts the semi-finalists' place mapping — keys off the *existence* of a completed Plate Finals, not off who won it. A score-only correction cannot flip it.

### 3.4 Clamping — bounded, rare, and invertible given what we store

`clamp(value) = value.max(1.0).min(7.0)` (`PerformanceBasedRankingCalculatorImpl.kt:270`), applied per set-step and to the final result. Delta magnitude is `K(0.16) · |dominance| · scale · sign`, where `scale` folds in the match-type factor (≤ 1.2) and the upset multiplier (≤ 2×) — so per-match movement is on the order of **tenths** of an NTRP point, not units.

- Clamping therefore only bites for players sitting within roughly a few tenths of **1.0 or 7.0**. For a realistic population (~2.0–5.5) the effect is nil.
- Clamping makes a delta **non-invertible in principle** — you cannot recover the unclamped pre-match value from the clamped result alone. In practice this does not matter, because the history row stores `previous_rating` directly (#97). So: **reverse the stored `rating_change`, recompute from the stored `previous_rating`, and re-clamp only the final result.** Residual error is zero unless the *corrected* result itself clamps.

### 3.5 Confidence — a score correction changes it by **zero**

Confidence (#459, revising #343) is **computed on read and never stored** — `RatingEntityMappers.kt:39` calls `confidenceAt(...)` on every rating read. It is a 3-factor multiplicative score over the player's COMPLETED matches in a 30-day window (full detail in [RATING_CONFIDENCE_SPARSITY.md](RATING_CONFIDENCE_SPARSITY.md)):

```
weightedCount = 3.0·tournaments + 0.5·openPlays
recency  = f(daysSinceLastMatch)
sparsity = f(30 / weightedCount)
spacing  = f(maxInternalGap)
confidence = recency · sparsity · spacing        where f(x) = 1 / (1 + (x/35)^2.5)
```

Its **only** inputs are match **dates**, match **weight class**, and **`now`**. Ratings, deltas, and rating-history rows are not inputs at all. Consequences:

- **A score-only correction has no effect on confidence whatsoever.** There is nothing to unwind, and confidence is already "based on the actual date of the match" by construction.
- **`user_ratings.matches_since_reset` and `match_rated_at` are vestigial for confidence** — `RatingRepository.kt:290-296` states this explicitly; they survive only for band-hop bookkeeping and "no longer affect confidence." A correction must **leave both alone**, and must **not** bump `matches_played` (the match was counted when it was first rated).
- Confidence moves only if a correction also changes the match's **date**, its **match_type** (OPEN_PLAY ↔ TOURNAMENT is a 6× swing in the sparsity weight, 0.5 → 3.0), or its **status** (in/out of COMPLETED). Those effects can be large — moving a player's only in-window match out of the 30-day window drops their confidence to 0. **This is why the correction facility is scoped to score-only.** If date or match-type editing is ever added, the preview must surface the confidence delta.

> Anyone revisiting "should a correction unwind confidence state?" should stop here: the question is an artifact of the retired #343 model.

## 4. Who can do it, and where

- **The public match page** (`/matches/:code`, `web/src/routes/MatchPage.tsx`) — not a dashboard tab. The editor is `web/src/components/MatchScoreCorrectionCard.tsx`.
- **ADMINISTRATOR capability only.** That page is otherwise fully anonymous, so the viewer lookup is best-effort: no token simply means no capabilities and no editor, and the public render is never blocked or delayed by it. `POST /api/v1/matches/{id}/score-correction` enforces ADMINISTRATOR server-side regardless; the UI gate is convenience, not security.
- The endpoint is keyed by the match's internal UUID, which the public page does not otherwise expose. `MatchPublicResponse.id` is therefore **revealed to ADMINISTRATOR viewers only** — the same viewer-conditional shape the precise rating rates already use — and is null for everyone else. The editor renders nothing without it.
- Editing the score runs the reversal **and** recalculation as **one operation** in **one transaction** — the admin does not separately reverse and re-rate.
- **Dry-run preview is the confirmation surface.** "Preview correction" calls the endpoint with `dryRun: true` (which writes nothing) and shows the per-player impact — current rating, delta reversed, new delta, net adjustment, resulting rating, band change, and a warning when the winner flips. Only the separate "Apply correction" sends `dryRun: false`. Editing any field after previewing retracts the Apply button, so an admin can never apply numbers they did not preview.

## 5. Visibility and audit

- A corrected match carries a persisted marker (`matches.re_rated_at`, alongside `re_rated_count`) driving a **"Re-rated" badge**, exposed on the public match response as `reRated` so the badge renders for everyone — a transparency signal that the score was corrected after rating, not a staff-only detail.
- Both halves are written to the **Activity Log** (`GET /api/v1/audit` → Admin tab), mapped onto existing audit categories: the score edit under `MATCH_RESULT`, the re-rating under `RATING_CALCULATION`. Entry details carry the match public code, old → new score, any winner change, and per-player `oldDelta` / `newDelta` / net adjustment — the same payload shape as `EVENT_RATINGS_REVERSED`.

## 6. The accepted approximation — and why

Because the players' subsequent matches were rated against the **wrong intermediate rating**, their deltas would have been slightly different had the corrected score been in place. Correcting one match in place does **not** fix that drift.

**This is accepted deliberately.** Ratings are estimates; the error introduced by an in-place correction is within acceptable tolerance, and a full cascading recalculation is not worth its cost or its blast radius. This is a product decision, not a known defect — do not "fix" it by adding a cascade without revisiting the decision with the product owner.

## 7. Invariants a correction must preserve

1. `current − oldDelta + newDelta`, per player, in one transaction; a repeated commit of the same correction cannot double-apply.
2. Matches rated **after** the corrected one are untouched; their history rows still read correctly.
3. The corrected match stays rated and never reappears in the pending-calculation queue.
4. History and award ledgers stay **append-only** — supersede/revoke, never delete.
5. `matches_since_reset`, `match_rated_at`, and `matches_played` are not touched.
6. Correcting a score back to its original value is a **net no-op**.
7. Computed confidence is unchanged by a score-only correction.
