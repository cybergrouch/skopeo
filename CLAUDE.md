# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Skopeo is a Ktor REST API that calculates performance-based tennis ratings (NTRP and UTR systems) from match results using an Elo-style algorithm. The v1 calculator is stateless; database infrastructure (PostgreSQL + Flyway + Exposed) exists but persistence features are not yet built.

## Commands

```bash
./gradlew build                  # Full build (includes ktlint, detekt, tests, coverage verification)
./gradlew test                   # Run all tests (auto-generates JaCoCo report)
./gradlew test --tests "*.PerformanceBasedRankingCalculatorImplTest"   # Single test class
./gradlew test --tests "org.skopeo.service.*"                          # Unit tests only
./gradlew ktlintFormat           # Auto-fix formatting
./gradlew ktlintCheck            # Check formatting
./gradlew detekt                 # Static analysis (config: detekt.yml, baseline: detekt-baseline.xml)
./gradlew jacocoTestCoverageVerification   # Enforce coverage thresholds (75% line, 70% branch)
./gradlew run                    # Start server on http://localhost:8080
docker-compose up                # Run with PostgreSQL (DB: SkopeoDb, postgres/postgres on :5432)
./gradlew flywayMigrate          # Run DB migrations (src/main/resources/db/migration/)
```

Helper scripts in `scripts/`: `start-server.sh`, `stop-server.sh`, `test-api.sh` (endpoint smoke tests), `check-coverage.sh`.

## JVM Constraints

- Code targets Java 17 (Gradle toolchain).
- The Gradle daemon is pinned to Java 21 in `gradle/gradle-daemon-jvm.properties` because detekt 1.23.8's bundled Kotlin compiler crashes on Java 25+. Do not raise it until detekt 2.0 is adopted. See `docs/JVM_COMPATIBILITY.md`.

## Architecture

**Request flow**: `Application.kt` (Ktor module setup) → `routes/RankingRoutes.kt` (POST `/api/v1/calculate-ranking`, error handling) → `service/calculator/impl/v1/PerformanceBasedRankingCalculatorImpl.kt` (the algorithm) → `RankingCalculationResult` (response DTO + audit trail).

**Pure-function core with audit trail**: `RankingCalculator.calculate()` is a pure function with no side effects. Instead of logging internally, it returns a `RankingCalculationResult` containing both the response and an `AuditTrail` (list of `AuditEntry`); the route layer logs the audit entries. This is why business-logic tests need no mocking and can assert on audit contents directly. See `docs/AUDIT_TRAIL.md`.

**Team-based request model**: The API accepts `teams: Map<String, Team>`, not bare players — designed so doubles can be added later without breaking the schema. Currently `RankingCalculationRequest.init` enforces exactly 2 SINGLES teams of 1 player each. Validation lives in DTO/model `init` blocks and throws `IllegalArgumentException`, which routes map to 400.

**Money-style precision**: Ratings are `BigDecimal` throughout (serialized as strings in JSON); `service/calculator/impl/BigDecimalUtils.kt` centralizes scale/rounding.

**Algorithm versioning**: Implementations live under `service/calculator/impl/v1/`. A v2 (database-backed published levels) is planned — keep new algorithm work behind the `RankingCalculator` interface.

**Database**: `config/DatabaseConfig.kt` wires HikariCP + Flyway migrations + Exposed on startup. `Application.module(initDatabase: Boolean)` allows tests to skip DB init — integration tests call `module(initDatabase = false)`. Config is read from `src/main/resources/application.yaml` (env vars `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`).

**Algorithm behavior** (details in `docs/ALGORITHM_BEHAVIOR.md`): rating changes depend on dominance (game margin), the rating gap vs a competitive threshold (8.3% of system range; expected wins beyond it yield zero change), a 2× upset multiplier, and optional USTA-style smoothing via `options.smoothingFactor`.

## Code Style Enforcement

- **Named arguments required everywhere**: detekt's `NamedArguments` rule has threshold 1 — name parameters even on single-argument calls.
- **Kotest assertions only in tests**: detekt forbids JUnit/kotlin-test assertions (`assertEquals`, `assertTrue`, etc.); use `shouldBe`, `shouldThrow<T>`, etc.
- ktlint runs as part of `build`; a pre-commit hook can be installed with `./gradlew installGitHooks`.

## Testing Notes

- Tests are JUnit 5 + Kotest assertions + Ktor `testApplication`.
- Shared test fixtures: `TestScenarios.kt`, `TeamTestHelpers.kt`, `RankingTestCase.kt` under `src/test/.../calculator/impl/`.
- JaCoCo excludes `dto/`, `model/`, `config/`, and `Application` from coverage; `check` fails below 75% line / 70% branch coverage on what remains.
- The OpenAPI spec (`src/main/resources/openapi/documentation.yaml`) is hand-maintained and verified by `OpenAPIIntegrationTest` — update it when changing the API.
