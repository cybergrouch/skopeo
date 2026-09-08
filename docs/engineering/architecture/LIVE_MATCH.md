# LiveMatch — umpire scoring and the spectator scoreboard

Design and decision record for the LiveMatch feature (#911): an **umpire view** for keying in points as
they happen, and a **public spectator view** that watches the score update in real time.

This document is written *before* the implementation and is meant to be edited as it lands. Where a
decision has been made, it is recorded with the reasoning; where one is still open, it says so plainly.

---

## 1. What is being built

**Umpire view** — full-screen landscape, split into a left and a right side matching the players' court
sides. Gated to a new `SCORER` capability. The umpire:

- increments a side's score (`0 → 15 → 30 → 40 → Deuce → Advantage → game`);
- **undoes** the previous action (the only backward operation);
- **ends a set** early, even below 6 games;
- marks a player **Retired** or **Default**;
- switches into **tiebreak** scoring, where points are ordinal and the *umpire* declares the winner
  rather than the UI assuming a target;
- sets **who is serving**;
- **flips the view** left-to-right for convenience when players change ends — a display preference only;
- **finalizes**, writing the result into the existing match fixture.

**Spectator view** — public, shareable, read-only. Players, set scores, game score and server, updating
live, in the shape of a broadcast tennis score annotation. At finalize it shows the winner.

---

## 2. The constraint that shaped everything

**Cloud Run runs `--min-instances=1 --max-instances=2`.**

An in-process broadcast — SSE or WebSocket with an in-memory subscriber registry — is therefore broken
by construction. The umpire's write lands on instance A; a spectator's stream is held open by instance
B; the event never crosses. It fails non-deterministically and **worse under load**, because a popular
match is precisely what triggers the scale to two.

Pinning to `max-instances=1` is not a fix: it removes the app's only headroom during the event you most
want to survive.

There is a second, independent trap: Cloud Run's request timeout defaults to **300s** and is not
configured in `deploy-api.yml`, so a naive SSE connection dies every five minutes and needs
reconnect-and-resync logic regardless.

### What choosing Firestore resolves — and what it does not

Both of the above **go away**. Spectators subscribe to Firestore rather than to Cloud Run, so nothing
is fanned out between instances and no request is held open. The instance count and the 300s timeout
stop being relevant to this feature.

One multi-instance concern **survives**, and it is easy to miss because the loud one is gone:

- **Concurrent umpire writes.** The umpire still POSTs to Cloud Run, and with two instances two rapid
  taps can be handled simultaneously by different instances. Both read the same log state, both append,
  and the result is a lost update or an out-of-order log.
- **No in-memory scoring state.** For the same reason, the engine must stay stateless per request:
  load the log (or its folded state) from Postgres, apply the event, then write. An in-memory cache of
  the current score would diverge between instances.

The fix for the first is the same one `match_number` uses (#898): a **monotonic sequence number per
match with a unique constraint**, so the losing write fails outright rather than silently interleaving,
and the client retries with the next sequence. This also gives the log a total order for free, which
replay depends on.

---

## 3. Decision — Firestore as the broadcast channel

**Decided.** Spectators subscribe to Cloud Firestore; the broadcast never passes through Cloud Run, so
the instance count stops mattering.

### Why

- **Already in the project.** `web/src/lib/firebase.ts` initializes Firebase for Auth today. Firestore
  is one more SDK import against the same project.
- **No instance affinity.** Fan-out is Google's problem, not ours. `max-instances` becomes irrelevant.
- **Reconnect and offline are handled by the SDK.** A phone losing signal courtside is a *when*.
- **Cost is noise.** Priced against the live SKU catalog for `asia-southeast1`: reads are
  **$0.000000061** each. A busy match — 200 points, 100 concurrent spectators — is 20,000 reads,
  about **$0.0012**. A hundred such matches a month is roughly **$0.13**. Reads dominate writes 100:1,
  so the conclusion is robust to the exact write price.

### What it costs us

A genuinely new dependency, a second place where state lives, and Firestore security rules to maintain.
Vendor lock-in on the real-time layer — though the project is already tied to Firebase Auth, which is
the harder of the two to leave.

### Alternatives considered

| Option | Why not |
|---|---|
| SSE + Postgres `LISTEN/NOTIFY` | Works across instances, but every instance holds a long-lived connection and `db-f1-micro` allows ~25 in total. Fights the database tier. |
| Redis / Memorystore pub-sub | Correct, and ~$35/month — roughly 3× the entire production database, for a handful of concurrent matches. |
| Client polling | No new infrastructure and a perfectly honest fallback, but it is the thing the feature explicitly set out to avoid. Keep it in mind as the degradation path. |

---

## 4. Decision — writes go through the API; Firestore is read-only fan-out

**Decided.** The umpire does **not** write to Firestore directly.

```
umpire (web or native)  ──POST──▶  Cloud Run  ──▶  Postgres   (event log, source of truth)
                                        │
                                        └──▶  Firestore  (score document, server-written)
                                                   │
                                        spectators ◀┘  (subscribe, read-only)
```

### Why this way round

Capabilities live in **Postgres** (`user_capabilities`), not in Firebase custom claims — there is no
`setCustomUserClaims` anywhere in the codebase. Firestore rules therefore *cannot* see who holds
`SCORER`, nor whether they may organize that event under #789.

Letting the client write would force one of two bad outcomes: mirroring the whole capability model into
Firebase custom claims (a second copy of authorization, free to drift), or accepting weaker rules than
the rest of the product enforces.

Routing writes through the API instead means:

- **authorization stays in one place** — the existing Kotlin capability checks and
  `ClubAccess.mayOrganize`;
- **Firestore rules become trivial** — nobody writes, anyone reads, which is very hard to get wrong on
  a public collection;
- **Postgres stays the source of truth**, so finalize and the audit story are unaffected.

### What it costs us

The backend gains a dependency it does not have: it currently verifies Firebase JWTs itself against
Google's public keys (`JwkProvider` in `Security.kt`) and does **not** use the Firebase Admin SDK.
Server-side Firestore writes will need it.

The umpire's write also becomes a normal API round-trip rather than an optimistic local write, so the
umpire's **offline behaviour becomes ours to build**. The append-only event log makes that tractable —
hold un-POSTed events locally and retry — but it is work that Firestore-direct would have given away.

---

## 5. Decision — native apps are unaffected, and slightly better off

**Decided** in the context of possible future Android/iOS apps.

Firestore has first-class Google-maintained SDKs for Android and iOS with the same listener API, and
**offline persistence on by default on mobile** — which is exactly the property an umpire app standing
on a court wants.

More importantly, the shape in §4 means a native umpire app is *identical* to the web one: it POSTs to
the same API. Only the spectator view subscribes to Firestore. A bespoke SSE/WebSocket protocol would
instead have meant writing and maintaining reconnect, resync, backoff and ordering **three times**.

---

## 6. Decision — the event log appends; undo never pops

**Decided.**

The umpire's actions are an append-only log, folded into the current score. Undo appends a
**compensating entry** referencing the undone action rather than removing it.

The stated goal is to "audit everything", and popping is the single operation that defeats that: the
log should still be able to say *the umpire corrected themselves here*.

**Events name players or teams, never "left" or "right".** The side-flip is a display preference and is
deliberately not recorded, so a log written in terms of screen position would be corrupted by a flip.

### What spectators receive is state, not keystrokes

**Decided.** The broadcast carries the **derived score**, never the umpire's raw actions. Every umpire
action — including an undo — follows the same path: append the event, fold the log, write the resulting
score document. An undo reaches spectators as *the score changed back*, not as an operation they have to
interpret.

The append-only log and the corrections in it are an internal concern. Nothing about the umpire's
keystrokes belongs in a public payload.

Three things follow, and they are all in our favour:

- **Reconnect is trivial.** A spectator who misses updates just reads the current document. There is no
  gap to fill, no replay, no ordering to reconstruct — which is the main practical advantage of
  broadcasting state rather than events, and it matters on a phone at a court.
- **The payload carries the sequence number** from §2, so a late-arriving write cannot overwrite a newer
  one and a client can tell whether what it is holding is stale.
- **The log never needs to be public**, which mostly answers the open question in §11 about whether
  spectators need history: they do not, and not exposing it keeps the public surface to one document.

---

## 7. Decision — the scoring engine is pure, and the umpire is authoritative

**Decided.**

`ScoreEngine.apply(state, event): state` as a pure function, mirroring `RankingCalculator.calculate()` —
which returns its result plus an audit trail and performs no side effects, and is why its tests need no
mocking (see [AUDIT_TRAIL.md](AUDIT_TRAIL.md)). Deuce, advantage, tiebreak and set completion can then
be tested exhaustively without a database.

The engine is **permissive**: it tracks points, and the umpire declares game, set and match. The feature
already requires that the UI not presume a tiebreak target and that a set may end below six games.
Encoding every format variant is where tennis scoring implementations go to die.

### Two functions, and only one of them holds the rules

```kotlin
// The step. Every scoring rule lives here and nowhere else.
fun apply(state: ScoreState, event: ScoreEvent): ScoreState

// The replay. Derived — a fold over `apply`, not a second implementation.
fun replay(log: List<LoggedEvent>): ScoreState =
    effective(log).fold(initial = ScoreState.start(...)) { state, event -> apply(state, event) }
```

`replay` is the one the server actually calls; `apply` is where deuce, advantage, tiebreak and set
completion are decided. Keeping `replay` a fold rather than its own traversal is the point: there is a
single implementation of the rules, so the live path and the replay path cannot drift — which is the
classic way event-sourced scoring goes wrong.

### `apply` should never see an UNDO

This is the part that is easy to get wrong. Undoing is not a scoring operation and cannot be expressed
as one: "un-applying" a point needs to know what the state was *before* it, which a step function does
not have.

So resolve undo **before** folding. `effective(log)` walks the log, drops each event cancelled by a
later `UNDO` marker (and the markers themselves), and hands `apply` nothing but real scoring events.
`apply` stays a plain tennis step function with no history awareness, and the append-only log from §6 is
preserved intact for the audit summary.

### `ScoreState` is the current score, and it is never stored as the truth

It holds what the scoreboard needs: points in the current game per side, games in the current set,
completed sets, who is serving, whether the game is a tiebreak, and whether the match has ended. That is
deliberately close to the spectator payload in §6 — the document written to Firestore is a projection of
this, not a separate model.

Two rules follow from §2 and §8a, and both are easy to violate later "for performance":

- **Do not cache `ScoreState` in memory between requests.** The server is stateless per request because
  two Cloud Run instances would otherwise diverge. Each write is `replay(log)` → `apply(new event)` →
  persist → project.
- **Do not read the Firestore document back to obtain current state.** It is an outbound projection for
  spectators. The log is the input; reading the projection back would make it an authority it is not.

**No snapshotting.** A match is a few hundred events, so folding the whole log on every write costs
microseconds. Introducing snapshots would add a second thing that can be stale for no measurable gain.

---

## 8. Decision — finalize goes through the existing result path

**Decided.**

Live state must not become a second system of record. On finalize, the log is translated into the
existing `MatchResultRequest` and submitted through the existing `uploadResult`, so ratings (#403),
ranking points, and score correction (#776) keep working untouched.

`MatchSetResult` already carries `tiebreakTeam1Points` / `tiebreakTeam2Points`, so the shape fits.
Note that `deriveOutcome` validates that every set is decisive and the sets are not tied — a retirement
will not satisfy it unchanged (see §10).

---

## 8a. Decision — the stack is working state, and it has a lifecycle

**Decided.** The live stack is *not* a second store of scores. It exists because the engine needs an
input to fold into a displayable score, and it is **disposable once the match is recorded**.

### Where it lives, and what lives where

| | Holds | Why there |
|---|---|---|
| **Postgres** | the event log (append-only, one row per umpire action) | the server folds it, it needs the per-match sequence + unique constraint from §2, and finalize is transactional with it |
| **Firestore** | the *current derived score* only — one document per live match | spectators need the latest state, not the history; keeping the log out of Firestore also keeps the public surface small |
| **`match_sets`** | the final result | the permanent record, written through the existing `uploadResult` (§8) |

The log is never the answer to "what was the score of match X" once the match is over — `match_sets`
is. That is what keeps this a front end rather than a parallel store.

### The tension: "audit everything" versus "disposable"

The feature asks for the stack so that *everything can be audited*, and also says it should be
disposable. Those conflict if the raw log is simply deleted — the accountability goes with it.

**Resolution: the durable audit is a summary in the existing domain audit log; the raw per-point stack
is working state.** On finalize, record an `audit_log` entry (#100/#102) capturing who scored the match,
how many actions were logged, how many were undone, and when it was finalized. That preserves the
answerable questions — *who scored this, and did they correct themselves* — without keeping several
hundred per-point rows per match forever.

If per-point forensics later turn out to be genuinely needed, that is a retention decision to revisit
deliberately, not something to keep by default.

### When it is disposed

**Decided: finalize never deletes the stack.** Disposal is always deferred to a separate sweep.

1. Finalize writes the result via `uploadResult`, writes the audit summary, and marks the stack
   **completed**.
2. A later sweep prunes completed stacks past a retention window.

Deleting inside the finalize transaction would be tidier, and was rejected: a mis-finalized match would
have nothing left to inspect, and finalize is the exact moment you are most likely to want to look. The
cost of keeping it is a table that grows until the sweep runs, which is a much better problem.

Separating the two also means **the sweep is the only thing that deletes**, so there is one place to
reason about retention rather than two, and one place to fix if it is ever wrong.

That gives the **abandoned session** case somewhere to be handled (§11) as well: a stack that never
finalizes is the same cleanup problem, and a fixture must not be left stuck `IN_PROGRESS` because
someone closed a laptop. Note the sweep therefore has two distinct jobs — pruning *completed* stacks,
and resolving *stale in-progress* ones — and they may well want different windows.

**Open: the retention window itself.** Nothing here fixes a number. It wants to be long enough that a
disputed result can still be inspected and short enough that the table does not accumulate several
hundred rows per match indefinitely.

### What this means for score correction

A post-finalize fix goes through the existing score-correction path (#776), not by reopening the stack.
The stack is an input to recording a result, not a mechanism for revising one — otherwise it becomes
the system of record by the back door, which §8 exists to prevent.

## 9. Decision — `SCORER` is a role set, not a hierarchy

**Decided.**

The feature describes `SCORER` as "inherited by HOST → CLUB_OWNER → ADMINISTRATOR". There is no
inheritance mechanism in this codebase; it composes role **sets**, and #908 established the pattern:

```kotlin
val RATING_ROLES: Set<Capability> = MATCH_MANAGEMENT_ROLES + Capability.RATER
```

So `SCORING_ROLES = MATCH_MANAGEMENT_ROLES + Capability.SCORER` produces the described behaviour. A true
capability hierarchy remains unbuilt and unraised.

**This needs a database migration**, which is easy to miss: `user_capabilities` carries a
`chk_capability` CHECK enumerating the permitted values, so a `SCORER` grant is rejected until it is
widened. `V16__points_budget.sql` has the drop-and-recreate pattern.

---

## 10. Open — how is a retirement or default rated?

**Not decided. Probably its own issue.**

Neither is representable today. `MatchStatus` is `SCHEDULED | IN_PROGRESS | COMPLETED | CANCELLED`, and
nothing in the match DTOs or domain mentions retirement or a walkover.

The harder half is that **the rating pipeline has no notion of one**. `RankingCalculator` computes from
games won, so a match abandoned at 3–1 in the first set would otherwise be rated as a 3–1 win. The same
question applies to ranking points and to the confidence model.

The options are roughly: not rated at all; rated at the score reached; or recorded as a loss for the
retiring player. This is a product decision with rating consequences, not a UI state.

---

## 11. Other open questions

- **Who may umpire a given match** — any `SCORER`, or only one scoped to that event's club, the way
  every other event-scoped operation goes through `ClubAccess.mayOrganize` (#789)?
- **One umpire at a time?** Two devices scoring the same match needs either a lock or last-write-wins,
  and last-write-wins on an event log is a mess.
- **Does the spectator view need history?** Largely settled by §6: the broadcast is a single
  current-score document, so history is not exposed by default. Still worth confirming that no product
  requirement (a point-by-point replay, say) wants it later.
- **Doubles.** Two sides fits, but serving rotates through four players. In scope for the first cut?
- **An abandoned scoring session** must not leave a fixture stuck `IN_PROGRESS` forever. §8a gives this
  a home in the sweep, but two things are still unfixed: how long a stack may sit untouched before it
  counts as abandoned, and what resolving it does — discard the stack and revert the fixture to
  SCHEDULED, or leave it for a human. The retention window for *completed* stacks is open too.

---

## 12. A note on `MatchStatus.IN_PROGRESS`

It already exists in the enum and is referenced nowhere outside generated model files. A live match is
its natural first user.

---

## 13. Suggested sequencing

1. `SCORER` capability, the `chk_capability` migration, and `SCORING_ROLES`.
2. The pure scoring engine and its event model — no transport, no UI, exhaustively unit-tested.
3. Persistence of the event log — including its per-match sequence constraint (§2) and its lifecycle
   (§8a: completed-marking, the audit summary, and the retention sweep) — and finalize → the existing
   `uploadResult`.
4. The umpire view.
5. The spectator view, the Firebase Admin SDK dependency, and the Firestore security rules.

Steps 1–3 carry no Firestore dependency, so the transport work does not block starting. Step 3 is where
the per-match sequence number and its unique constraint belong — the concurrency concern in §2 is the one
multi-instance problem Firestore does not solve for us.
