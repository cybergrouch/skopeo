# Layered package architecture

The backend is organised into layers by top-level package under `org.skopeo`. Their dependency
directions are **enforced** by `src/test/.../LayeredArchitectureTest.kt` (ArchUnit) so they don't
erode silently. The build fails if a rule is broken.

## Layers and the rules that hold

```
routes        ──► { service, dto, common }                                    (never mapper, never model)
service       ──► { repository, mapper.dto, mapper.entity, dto, model, persistence, common }
mapper.dto    ──► { dto, model }                        (dto ↔ model translation)
mapper.entity ──► { persistence, model }                (entity ↔ model translation, #633)
repository    ──► { persistence, model, common } ──► DB (pure data access; returns persistence entities)
persistence   ──► common                                (#633 raw as-stored row entities — a leaf)
model         ──► common                                (e.g. the Capability enum; model reaches nothing else)
common        ──► ∅                                      (common: shared value types — a pure cross-cutting leaf)
```

> **Rollout note (#633 full separation).** The two mapper packages and the rules below are in place; the
> repository *return-type* flip (domain model → `persistence` entity) rolls out aggregate-by-aggregate.
> Until an aggregate is flipped its repository still returns a domain `model` via an internal conversion.

`common` (`org.skopeo.common.{error,security,contract}` — each a distinct sub-package under one parent) is
the sanctioned cross-cutting foundation: dependency-free value types any layer may use, itself depending
on nothing above — not even `model`.

Enforced invariants (each is true in the codebase today):

- **`repository`** is pure data-access: it never depends on `routes`, `service`, `dto`, or `mapper`; it
  maps DB rows to raw **`persistence`** entities and returns them (it MAY depend on `persistence`, `model`
  — for write-command/enum inputs — and `common`).
- **`persistence`** (`org.skopeo.persistence`) is the **raw entity / data-model** leaf (#633): dumb
  as-stored `<X>Entity` row types the repository returns. Like `model`/`common` it is a strict leaf —
  it depends only on `common`, and **nothing flows back into it** (`model` and `mapper` may not depend on
  `persistence`). The `entity → domain` conversion lives in `mapper.entity`.
- **`model`** is the domain — it never depends on `routes`/`service`/`repository`/`dto`/`mapper`/
  `persistence`; it MAY depend on the neutral `common` foundation (e.g. the `Capability` enum). Generic
  numeric helpers live in `model` (`BigDecimalUtils.kt`).
- **`common`** (`org.skopeo.common.{error,security,contract}`) is the sanctioned **cross-cutting
  foundation** of dependency-free shared value types: `ServiceError` (#115); the auth principals
  `ClientPrincipal`/`ClientAuthResult` + the `Capability` authorization enum (#597/#106); and the
  serializable points-config contracts (`OpenPlayPointsConfig`/`TournamentPointsConfig`, #552). **Any**
  layer — including `model` (which uses `Capability`) — may depend on it; `common` depends on nothing
  above it, not even `model`. It is the one package referenced from every layer.
- **`dto`** is a **pure serializable boundary record**: it never depends on `routes`, `repository`,
  `service`, or `model` — with one sanctioned exception (below).
- **`mapper`** is two sibling sub-packages, both consumed only by `service` and both forbidden from
  `routes`/`repository`/`service`:
  - **`mapper.dto`** owns the dto↔model translation (`toResponse`/`toCommand`); depends on `dto` + `model`
    only — **never `persistence`** (a dedicated rule enforces this).
  - **`mapper.entity`** owns the entity↔model translation (`<X>Entity.toDomain(...)`, #633); depends on
    `persistence` + `model` only — **never `dto`** (a dedicated rule enforces this).
- **`service`** never depends on the transport layer (`routes`); it is the orchestrator — it calls a
  `repository`, converts the returned entity via `mapper.entity`, runs business logic on the domain
  `model`, then converts to a response DTO via `mapper.dto` (one-way, so the graph stays acyclic). It
  therefore MAY depend on `persistence` (the entities it receives). Services return response DTOs and
  accept request DTOs.
- **`routes`** never depend on `mapper` **or `model`**: dto↔model translation is hidden behind the service
  (a route calls `service.*` and responds with the DTO it receives), and routes pass **raw** query/path/body
  strings to services, which parse + validate them (an unknown enum/band/value is a `ServiceError.Validation`
  → 400). Request bodies are DTOs or `contract` value types. This is enforced by a strict `routes ↛ model`
  rule **with no exception** — the former wire-contract request bodies (`OpenPlayPointsConfig`/
  `TournamentPointsConfig`) were relocated to `org.skopeo.common.contract`.

## The one sanctioned `dto → model` dependency

Three v1 stateless-calculator contract DTOs (`RankingCalculationRequest`/`Response`, `RatingChange`)
deliberately embed shared `@Serializable` domain value types (`Team`, `MatchScore`, `PlayerProfile`,
`Rating`, `RatingCalculationOptions`) directly as their wire format instead of mirroring them into
parallel DTOs. The `dto ↛ model` rule exempts exactly these classes (by name). Fully decoupling that
contract (DTO mirrors + bidirectional mapping) is a separate, larger change — the test is the place to
tighten the rule if we ever pursue it. (The two points-config responses that used to be on this list no
longer touch `model`: their `@Serializable` types were relocated to `org.skopeo.common.contract`.)

## Why ArchUnit (not Konsist)

ArchUnit analyses **compiled bytecode**, so it is robust against the Kotlin compiler version. This repo
already pins the JVM/Kotlin toolchain for a detekt compatibility reason
(`docs/engineering/operations/JVM_COMPATIBILITY.md`), so a bytecode-based analyzer is the safer fit
than a source-scanning tool that bundles its own Kotlin compiler.
