# Layered package architecture

The backend is organised into layers by top-level package under `org.skopeo`. Their dependency
directions are **enforced** by `src/test/.../LayeredArchitectureTest.kt` (ArchUnit) so they don't
erode silently. The build fails if a rule is broken.

## Layers and the rules that hold

```
routes ──► { service, dto, common }             (never mapper, never model)
service ──► { repository, mapper, dto, model, common }
mapper ──► { dto, model }                       (mapper: dto↔model translation)
repository ──► { model, common }                (the persistence foundation)
model ──► common                                (e.g. the Capability enum; model reaches nothing else)
common ──► ∅                                     (common: shared value types — a pure cross-cutting leaf)
```

`common` (`org.skopeo.common.{error,security,contract}` — each a distinct sub-package under one parent) is
the sanctioned cross-cutting foundation: dependency-free value types any layer may use, itself depending
on nothing above — not even `model`.

Enforced invariants (each is true in the codebase today):

- **`repository`** never depends on `routes`, `service`, `dto`, or `mapper`. It is the foundation and
  only works with `model`.
- **`model`** is the domain — it never depends on `routes`/`service`/`repository`/`dto`/`mapper`; it MAY
  depend on the neutral `common` foundation (e.g. the `Capability` enum). Generic numeric helpers live in
  `model` (`BigDecimalUtils.kt`).
- **`common`** (`org.skopeo.common.{error,security,contract}`) is the sanctioned **cross-cutting
  foundation** of dependency-free shared value types: `ServiceError` (#115); the auth principals
  `ClientPrincipal`/`ClientAuthResult` + the `Capability` authorization enum (#597/#106); and the
  serializable points-config contracts (`OpenPlayPointsConfig`/`TournamentPointsConfig`, #552). **Any**
  layer — including `model` (which uses `Capability`) — may depend on it; `common` depends on nothing
  above it, not even `model`. It is the one package referenced from every layer.
- **`dto`** is a **pure serializable boundary record**: it never depends on `routes`, `repository`,
  `service`, or `model` — with one sanctioned exception (below).
- **`mapper`** owns the dto↔model translation (the `toResponse`/`toCommand` extension functions). It
  depends on `dto` + `model` only, never `routes`/`repository`/`service`.
- **`service`** never depends on the transport layer (`routes`); it *may* call `mapper` (one-way, so
  the graph stays acyclic). Services return response DTOs and accept request DTOs.
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
