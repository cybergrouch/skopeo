# Database Migrations

Flyway migrations live in `src/main/resources/db/migration/` as `V<n>__<snake_case_description>.sql`. They run
**on application startup** (`config/DatabaseConfig.kt`, which rethrows on failure), not as a separate deploy step.
Two consequences follow from that and they shape everything below:

1. A failing migration fails the boot. The container never binds `$PORT`, so Cloud Run reports the generic
   "failed to start and listen on the port" and the revision's own logs hold the real cause (#751).
2. Because the revision never passes readiness, **Cloud Run does not shift traffic to it**. The previous
   revision keeps serving and Postgres' transactional DDL leaves the schema untouched. A bad migration costs a
   failed deploy and a manual remediation — not an outage. That is the reason this page is a checklist rather
   than a gate.

Never edit a migration that has shipped; add a new `V<n>` instead. **Flyway's checksum does not protect you
from this** — it is worth being precise about why, because the reassuring reading is wrong. Flyway compares
checksums when it *starts up against a database that already applied the old version*. So an edit is not
rejected at the point of the mistake; it is rejected later, by every environment that already ran it, as a
refusal to boot. The edit does not get prevented — it gets converted into a failed deploy somewhere else.

A [checksum manifest](#the-checksum-manifest) moves that detection to commit time.

## The checksum manifest

`src/main/resources/db/migration-checksums.txt` records Flyway's own checksum for every shipped migration,
and `MigrationChecksumManifestTest` fails when an existing line changes (#854).

| Condition | Result |
|---|---|
| A shipped migration's content changed | **Fail** — "EDITED AFTER SHIPPING", naming the version, with the old and new checksums |
| A migration was added | **Fail** until its line is appended, so a new migration is a deliberate line in the diff |
| A migration disappeared | **Fail** |

**Adding a migration**: run the test, copy the `Full applied set` it prints on failure into the manifest.
That is the only supported way to change the file — hand-editing a number to make the test pass defeats it
entirely, which is why the failure message says so.

**The values are Flyway's own CRC32**, not a hash of the file bytes. That is deliberate: they are the same
numbers a database records, so the manifest is also the reference for whether an environment is stale —

```sql
SELECT version, checksum FROM flyway_schema_history WHERE success ORDER BY installed_rank;
```

Diff that against the manifest and a mismatch names exactly which migration an environment is holding an old
copy of. This is the diagnosis that previously had to be done by hand from a boot failure.

**Why this is a separate guard from everything else on this page.** The rest of this document is about
*backfilling before you tighten* — a migration that is correct in isolation but wrong against existing rows.
The manifest addresses a different failure: a migration that was correct, shipped, and then changed. V44
managed both, which is why it appears twice here.

## Why CI cannot validate a constraint

`src/test/kotlin/org/skopeo/testsupport/PostgresTestDatabase.kt` starts a fresh container and runs
`Flyway…migrate()` against an **empty** database. So:

| Migration shape | Exercised by CI? |
|---|---|
| Adds a table, column, index, comment | **Yes** — the DDL either parses and applies or it doesn't. |
| Backfills data (`UPDATE …`) | Partly — the statement is executed, but against no rows, so its *logic* is unverified. |
| Tightens a constraint — `SET NOT NULL`, `ADD CONSTRAINT … UNIQUE`, `ADD CONSTRAINT … CHECK`, narrowing `ALTER TYPE` | **No.** Validated against zero rows. It passes in CI regardless of what production holds. |

No amount of test coverage changes the third row. A tightening is only safe if the migration itself guarantees
its precondition. "Someone will check the row count before deploying" is not a guarantee — it is the failure
mode described below.

## Checklist for a constraint-tightening migration

- [ ] **Does the migration make its own precondition true?** A `SET NOT NULL` is preceded by an `UPDATE … WHERE
      col IS NULL`; a new `UNIQUE` is preceded by whatever de-duplicates; a narrowed `CHECK` is preceded by the
      `UPDATE` that maps the retired values. Not a comment asking a human to verify. Not a runbook step. Not a
      separate script.
- [ ] **Is the backfill unconditional?** Prefer a catch-all terminal statement (`UPDATE t SET col = <default>
      WHERE col IS NULL`) after any clever derivation, so the precondition holds even when the derivation finds
      nothing. `V40__event_format.sql` does exactly this.
- [ ] **Is the backfill in the same file as the constraint?** Flyway wraps each migration in a single
      transaction on Postgres, so backfill-then-constrain is atomic by default: either the data is corrected and
      the constraint lands, or neither happens and the previous revision keeps serving. Splitting them across two
      migrations gives up that atomicity for nothing. (The exception is a statement Postgres cannot run inside a
      transaction — `CREATE INDEX CONCURRENTLY`, `ALTER TYPE … ADD VALUE` on older versions. Those need their own
      migration and their own reasoning.)
- [ ] **Does soft-delete matter?** `NOT NULL` and `UNIQUE` do not know that `is_active = false` means "deleted".
      Count and backfill unfiltered.
- [ ] **If the backfill value cannot be derived, has the product question been settled?** "Which club owns this
      orphaned event?" is a decision to make before writing the migration, not a reason to skip the backfill.
- [ ] **If it genuinely cannot be automated, is it `NOT VALID`?** `ALTER TABLE t ADD CONSTRAINT c CHECK (…) NOT
      VALID` enforces the rule on every new write without validating existing rows, so the deploy cannot fail on
      legacy data. Clean the data, then land a follow-up `ALTER TABLE t VALIDATE CONSTRAINT c`. Two migrations,
      zero failed deploys.
- [ ] **Does the tightening contradict an existing FK action?** A column that is now `NOT NULL` but whose FK is
      `ON DELETE SET NULL` turns the parent delete into a runtime `23502`. Either the FK action needs to change
      (`RESTRICT`/`CASCADE`) or the parent must only ever be soft-deleted.
- [ ] **Is the column also nullable in Exposed?** Tightening the DB without tightening the table object /
      domain model leaves the invariant unenforced at every call site — and vice versa.

## Worked example: V44, the one that failed

`V44__events_require_club.sql` (#794) made `events.club_id` required:

```sql
ALTER TABLE events
    ALTER COLUMN club_id SET NOT NULL;
```

Its header *asserted* the precondition instead of enforcing it:

> Confirm zero before deploying, unfiltered by is_active:
> `SELECT count(*) FROM events WHERE club_id IS NULL;   -- must be 0`
> The clubless events that predated #794 have been re-filed under clubs, so this is expected to be 0.

It was not 0. The v2.0.8 deploy failed:

```
ERROR: column "club_id" of relation "events" contains null values   (SQLSTATE 23502)
Location : db/migration/V44__events_require_club.sql
```

The header was right about the risk, right about the query, and right to warn about soft-deleted rows — and none
of that helped, because the check lived in a comment. Remediation took a hand-written backfill script (#798),
**a public IP temporarily enabled on the production database** to reach it, and a redeploy. Blast radius was
nil, but widening a production database's attack surface is a real cost for a preventable mistake.

What it should have been — one file, one transaction:

```sql
UPDATE events SET club_id = <resolved> WHERE club_id IS NULL;   -- make it true
ALTER TABLE events ALTER COLUMN club_id SET NOT NULL;           -- then assert it
```

V44 was originally written as `CHECK (…) NOT VALID` before being tightened. The hedge existed for a reason.

The counter-example was already in the tree, one migration family earlier and on the same table:
`V40__event_format.sql` added a required `events.format` by deriving each event's format from its own matches,
then falling back with an unconditional `UPDATE events SET format = 'SINGLES' WHERE format IS NULL`, and only
then running `SET NOT NULL`. That migration cannot fail on legacy data no matter what the table holds.

## Audit of existing migrations (as of V44, #799)

Every tightening in `V1`–`V44` was reviewed. **V44 is the only one that tightened a constraint without making
its own precondition true.** The rest are safe, and for one of three distinct reasons — worth distinguishing,
because only the first is a pattern to copy:

| Migration | Tightening | Why it holds |
|---|---|---|
| `V10` | `clubs.public_code` `SET NOT NULL` + `CREATE UNIQUE INDEX` | **Backfilled.** A `DO` block generates a 6-char code per `public_code IS NULL` row, retrying until unique — so both the NOT NULL and the unique index are guaranteed, not hoped for. |
| `V40` | `events.format` `SET NOT NULL` + `chk_event_format` | **Backfilled**, with an unconditional catch-all. The model case; see above. |
| `V21` | `chk_team1_handicap` / `chk_team2_handicap` on the pre-existing `matches` | **Vacuous by construction.** Both columns are added nullable in the same migration, so every existing row is NULL and the `IS NULL OR …` branch is satisfied. |
| `V39` | `ck_seedings_exactly_one_source`, `fk_seedings_event`, `uq_seedings_event` on the pre-existing `seedings` | **Vacuous by construction.** `event_id` is added in the same migration (all NULL → FK and unique index have nothing to check; Postgres treats NULLs as distinct), and the XOR check holds because `list_id` was `NOT NULL` in `V1` — derivable from the schema, not an assumption about the data. The `list_id DROP NOT NULL` in the same file is a loosening, always safe. |
| `V1`, `V5`, `V12`, `V13`, `V16`, `V23`, `V25`, `V28`, `V33`, `V41` | `UNIQUE` / `CHECK` in a `CREATE TABLE`, or a unique index on one | **Table is new in the same migration** — zero rows to validate. |
| `V16` | `chk_capability` drop + re-add | **Widening**, not tightening: it *adds* `POINTS_MANAGER` to the allowed set. Every row that passed the old check passes the new one. |
| `V14` | `standings_entries.ordering_value` → `NUMERIC(12,4)` | **Widening** from `NUMERIC(6,4)`. Lossless. |
| `V29` | `matches.placement_bracket` / `match_type` → `VARCHAR(32)` | **Widening** from `VARCHAR(20)`. Lossless. |
| `V2`, `V9`, `V14`, `V15`, `V23`, `V26`, `V30`, `V32`, `V37`, `V42`, `V43` | `ADD COLUMN … NOT NULL DEFAULT …` | **Self-backfilling** — Postgres fills existing rows from the default. Safe, and not the same operation as `SET NOT NULL` on an existing column. |
| `V44` | `events.club_id` `SET NOT NULL` | ❌ **Assumed.** The defect this page exists for. Data remediated by #798; the migration itself still carries no backfill. |

Two adjacent observations from the sweep, neither a landmine:

- **`events.club_id` was `NOT NULL` (V44) while its FK was still `ON DELETE SET NULL` (V6)** — a hard
  `DELETE FROM clubs` would have failed with `23502` on `events` instead of orphaning them. Unreachable through
  the app (`ClubRepository` soft-deletes via `is_active = false` and never issues a `DELETE`), but the FK action
  and the column constraint disagreed. **Fixed in `V45__events_club_fk_restrict.sql` (#800)**: the FK is now
  `ON DELETE RESTRICT`, so the delete is refused on `clubs` and names the dependent events. RESTRICT rather than
  CASCADE, because cascading would silently destroy the match and rating history hanging off those events.
- **`V38` retired the `LEAGUE` event type and the `LEAGUE_PLAY`/`LEAGUE_PLAYOFFS` match types by `UPDATE`, but
  narrowed no `CHECK`** (neither column has one). The data is clean; the database still accepts the retired
  values. That is the safe direction to be wrong in, but it is the exact shape that needs a backfill-first
  migration if the constraint is ever added.
