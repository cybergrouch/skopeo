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

#### This does not mean `ScoreState` should hold the stack

It is a natural inference — *undo needs history, so the state must carry history* — and it is worth
saying plainly why the answer is no. **The stack is held; it is just not held inside `ScoreState`.**

```
log: List<LoggedEvent>          ← the stack lives HERE (Postgres, §8a)
  │
  ├── effective(log)            ← the ONLY layer that knows about UNDO
  │        │
  │        └── fold with apply(state, event)   ← sees real scoring events only
  │
  └── ScoreState                ← the current score. No history, by design.
```

`apply` does not need history *because it never sees an undo*. Undo is a property of the log, and it is
resolved where the log is.

Putting the stack inside the state instead would cost three things:

- `apply` becomes history-aware, which is exactly the simplicity that made it exhaustively testable;
- `ScoreState` stops being usable as the spectator projection (§6), because the projection would then
  carry the whole log — the opposite of the small public surface that section argues for;
- the log ends up represented twice, in storage and in the state folded from it, which is two things to
  keep in step.

The alternative design — state as `(score, history)` with `apply` handling UNDO — is legitimate in the
abstract and is how some event-sourced systems do it. It is the wrong trade *here* specifically because
`ScoreState` doubles as the thing broadcast to spectators.

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

### Shipped — step 2 of §13

`ScoreEngine` (`domain/service/livematch/`) and its event model (`domain/model/LiveScoreDomain.kt`),
exactly as described above: `apply` holds the rules, `effective` is the only layer that knows undo
exists, and `replay` is a fold over `apply` rather than a second traversal. No I/O, no transport, no UI.

Decisions taken while building it, none of which the section above had settled:

- **Where the permissive boundary actually falls.** `apply` advances points and closes a **game** by the
  ordinary deuce/advantage rule; the **set**, the **tiebreak** and the **match** are declared. Six games
  does not end a set — the umpire does. That follows from #911 requiring a set be endable below six and
  the UI not presume a tiebreak target: auto-closing at six would fight the umpire in any short-set or
  pro-set format. It does mean the engine will accept a set banked at 2-1 and a tiebreak sitting at 7-0,
  which is pinned by test rather than left to be discovered.
- **Undo resolution is recursive, and resolved in one pass.** A marker is in force unless it has itself
  been cancelled, which is what makes undo-of-undo a redo. Walking sequences in *descending* order
  settles it without fixpoint iteration, because a marker always has a higher sequence than its target.
  A marker aimed at a sequence that does not exist, or at one already cancelled, is inert.
- **Undo is not last-in-first-out.** A marker names its target, so an earlier action can be struck out
  with everything after it still counting.
- **Doubles is in the first cut.** The only thing that differs is the server, so `ServerAssigned` names a
  **player** rather than a side. Nothing auto-rotates: whose turn it is is a format rule, and the umpire
  is the authority. Points, games and sets are per *side* and need no doubles-specific handling.
- **A finished match ignores further scoring**, with `ServerAssigned` carved out as a record correction.
  Safe rather than a trap, because the concluding event is undoable like any other — undoing a
  mis-tapped retirement puts the match back in play.

Left for step 3, deliberately: the log is a `List<LoggedAction>` handed in by the caller. Nothing here
persists it, allocates a sequence number, or knows about a match id.

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

**Decided (revising the above): the log is kept until the match is finalized. There is no staleness
timeout, and the sweep loses one of its two jobs.**

The "resolve stale in-progress stacks" half cannot be made to work. A match suspended for weather may
resume **days** later, on a court the host cannot book yet — so any timeout would have to choose between
sweeping away a match that is merely waiting and leaving a genuinely abandoned one forever. There is no
number that gets both right, because the distinguishing fact is not elapsed time; it is whether anyone
intends to come back.

So the umpire says so instead. `PAUSED` / `RESUMED` make a suspension explicit, and a paused match is
paused rather than stale. That also answers §11's abandoned-session question without a sweep: a fixture
sitting in `IN_PROGRESS` for a fortnight is not a bug if it is paused.

`MATCH_STARTED` joins them, separate from the first point on purpose: the interval between the umpire
opening the app and the players actually starting is exactly what would corrupt a duration figure. Every
row already carries `recorded_at`, so playing time is derivable as *(last event − `MATCH_STARTED`)* minus
the pause intervals, and per-game and per-point pacing falls out of the same timestamps.

**Still open:** the retention window for *completed* stacks — how long a finalized match's log is kept
before pruning. That one is a genuine trade-off (long enough to inspect a disputed result, short enough
that the table does not grow without bound) and nothing depends on it yet.

### Shipped — step 3a of §13, the persistence half

`V55` adds three tables, and the third one exists because of a hole this section had.

| Table | Job |
|---|---|
| `live_match_events` | The append-only log. Never updated, never deleted; undo appends a marker. |
| `live_match_scorers` | The **soft claim** — who is keying a match in right now. |
| `match_umpires` | Durable credit for who scored the match. |

**`match_umpires` is the fix for a hole in "disposable".** Every log row carries `recorded_by`, so while
the log exists it knows who did what. But this section makes the log disposable once the match is
recorded — which means attribution would survive right up until the moment the match became historical,
and a match page rendered a year later could not say who umpired it. So the credit is folded out of the
log at finalize and written somewhere that outlives it. A row per (match, umpire), because a takeover is
expected and a match scored by two people should credit both; the counts and timestamps are what separate
"umpired the match" from "tapped one point during a handover".

**Concurrency is settled by `uq_live_match_events_sequence`, and by nothing else.** A writer computes the
next sequence from the log it read and inserts; the loser of a race gets a unique violation and retries
against the log that actually won. The repository catches the violation rather than pre-checking with a
`SELECT`, because check-then-insert has a window between the two — which is the entire bug. Tested by
racing eight real threads at one sequence and asserting exactly one survives, rather than by asserting
that some Kotlin looks careful.

**Who may umpire — decided: any `SCORER`, on any match.** No per-event `ClubAccess.mayOrganize` check, so
this is the one event-scoped operation that does not go through the #789 gate. That is deliberate: an
umpire pool moves between clubs, and requiring a roving umpire to be made a club owner would be worse.
The cost to hold in mind is that `SCORING_ROLES` composes `MATCH_MANAGEMENT_ROLES`, so **every HOST and
CLUB_OWNER in the system can write to any live match**, not only their own club's. If that proves too
wide, the narrowing is to gate live scoring on `{SCORER, ADMINISTRATOR}` rather than to re-scope per club.

**One umpire at a time — decided: a soft claim with takeover.** `live_match_scorers` records who holds a
match so a second umpire takes over deliberately rather than by accident. It is *not* a lock: the unique
constraint already makes interleaved and lost writes impossible, so this is about not confusing two
people. A hard lock was rejected because a courtside phone that dies must not strand the fixture behind a
timeout nobody chose well.

### Shipped — step 3b of §13, the service half

`LiveMatchService`: claim/release, record, undo, and read. Every write is `read log → append one row →
re-read → replay`, with nothing cached between requests — two Cloud Run instances would otherwise
diverge.

- **Append retries on a sequence collision.** The loser of a race must not simply take the next number:
  the event that won may have changed what the umpire's action means, so it re-reads and recomputes.
  Three attempts, then a `Conflict` — losing three in a row is not ordinary contention.
- **Undo targets the last *surviving* action**, obtained from `ScoreEngine.surviving(log)` rather than by
  scanning for the last non-marker row. Only the engine knows a redo can bring an earlier action back,
  and matching on the event *value* would be wrong outright — two identical `PointWon(TEAM1)` rows are
  equal, so a cancelled one is indistinguishable from a surviving one without its sequence.
- **Undo with nothing to undo writes nothing and is not an error**, which is what a courtside double-tap
  should do.
- **`MatchStatus.IN_PROGRESS` finally has a writer.** Claiming a `SCHEDULED` fixture moves it there;
  releasing the claim does *not* move it back, because the match is still being played.

Not here, and deliberately: **finalize**. Two things block it and neither is small. `SetScoreRequest`
enforces `MIN_GAMES_TO_WIN = 4`, so a set the engine will happily bank at 3-2 — the "end a set early"
requirement — cannot be submitted through `uploadResult` at all. And retirement/default still have no
representation (§10). Both belong with the retirement work rather than being half-solved here.

### Shipped — step 3c of §13, the HTTP surface

| | |
|---|---|
| `GET /api/v1/matches/{id}/live` | the scoreboard |
| `POST …/live/events` | one umpire action |
| `POST …/live/undo` | take the last one back |
| `POST` / `DELETE …/live/claim` | take or give up the scoring |

Three decisions the API forced:

- **Points are rendered on the server**, not returned as raw counts. `0`/`15`/`30`/`40`/`AD`, or a plain
  ordinal in a tiebreak. Handing out raw counts would invite every client to grow its own implementation
  of deuce, and the umpire view and the spectator view would eventually disagree.
- **Undo is its own endpoint with an empty body, because the server picks the target.** Only the engine
  knows which action is still in force — an undo of an undo is a redo — so a client naming a sequence
  would be duplicating that reasoning and would drift from it. `UNDONE` is therefore not a postable kind.
- **Parsing lives in the service, not the route.** `routes` may not depend on `model` (enforced with no
  exception), so a route cannot name a `ScoreEvent`. `ScoreEventParser` does the translation, and the
  service's public surface takes and returns DTOs — which meant reworking the model-returning signatures
  step 3b shipped.

An unknown kind, an unknown side, a missing payload the kind requires, or a malformed player id are all
400s rather than being ignored. Silently dropping a field the caller meant is how a scoreboard ends up
quietly wrong.

The read is authenticated. The *public* spectator scoreboard is a Firestore projection written by the
server (step 5), not this endpoint.

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

**Shipped — step 1 of §13.** `Capability.SCORER`, `SCORING_ROLES = MATCH_MANAGEMENT_ROLES + SCORER`,
and `V54__scorer_capability.sql`. The web mirror is `canScore` in `auth/capabilities.ts`, and `SCORER`
joined `GRANTABLE` in `ManagePlayerSection` — without that last one the role exists server-side with no
way to hand it to anybody.

The "easy to miss" migration is now a test rather than a warning: `CapabilityServiceTest` grants **every**
`Capability.entries` value against a real database, so the next capability added without widening the
CHECK fails on the day it is added. That guard is the durable part of this step — the enum value itself
was trivial.

---

## 10. Decision — a retirement is a loss on the record and the real score for ratings

**Decided.** The two answers differ on purpose.

- **On the record**, the match is a **win for the opponent** of the retiring player.
- **On the scoreline**, the actual score at the moment of retirement is kept, annotated `1-5 (ret)`.
- **For ratings**, dominance is computed from that **actual score**, not from who was awarded the match.

### Rationale

Retirement decides the *consequence* of the match. It says nothing about the tennis played up to that
point, and the rating is a measure of performance. A player leading 5–1 who pulls up injured
demonstrably outplayed their opponent for that set; awarding the opponent the match should not also
hand them the rating movement of a player who was being beaten.

So in that example the retiring player is treated as the **winner for dominance purposes**, even though
they lost the match.

### This needs no calculator change

The rating maths reads **`set.winnerTeamId` only**. The match-level `winnerTeamId` is passed into
`RankingCalculationRequest` but is never read by `PerformanceBasedRankingCalculatorImpl` — the single
use is per set:

```kotlin
val isWinner = set.winnerTeamId == teamId    // per set, not per match
```

So the split falls out of recording the data honestly:

| | Value | Read by |
|---|---|---|
| `matches.winner_team_id` | the **opponent** | the record, the public page, ranking-point awards |
| each set's `winner_team_id` | whoever actually led that set, including the partial one | the rating calculation |

Record a retirement at 1–5 as a partial set whose winner is the player with 5, and the dominance
credited is already correct. Nothing in the calculator has to learn what a retirement is.

Put another way: **the existing algorithm already decides the rating winner — the player with the higher
score — and that stays.** The new concept is a *match* winner that is designated rather than computed.
Today those are the same value because the match winner is derived from the sets; a retirement is the
first case where they legitimately differ.

### The consequence to state loudly

**A retiring player can gain rating from a match they lost.** That is the intent, and it will look like
a bug to anyone who has not read this section — so it belongs in the UI copy and in whatever explains a
rating change (#862's derivation view), not just here.

### What still has to be built

1. **Representation.** Neither retirement nor default exists today: `MatchStatus` is
   `SCHEDULED | IN_PROGRESS | COMPLETED | CANCELLED`, and nothing in the match model mentions either. It
   needs a completion reason and *which* player retired or defaulted.
2. **The match winner becomes designatable; set winners stay derived.** This is the whole change, and
   it is smaller than it sounds. Today `deriveOutcome` derives *both*:

   ```kotlin
   val winner = setWinner(...)                                   // per set, from games — KEEP
   ensure(condition = team1Sets != team2Sets) { "sets are tied" } // only meaningful when deriving
   resolved to if (team1Sets > team2Sets) team1Id else team2Id    // match winner — make designatable
   ```

   The per-set derivation is the rating algorithm and is untouched: higher games still wins the set, so
   dominance keeps working exactly as it does now. What is added is an **optional designated match
   winner** on the result request, used when a retirement or default is recorded. When it is supplied,
   the sets-tied guard no longer applies — a retirement can legitimately stand at one set all, or at a
   single unfinished set.
3. **Display.** `1-5 (ret)` on the match page, the event page and anywhere a scoreline is rendered.
4. **Default — decided: a defaulted match is not rated.** A default is usually a no-show, so there is
   commonly no scoreline at all to compute dominance from, and rating one would mean inventing a
   performance nobody gave. This is deliberately a *different* answer from retirement, which **is** rated
   on the real score: a retirement has tennis behind it and a default does not. The match still counts on
   the record — the opponent wins it — and ranking points follow the record, per the section below.

**Prerequisite done: the games floor no longer blocks an abandoned set.** `SetScoreRequest` enforced
`MIN_GAMES_TO_WIN = 4`, so a set standing at 3-2 — what an early-ended set or an early retirement
actually produces — was rejected at the DTO boundary before any service saw it. The check now lives on
`MatchResultRequest` and is lifted when a match winner is designated, the same carve-out #917 made for
the sets-tied guard and for the same reason: a designation is the result saying it did not end normally.
Without one the floor still applies, so nothing previously rejected is now accepted.

(Note 1-5 was never blocked — the winner has five games. The floor only bites below four.)

### Where each winner should live

**Decided.** The models should carry only the **designated** match winner. The **derived** winner — who
took a set — should be computed where it is used, next to the rating calculation, rather than stored.

`setWinner` is already a pure function of data the set row holds:

```kotlin
team1Games > team2Games            -> team1
team2Games > team1Games            -> team2
else, tiebreak points decide       -> whoever has more
otherwise                          -> no clear winner
```

Nothing else feeds it. So `match_sets.winner_team_id` is a **stored derivation** — a second source of
truth for something its own row already determines, and nothing stops the two disagreeing.

Checked against production: **959 sets, zero null winners, zero rows where the stored winner disagrees
with the games**, and zero sets with tied games (so the tiebreak branch, while reachable through the
API, has never been needed by real data). The column is fully reconstructible today, which is exactly
when it is safe to stop storing it.

The resulting split is clean:

| | Nature | Where |
|---|---|---|
| **Match winner** | *designated* — declared at record time, and the only one a retirement changes | `matches.winner_team_id`, on the model |
| **Set winner** | *derived* — a pure function of that set's games and tiebreak | computed for the rating calculation, not stored |

This also removes the thing that makes retirement feel awkward. Once the set winner is derived rather
than persisted, recording a partial set at 1–5 needs no decision about "who won" an unfinished set — the
rating side derives the leader, and the match side takes the designated winner. The two stop competing
for the same column.

**Done — #917, shipped in three steps.** #918 moved the derivation into the entity→domain mapper so
nothing read the stored value; #922 made the match winner designatable, defaulting to the derived one
and lifting the sets-tied guard only when a designation is supplied; #923 dropped
`match_sets.winner_team_id` and `match_set_tiebreaks.winner_team_id` in `V53`.

So the split described above is live. `matches.winner_team_id` is the designated match winner and the
only one a retirement changes; the set winner is derived from that set's games and tiebreak, wherever
it is needed.

Two consequences for the retirement work here:

- **Recording a partial set no longer needs a decision about who "won" it** — the rating side derives
  the leader, the match side takes the designated winner, and they no longer compete for one column.
- **An undecidable set now throws rather than falling back to a stored value.** That is unreachable for
  matches as they can be recorded today, and retirement is the thing that changes it — see the
  level-partial-set question in §11.

### Points and awards follow the record, not the rating

`EventFinalizeAwarder` reads `match.winnerTeamId`, so ranking points go to the opponent. That is
consistent with "retirement determines the post-match consequences" and needs no change — but it is the
second place where the two notions of *winner* diverge, so it is worth a test that pins both at once.

---

## 11. Other open questions

- **Who may umpire a given match** — any `SCORER`, or only one scoped to that event's club, the way
  every other event-scoped operation goes through `ClubAccess.mayOrganize` (#789)?
- **One umpire at a time?** Two devices scoring the same match needs either a lock or last-write-wins,
  and last-write-wins on an event log is a mess.
- **Does the spectator view need history?** Largely settled by §6: the broadcast is a single
  current-score document, so history is not exposed by default. Still worth confirming that no product
  requirement (a point-by-point replay, say) wants it later.
- **A retirement can produce a set with no winner.** A player pulling up at 3–3, or at 0–0 in a new
  set, leaves a level partial set. `MatchService.setWinner` refuses to record one today, and since #917
  there is no stored winner to fall back on, so the derivation throws. Three options, materially
  different in cost: do not record the level set at all; make the set winner nullable (which ripples
  into `PerformanceBasedRankingCalculatorImpl`, which assumes every set has one); or record it, derive
  nothing, and have the rating skip it. The third looks right — a level set demonstrates nothing about
  who was outplaying whom, so contributing no dominance is the correct semantics rather than a
  workaround — but it wants deciding **before** the retirement work, not during it.
- **Doubles.** Two sides fits, but serving rotates through four players. In scope for the first cut?
- ~~**An abandoned scoring session** must not leave a fixture stuck `IN_PROGRESS` forever.~~
  **Answered by `PAUSED`/`RESUMED` (§8a).** A suspension is now stated by the umpire rather than guessed
  from elapsed time, so a fixture sitting in `IN_PROGRESS` is not evidence of abandonment. The retention
  window for *completed* stacks remains open.

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
