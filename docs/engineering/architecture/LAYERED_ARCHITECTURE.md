# Layered package architecture

The backend is organised into layers by top-level package under `org.skopeo`. Their dependency
directions are **enforced** by `src/test/.../LayeredArchitectureTest.kt` (ArchUnit) so they don't
erode silently. The build fails if a rule is broken.

## Layers and the rules that hold

```
routes (transport)  ──►  service  ──►  repository
   │                       │              │
   └──► dto                └──► model ◄────┘   (model is pure domain)
```

Enforced invariants (each is true in the codebase today):

- **`repository`** never depends on `routes`, `service`, or `dto`. It is the foundation and only
  works with `model`.
- **`model`** is pure domain — it depends on no other app layer (`routes`/`service`/`repository`/`dto`).
  Generic numeric helpers it needs live in `model` (`BigDecimalUtils.kt`).
- **`dto`** never depends on `routes` or `repository`. It is a boundary/serialization type.
- **`service`** never depends on the transport layer (`routes`).

## What is deliberately *not* enforced

`service` and `dto` are **coupled at the HTTP boundary by design**: services accept request DTOs
(e.g. `CreateUserRequest`) and some return response DTOs (e.g. `PlayerService.publicProfile`), and one
DTO maps a service result type (`CalculationDtos`). Forcing strict "DTOs only in routes" would mean a
large command/result refactor for little gain, so that relationship is left unconstrained. If we ever
want it, that's a separate change — the test is the place to tighten the rules.

## Why ArchUnit (not Konsist)

ArchUnit analyses **compiled bytecode**, so it is robust against the Kotlin compiler version. This repo
already pins the JVM/Kotlin toolchain for a detekt compatibility reason
(`docs/engineering/operations/JVM_COMPATIBILITY.md`), so a bytecode-based analyzer is the safer fit
than a source-scanning tool that bundles its own Kotlin compiler.
