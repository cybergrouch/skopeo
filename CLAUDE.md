# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Skopeo is a Ktor REST API that calculates performance-based tennis ratings (NTRP only) from match results using an Elo-style algorithm. UTR support was removed permanently; the system is NTRP-only by design. The stateless v1 calculator still exists behind `/api/v1/calculate-ranking`, but persistence is now fully built on top of it (PostgreSQL + Flyway + Exposed): admin-set initial ratings, match fixtures + result upload, a rating-calculation trigger (dry-run by default, explicit commit), and rating history. A capability-gated React web UI (`web/`) sits on top: sign-up (sex + date of birth required) plus a dashboard with Profile / Matches / Research / Admin tabs.

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

**Stateless calculation flow**: `Application.kt` (Ktor module setup) → `routes/RankingRoutes.kt` (POST `/api/v1/calculate-ranking`, error handling) → `service/calculator/impl/v1/PerformanceBasedRankingCalculatorImpl.kt` (the algorithm) → `RankingCalculationResult` (response DTO + audit trail). This endpoint persists nothing — it is a pure "what-if" calculator.

**Pure-function core with audit trail**: `RankingCalculator.calculate()` is a pure function with no side effects. Instead of logging internally, it returns a `RankingCalculationResult` containing both the response and an `AuditTrail` (list of `AuditEntry`); the route layer logs the audit entries. This is why business-logic tests need no mocking and can assert on audit contents directly. See `docs/AUDIT_TRAIL.md`.

**Team-based request model**: The API accepts `teams: Map<String, Team>`, not bare players — designed so doubles can be added later without breaking the schema. Currently `RankingCalculationRequest.init` enforces exactly 2 SINGLES teams of 1 player each. Validation lives in DTO/model `init` blocks and throws `IllegalArgumentException`, which routes map to 400.

**Persistence flow (built)**: routes → service → repository → Exposed tables. The pieces:
- **Users**: `routes/UserRoutes.kt` → `service/user/UserService.kt` → `repository/UserRepository.kt` (`UserTables.kt`). Sign-up (`POST /api/v1/users`) provisions a profile from the verified Firebase token; `sex` (Male/Female) and `dateOfBirth` are required. Names and contacts are append-only sub-resources (`NameRoutes`/`ContactRoutes`). Authorization is capability-based (`Capability`: PLAYER, HOST, CLUB_OWNER, ADMINISTRATOR) via `CapabilityRoutes`/`CapabilityService`.
- **Ratings**: `routes/RatingRoutes.kt` → `service/rating/RatingService.kt` → `repository/RatingRepository.kt` (`RatingTables.kt`). Admins set a user's initial rating (`PUT /api/v1/users/{id}/ratings`); the pending-assessment list surfaces users without one. Rating history is read at `GET /api/v1/users/{id}/rating-history`.
- **Matches**: `routes/MatchRoutes.kt` → `service/match/MatchService.kt` → `repository/MatchRepository.kt` (`MatchTables.kt`). HOST/ADMINISTRATOR create fixtures and upload results; recording a result does NOT compute ratings.
- **Rating-calculation trigger**: `service/rating/RatingCalculationService.kt` (ADMINISTRATOR only, `POST /api/v1/ratings/calculations`). It processes matches pending calculation oldest→newest, carrying ratings forward through an in-memory snapshot, reusing the stateless `RankingCalculator`. **Dry-run is the default** (full preview, no writes); only an explicit `{"dryRun": false}` persists ratings + history + `rated_at` in one transaction.

**Web UI** (`web/`, React + Vite, generated API client under `web/src/api/generated/`): Firebase-auth sign-up/login, then a capability-gated dashboard (`routes/DashboardPage.tsx`) with Profile / Matches / Research / Admin tabs (Matches/Research require match-management capability; Admin requires ADMINISTRATOR). See `docs/WEB_UI_ARCHITECTURE.md`.

**Money-style precision**: Ratings are `BigDecimal` throughout (serialized as strings in JSON); `service/calculator/impl/BigDecimalUtils.kt` centralizes scale/rounding.

**Algorithm versioning**: Implementations live under `service/calculator/impl/v1/` behind the `RankingCalculator` interface — keep new algorithm work behind that interface.

**Database**: `config/DatabaseConfig.kt` wires HikariCP + Flyway migrations + Exposed on startup. `Application.module(initDatabase: Boolean)` allows tests to skip DB init — integration tests call `module(initDatabase = false)`. Config is read from `src/main/resources/application.yaml` (env vars `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`).

**Algorithm behavior** (details in `docs/RATING_CALCULATION_ALGORITHM.md`): single NTRP K-factor of 0.16 over the 1.0–7.0 range. Rating changes depend on dominance (game margin), the rating gap vs a competitive threshold (8.3% of the NTRP range = 0.5 points; expected wins beyond it yield zero change), a 2× upset multiplier, and optional USTA-style smoothing via `options.smoothingFactor`.

## Code Style Enforcement

- **Named arguments required everywhere**: detekt's `NamedArguments` rule has threshold 1 — name parameters even on single-argument calls.
- **Kotest assertions only in tests**: detekt forbids JUnit/kotlin-test assertions (`assertEquals`, `assertTrue`, etc.); use `shouldBe`, `shouldThrow<T>`, etc.
- ktlint runs as part of `build`; a pre-commit hook can be installed with `./gradlew installGitHooks`.

## Testing Notes

- Tests are JUnit 5 + Kotest assertions + Ktor `testApplication`.
- Shared test fixtures: `TestScenarios.kt`, `TeamTestHelpers.kt`, `RankingTestCase.kt` under `src/test/.../calculator/impl/`.
- JaCoCo excludes `dto/`, `model/`, `config/`, and `Application` from coverage; `check` fails below 75% line / 70% branch coverage on what remains.
- The OpenAPI spec (`src/main/resources/openapi/documentation.yaml`) is hand-maintained and verified by `OpenAPIIntegrationTest` — update it when changing the API.
