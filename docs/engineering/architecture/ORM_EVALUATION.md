# ORM Evaluation: Exposed vs. jOOQ vs. Jimmer

**Status:** Accepted — stay on **Exposed** for now; revisit **jOOQ** if database-first
schema-to-code synchronization becomes a priority.

**Date:** 2026-06-24

This is an evaluation/decision record, not a migration plan. It captures the trade-offs so a
future change is an informed one.

---

## Context

The persistence stack is **Ktor + Kotlin + PostgreSQL**, with:

- **Flyway** owning all DDL (a single consolidated `V1__create_initial_schema.sql`).
- **Exposed** (JetBrains, the **DSL** flavor) as the data-access layer. The `Table` objects
  (`UserTables.kt`, `RatingTables.kt`, `MatchTables.kt`) are a **hand-written, thin mapping of
  "only what the repository touches."**

### The observation that prompted this

The Kotlin code "knows" database details — the Exposed `Table` objects mirror the DDL (column
names, types, precision). A change to a *used* column means editing **both** the Flyway migration
and the Exposed table. The hope was that an ORM would abstract the database so schema changes are
transparent to Kotlin.

### Clearing up the expectation

No ORM makes schema changes transparent. An ORM/data-mapper maps **objects ↔ tables**; the
mapping always lives somewhere. What ORMs abstract is the **SQL dialect** (Postgres vs. MySQL vs.
H2), **not the schema shape**. If a column you read changes, the mapping changes — in any tool.
"Swap the database and the code doesn't notice" is a leaky abstraction that breaks the moment you
use real database features (which we do).

The friction we actually have is **duplication** between Flyway DDL and the Exposed tables — not
a leak. The relevant question is therefore: *which tool best reduces that duplication, given a
Postgres-committed, feature-rich schema?*

---

## Key architectural fact: Flyway owns DDL, the ORM only reads/writes data

This separation is what makes an ORM change lower-risk than it appears. The "scary"
Postgres-specific features are **not declared in the ORM** — Flyway owns them and the query
planner uses them automatically:

| Postgres feature | Defined in | Touched by Kotlin today? | Affected by an ORM swap? |
|---|---|---|---|
| GIN trigram index (`gin_trgm_ops`) | Flyway DDL | No (planner uses it automatically) | ❌ No |
| Partial indexes (`WHERE is_active`, …) | Flyway DDL | No | ❌ No — planner uses them; *unique* partial indexes enforce constraints the app relies on |
| CHECK constraints | Flyway DDL | No | ❌ No — DB enforces; ORM surfaces the violation as an exception |
| JSONB (`matches.metadata`) | Flyway DDL | **No — not mapped in the repository** | ❌ No (nothing to port today) |
| Append-only writes | Repository logic (`insert` + `update` flipping `is_active`) | Yes | ⚠️ Rewritten like all repo code, but it's ordinary DML |
| **pg_trgm `similarity()`** | **Repository query** (`UserRepository.kt`) | **Yes** | ⚠️ **The one real Postgres-specific thing to re-express** |

### The single Postgres-specific query

The fuzzy name search is the only place a Postgres-specific feature reaches the Kotlin layer,
via Exposed's escape hatch:

```kotlin
// UserRepository.kt
val proximity = CustomFunction("SIMILARITY", FloatColumnType(), nameLower, stringParam(normalized))
// ... (nameLower like "%$normalized%") or (proximity greaterEq SIMILARITY_THRESHOLD)
```

Subtlety for any port: the GIN index is on `lower(value) gin_trgm_ops`, so the query must keep
the **exact `lower(value)` expression** for the functional index to be used.

---

## Options

### Exposed (current)

Kotlin type-safe DSL; thin hand-written mapping; full control of Postgres DDL via Flyway.

- **Pros:** already in place; minimal magic; Postgres features expressible via `CustomFunction`;
  no codegen step.
- **Cons:** schema mapping is duplicated by hand (the original friction).

### jOOQ

**Database-first codegen** — generates type-safe Kotlin/Java from the *actual (migrated)* schema.

- **Pros:** directly addresses the duplication — Flyway migrates → jOOQ regenerates → the
  **compiler flags any code referencing changed/removed columns**. SQL-first, so pg_trgm
  `similarity()`, the `%` operator, `ILIKE`, JSONB operators are all first-class. Excellent
  PostgreSQL support; the Open Source Edition covers PostgreSQL for free.
- **Cons:** adds a codegen build step (generate after migrate); a real migration off Exposed.

### Jimmer

Entity-first ORM with **immutable entities** (KSP codegen), an **object fetcher** (GraphQL-style
nested fetching, kills N+1), a **DTO language**, and a tree-aware **save command**.

- **Pros:** best-in-class for read-heavy APIs that return rich nested DTOs; immutable model fits
  Kotlin; strong N+1 handling.
- **Cons:** **entity-first, so it does *not* solve the DB-first transparency goal** (entities are
  the source of truth — same philosophy as JPA/Exposed-DAO). pg_trgm needs a native-SQL escape
  hatch. Its flagship **save command** (upsert/diff of object trees) is at odds with our explicit
  append-only "insert new row + flip `is_active`" pattern, so we'd use its lower-level statements
  and not benefit from the headline feature. Newer/smaller ecosystem; Spring Boot is the blessed
  integration (Ktor usage is DIY via `KSqlClient`); steeper learning curve.

### Briefly considered

- **Hibernate / JPA** — most abstraction, but heavyweight, hides SQL (N+1 risk), and *fights*
  Postgres-specific features. Poor fit for a Postgres-committed, feature-rich schema.
- **Ktorm / Komapper** — Kotlin DSL ORMs similar to Exposed; a lateral move not worth a rewrite
  (Komapper adds codegen + coroutines/R2DBC).

---

## Comparison

| Option | Philosophy | Solves "DB → code"? | pg_trgm query | Append-only fit | Maturity / Ktor fit | Best when |
|---|---|---|---|---|---|---|
| **Exposed** (current) | Kotlin DSL, thin mapping | ❌ | `CustomFunction` escape hatch | ✅ plain DML | Mature; Ktor-friendly | Full control of Postgres DDL, minimal magic |
| **jOOQ** | DB-first codegen | ✅ | ✅ first-class (SQL-first) | ✅ plain SQL | Mature; framework-agnostic | Schema as source of truth, compiler-checked |
| **Jimmer** | Entity-first + fetcher/DTO codegen | ❌ | ⚠️ native-SQL escape hatch | ⚠️ use low-level API (not `save`) | Newer; Spring-favored (Ktor DIY) | Read-heavy APIs returning rich nested DTOs |
| **Hibernate/JPA** | Full entity ORM | ❌ | ⚠️ native query | ⚠️ entity lifecycle friction | Mature; heavyweight | Max abstraction (not our need) |

---

## Decision

**Stay on Exposed for now.** A DB-backed, thin Exposed mapping with Flyway-owned DDL is a sound
fit for the current straightforward data layer. Each request already loads the entities it needs,
so there is no performance pressure, and the only friction (hand-maintained mapping duplication)
does not yet justify a migration's cost and risk.

**If/when database-first schema-to-code synchronization becomes a priority, choose jOOQ.** It is
the only evaluated option whose model matches that goal, it pairs naturally with our existing
Flyway-as-source-of-truth setup, and it handles our one Postgres-specific query (and JSONB, if we
ever map `metadata`) first-class.

**Jimmer is not recommended for this project.** It does not address the stated concern
(entity-first, not DB-first), its headline features (object fetcher, DTO language, tree saves)
are largely wasted on our flat CRUD + hand-maintained DTOs + append-only writes, and its maturity
and Ktor-DIY integration add risk without a matching payoff.

---

## Consequences

- No immediate change; the duplication between Flyway DDL and Exposed tables remains, managed by
  keeping the Exposed mapping deliberately thin.
- A future jOOQ migration is **lower-risk than the feature list suggests**: Flyway keeps owning
  all DDL (GIN/partial indexes, CHECK, JSONB column), so the rewrite is confined to the
  repository query/DML layer, within which exactly one Postgres-specific predicate (the trgm
  search) needs re-expressing.
- Revisit triggers:
  - **DB-first transparency wanted** (schema changes should fail the build until code catches up)
    → adopt **jOOQ** (regenerate-after-migrate in the build).
  - **Authorization/queries become relational or DTO-graph heavy** → re-weigh Jimmer for the
    read side specifically.

---

## References

| Concern | File |
|---|---|
| DDL source of truth | `src/main/resources/db/migration/V1__create_initial_schema.sql` |
| Exposed table mappings | `src/main/kotlin/org/skopeo/repository/UserTables.kt`, `RatingTables.kt`, `MatchTables.kt` |
| The one Postgres-specific query (pg_trgm) | `src/main/kotlin/org/skopeo/repository/UserRepository.kt` |
| DB wiring (HikariCP + Flyway + Exposed) | `src/main/kotlin/org/skopeo/config/DatabaseConfig.kt` |
