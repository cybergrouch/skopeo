# Layered package architecture

The backend is organised into layers by top-level package under `org.skopeo`. Their dependency
directions are **enforced** by `src/test/.../LayeredArchitectureTest.kt` (ArchUnit) so they don't
erode silently. The build fails if a rule is broken.

## Layers and the rules that hold

```
routes ──► { service, dto }                    (routes never touch mapper — translation is service-side)
service ──► { repository, mapper, dto, model }
mapper ──► { dto, model }                       (mapper: dto↔model translation)
repository ──► model                            (model is pure domain — depends up on nothing)
```

Enforced invariants (each is true in the codebase today):

- **`repository`** never depends on `routes`, `service`, `dto`, or `mapper`. It is the foundation and
  only works with `model`.
- **`model`** is pure domain — it depends on no other app layer
  (`routes`/`service`/`repository`/`dto`/`mapper`). Generic numeric helpers it needs live in `model`
  (`BigDecimalUtils.kt`).
- **`dto`** is a **pure serializable boundary record**: it never depends on `routes`, `repository`,
  `service`, or `model` — with one sanctioned exception (below).
- **`mapper`** owns the dto↔model translation (the `toResponse`/`toCommand` extension functions). It
  depends on `dto` + `model` only, never `routes`/`repository`/`service`.
- **`service`** never depends on the transport layer (`routes`); it *may* call `mapper` (one-way, so
  the graph stays acyclic). Services return response DTOs and accept request DTOs.
- **`routes`** never depend on `mapper`: dto↔model translation is hidden behind the service, so a route
  calls `service.*` and responds with the DTO it receives. (Routes still reference a thin slice of
  `model` — enums parsed from query/path params, `ServiceError`, auth principals, and the wire-contract
  request bodies below — so there is deliberately no `routes ↛ model` rule yet.)

## The one sanctioned `dto → model` dependency

Three v1 stateless-calculator contract DTOs (`RankingCalculationRequest`/`Response`, `RatingChange`) and
the two points-config responses (`OpenPlayConfigResponse`, `TournamentConfigResponse`) deliberately embed
shared `@Serializable` domain value types (`Team`, `MatchScore`, `PlayerProfile`, `Rating`,
`RatingCalculationOptions`, `OpenPlayPointsConfig`, `TournamentPointsConfig`) directly as their wire
format instead of mirroring them into parallel DTOs. The `dto ↛ model` rule exempts exactly these classes
(by name). Fully decoupling that contract (DTO mirrors + bidirectional mapping) is a separate, larger
change — the test is the place to tighten the rule if we ever pursue it.

## Why ArchUnit (not Konsist)

ArchUnit analyses **compiled bytecode**, so it is robust against the Kotlin compiler version. This repo
already pins the JVM/Kotlin toolchain for a detekt compatibility reason
(`docs/engineering/operations/JVM_COMPATIBILITY.md`), so a bytecode-based analyzer is the safer fit
than a source-scanning tool that bundles its own Kotlin compiler.
