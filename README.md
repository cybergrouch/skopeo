# <img src="web/public/favicon.svg" alt="" height="32" align="top" /> Skopeo

A Ktor API for dynamic calculation of tennis rankings based on match results.

## Overview

Skopeo provides real-time ranking calculations for tennis players using the NTRP (National Tennis Rating Program) system. The API calculates updated rankings for both players based on match outcomes, deriving expected results from the difference in their current rankings. NTRP is the only supported rating system by design — UTR support was intentionally removed.

Beyond the stateless calculator, Skopeo persists players, matches, and ratings: admins set each player's initial rating, hosts create match fixtures and upload results, an admin-triggered calculation turns those results into rating changes (dry-run by default, explicit commit to write), and every change is recorded in rating history. A capability-gated React web UI (`web/`) wraps it all — sign-up plus a dashboard whose tabs (Profile, Settings, Standings, Event Organizer, Ratings, Admin, and more) are shown according to each user's capabilities.

## Features

### ✅ Implemented Features

#### 1. **Rating Calculation Engine** (Core)
The heart of the Skopeo system - a sophisticated performance-based rating calculator.

- **Dynamic Rating Calculation**: Real-time calculation of updated player ratings based on match results
- **Elo-Based Algorithm**: Advanced ranking system that considers:
  - Rating differential between players
  - Match dominance (games won ratio)
  - Expected vs actual performance
  - Upset detection and amplification
- **NTRP Rating System**: NTRP only by design (UTR was intentionally removed)
  - NTRP: 1.0-7.0 range with 0.5 published level increments
  - Single K-factor of 0.16 calibrated to the NTRP range
- **Published Levels**: Discrete rating buckets with automatic level change detection
  - Stateless calculator derives the published level dynamically from the value
- **Rating Smoothing**: USTA NTRP Dynamic-style smoothing for stable ratings
  - Configurable smoothing factors (0.3, 0.5, 0.7)
  - Reduces volatility from single exceptional performances
- **Comprehensive Validation**: Input validation for player profiles, ratings, and match scores

#### 2. **REST API**
Production-ready HTTP API built with Ktor 3.0.3.

- **Ranking Calculation Endpoint**: POST `/api/v1/calculate-ranking`
  - Accepts player profiles with ratings and match scores
  - Returns updated ratings with published levels
  - Percentage changes and level change indicators
- **Health Check**: GET `/health` - Service status and version
- **Error Handling**: Comprehensive error responses with clear messages

#### 3. **API Documentation**
Interactive and machine-readable API documentation.

- **Swagger UI**: Interactive API explorer at `/swagger`
  - Try API calls directly in the browser
  - Full request/response documentation
  - Example payloads
- **OpenAPI Spec**: Raw specification at `/openapi.yaml`
  - Machine-readable YAML format
  - Compatible with code generators and API clients
  - Complete schema definitions for all DTOs

#### 4. **Quality Assurance**
Comprehensive testing and code quality infrastructure.

- **Test Suite**: an extensive automated suite (JUnit 5 + Kotest)
  - Unit tests for business logic
  - Integration tests for API contracts
  - Edge case tests for algorithm validation
  - Kotest DSL assertions (enforced by Detekt)
- **Code Coverage**: JaCoCo, enforced at **75% line / 70% branch**
  - JaCoCo reports (HTML/XML)
  - Threshold enforcement in the build (`jacocoTestCoverageVerification`)
- **Code Quality**: ktlint + Detekt
  - Automatic formatting via Git hooks
  - Style enforcement
  - Best practice rules

#### 5. **Persistence** (Players, Matches, Ratings)
Built on top of the stateless calculator (PostgreSQL + Flyway + Exposed).

- **Player profiles**: Sign-up provisions a profile from the verified Firebase token; `sex` (Male/Female) and date of birth are required. Names and contacts are append-only sub-resources.
- **Capability-based authorization**: PLAYER, HOST, CLUB_OWNER, ADMINISTRATOR, RATER, RESEARCHER, and POINTS_MANAGER grants gate every operation.
- **Admin-set initial ratings**: Admins assign each player's starting rating; a pending-assessment list surfaces players without one.
- **Match fixtures + results**: Hosts/admins create fixtures and upload results. Recording a result does not itself change ratings.
- **Rating-calculation trigger**: Admins run the calculation (`POST /api/v1/ratings/calculations`) over matches pending calculation. **Dry-run is the default** (full preview, no writes); an explicit `{"dryRun": false}` commits ratings, history, and `rated_at`.
- **Rating history**: Every change is recorded and readable per player.

#### 6. **Web UI** (`web/`)
A capability-gated React + Vite single-page app over the API.

- **Authentication**: Firebase-auth sign-up and login.
- **Dashboard**: a capability-gated set of tabs (Profile, Settings, Standings, Event Organizer, Seeding, Ratings, Invites, Activity Log, Reports, Admin, and more), shown according to the signed-in user's capabilities (e.g. Event Organizer/Seeding need match-management; Ratings needs RATER; Invites/Activity Log/Reports/Admin need ADMINISTRATOR).
- **Generated API client**: Typed client generated from the OpenAPI spec under `web/src/api/generated/`.

### Input/Output

**Input** (POST `/api/v1/calculate-ranking`):
```json
{
  "teams": {
    "T1": {
      "teamId": "T1",
      "name": "Player 1",
      "players": [
        { "playerId": "P1", "name": "Player 1", "rating": { "value": "4.5" } }
      ],
      "teamType": "SINGLES"
    },
    "T2": {
      "teamId": "T2",
      "name": "Player 2",
      "players": [
        { "playerId": "P2", "name": "Player 2", "rating": { "value": "4.0" } }
      ],
      "teamType": "SINGLES"
    }
  },
  "matchScore": {
    "sets": [
      { "games": { "T1": 6, "T2": 2 }, "winnerTeamId": "T1" }
    ]
  }
}
```

The API is team-based (`teams: Map<String, Team>`, keyed by team ID) so doubles can be added later without a schema change; for singles each team holds exactly one player. `matchScore` games and `winnerTeamId` reference team IDs.

**Output**:
```json
{
  "ratingChanges": {
    "P1": {
      "change": "+0.032000",
      "previousRating": { "value": "4.5", "publishedLevel": {...} },
      "newRating": { "value": "4.532000", "publishedLevel": {...} },
      "percentChange": "+0.71%",
      "levelChanged": false
    },
    "P2": { ... }
  }
}
```

## Persistence Status

Skopeo persists its core data (PostgreSQL + Flyway + Exposed):
- ✅ Player profiles (database-backed, capability-gated)
- ✅ Admin-set initial ratings + rating history, plus player re-rate requests (approve/deny)
- ✅ Match fixtures and uploaded results (editable until rated)
- ✅ Admin-triggered rating calculation (dry-run default, explicit commit)
- ✅ Per-NTRP-band standings / "Ranking Race" leaderboards
- ✅ Event Organizer (events/meets with participants and matches) + host seeding generation
- ✅ Governance: domain audit/activity log, duplicate detection + rectification, admin invites
- ✅ Public pages by shareable code (player / match / event) with QR sharing
- ✅ Web UI: sign-up + capability-gated dashboard (Profile / Settings / Research / Standings / Claim / Event Organizer / Seeding / Placeholder Players / Ratings / Invites / Activity Log / Reports / Points Management / Account Management / Admin / About — each gated by capability)

The `POST /api/v1/calculate-ranking` endpoint remains a stateless "what-if" calculator that writes nothing.

## Product Roadmap

Skopeo has grown from a stateless rating calculator into a capability-gated ranking platform. The core engine, persistence, web UI, standings, Event Organizer, governance (audit / duplicate rectification / invites), and partner API access are **shipped** (see [Implemented Features](#-implemented-features) above). Remaining and aspirational work — Philippine KYC identity verification, dynamic rating confidence, doubles, tournaments / leagues, mobile apps, payments — lives in **[docs/product/ROADMAP.md](docs/product/ROADMAP.md)**.

## Technology Stack

### Current
- **Language**: Kotlin 2.2.21
- **Web Framework**: Ktor 3.0.3 (Netty server)
- **Serialization**: kotlinx.serialization (JSON)
- **Database**: PostgreSQL
- **Migrations**: Flyway
- **ORM**: Exposed (Kotlin SQL framework) + HikariCP connection pool
- **Auth**: Firebase Auth (tokens verified at the API)
- **Web UI**: React + Vite (`web/`)
- **Build Tool**: Gradle 9.5.1
- **Code Quality**: ktlint + Detekt
- **Testing**: JUnit 5 + Kotest assertions
- **Coverage**: JaCoCo
- **Logging**: Logback with structured JSON output for Cloud Logging
- **API Docs**: Swagger UI + OpenAPI 3.0

### Planned
- **Caching**: Redis (for ranking leaderboards)
- **File Storage**: AWS S3 or local filesystem (ID document storage)
- **OCR**: Tesseract or cloud OCR service (ID verification)
- **Queue**: RabbitMQ or Redis (async rating calculations)
- **Deployment**: Docker + Kubernetes or AWS ECS

## Getting Started

### Prerequisites

- Java 17 or higher
- Gradle (included via wrapper)

### Running the Application

#### Option 1: Using the helper script (recommended)
```bash
./scripts/start-server.sh
```

#### Option 2: Using Gradle directly
```bash
./gradlew run
```

The API will start on `http://localhost:8080`

### Docker Deployment

#### Option 1: Using Docker Compose (recommended)
```bash
docker-compose up
```

#### Option 2: Using Docker directly
```bash
# Build the image
docker build -t skopeo .

# Run the container
docker run -d -p 8080:8080 --name skopeo skopeo

# View logs
docker logs -f skopeo

# Stop the container
docker stop skopeo
```

#### Option 3: Using the helper script
```bash
# Build with version tag
./scripts/docker-build.sh 1.0.0

# Run with Docker
docker run -d -p 8080:8080 skopeo:1.0.0
```

See [docs/engineering/operations/DOCKER_DEPLOYMENT.md](docs/engineering/operations/DOCKER_DEPLOYMENT.md) for comprehensive Docker deployment guide.

### Testing the API

#### Automated Testing

Run the automated test suite:
```bash
./scripts/test-api.sh
```

This will test all available endpoints and show you:
- Response status codes
- Response bodies
- Response times
- Pass/fail status for each endpoint

#### Manual Testing

**Using a web browser:**
- Root endpoint: http://localhost:8080/
- Health check: http://localhost:8080/health

**Using curl:**
```bash
# Test root endpoint
curl http://localhost:8080/

# Test health endpoint
curl http://localhost:8080/health
```

**Using HTTPie (if installed):**
```bash
http :8080/
http :8080/health
```

#### cURL Examples and Reference

View all available cURL commands and examples:
```bash
./scripts/curl-examples.sh
```

### Stopping the Server

```bash
./scripts/stop-server.sh
```

Or press `Ctrl+C` in the terminal where the server is running.

## Available Scripts

All utility scripts are located in the `scripts/` directory:

| Script | Description |
|--------|-------------|
| `start-server.sh` | Start the API server with port conflict detection |
| `stop-server.sh` | Stop the running API server |
| `test-api.sh` | Run automated tests for all endpoints |
| `curl-examples.sh` | Display cURL command examples and usage |
| `docker-build.sh` | Build and tag Docker images for deployment |
| `format-code.sh` | Auto-format all Kotlin code with ktlint |
| `check-coverage.sh` | Run tests and verify the coverage thresholds (75% line / 70% branch) |

See `scripts/README.md` for detailed documentation.

## API Endpoints

### Current Endpoints

| Method | Endpoint | Description | Response |
|--------|----------|-------------|----------|
| GET | `/` | Root endpoint | `Skopeo API` |
| GET | `/health` | Health check | JSON with status and version |
| GET | `/swagger` | Swagger UI | Interactive API documentation |
| GET | `/openapi.yaml` | OpenAPI specification | Raw OpenAPI spec (YAML) |
| POST | `/api/v1/calculate-ranking` | Stateless "what-if" ranking calculation | JSON with updated ratings |
| POST | `/api/v1/users` | Sign-up / provision a profile (sex + date of birth required) | Created user |
| GET | `/api/v1/users/{id}` | Read a user profile | User |
| PUT | `/api/v1/users/{id}/ratings` | Admin-set a user's rating | Rating |
| GET | `/api/v1/users/{id}/rating-history` | Read a user's rating history | Rating-change list |
| GET | `/api/v1/users/pending-assessment` | Admin: users with no rating yet | User list |
| GET / POST | `/api/v1/matches` | List / create match fixtures | Matches |
| POST | `/api/v1/matches/{id}/result` | Upload a match result | Match |
| POST | `/api/v1/ratings/calculations` | Admin: run rating calculation (dry-run default; `{"dryRun": false}` commits) | Calculation preview/outcome |

The table above is a representative slice. The full surface spans ~27 route groups (users, matches, ratings, events, clubs, circuits, standings, ranking points, invites, reports, placeholders, seeding, API clients, audit, and more). For the complete, authoritative list use the **Swagger UI (`/swagger`)** / **OpenAPI spec (`/openapi.yaml`)**, and see **[BACKEND_ARCHITECTURE.md](docs/engineering/architecture/BACKEND_ARCHITECTURE.md)** for the route-group catalog and request pipeline.

### API Documentation

- **Interactive**: Visit `/swagger` for Swagger UI (try API calls in browser)
- **Machine-readable**: Download `/openapi.yaml` for code generation and tooling
- **Detailed specs**: See [docs/engineering/api/API_DOCUMENTATION.md](docs/engineering/api/API_DOCUMENTATION.md)

## How Ratings Are Calculated

Skopeo uses a **performance-based rating system** with normalized gaps to ensure fair calculations across different rating systems. The algorithm considers **how dominantly** you won and whether the match was an **upset** or **expected outcome**.

### Quick Guide for Players

**What affects your rating?**
1. **The result** - Win or lose
2. **Your opponent's rating** - Beating stronger players gains more points
3. **How dominant** - 6-0 wins count much more than 7-6 wins
4. **Rating gap** - Competitive matches (within threshold) produce larger changes
5. **Upsets** - Unexpected wins produce significant rating changes

**Two main scenarios:**

1. **Competitive or Expected Win**: Rating changes decrease as gap increases
   - Equal players (no gap): Maximum performance-based change
   - Small gap (within 8.3% of range): Moderate change based on gap size
   - At threshold (0.5 NTRP): Zero change
   - Beyond threshold (expected outcome): Zero change

2. **Upset Win**: Underdog wins against favorite
   - Larger gap = larger rating change
   - Upset multiplier (2×) applied
   - Change proportional to gap size

### Examples

**Scenario 1: Close match between equals**
- You (5.0 NTRP) vs Opponent (5.0 NTRP)
- You win 6-4: Gain ~0.032 points
- Equal ratings = full performance-based change

**Scenario 2: Dominant match between equals**
- You (5.0 NTRP) vs Opponent (5.0 NTRP)
- You win 6-0: Gain ~0.160 points
- Dominance factor amplifies the change (shutout = 5× larger than 6-4)

**Scenario 3: Small gap, expected win**
- You (4.5 NTRP) vs Opponent (4.0 NTRP) [gap = 0.5, at threshold]
- You win 6-3: Gain 0.0 points
- Met expectations exactly, ratings are already accurate

**Scenario 4: Upset victory**
- You (3.0 NTRP) vs Opponent (4.0 NTRP) [gap = 1.0]
- You win 6-2: Gain ~0.32 points
- Upset with decent dominance = significant change

**Scenario 5: Large gap mismatch**
- You (6.0 NTRP) vs Opponent (1.0 NTRP) [gap = 5.0]
- You win 6-0, 6-0: Gain 0.0 points
- Heavily favored player winning as expected = no change

**Scenario 6: Close match near threshold**
- You (4.3 NTRP) vs Opponent (4.0 NTRP) [gap = 0.3]
- You win 7-5: Gain ~0.011 points
- Within competitive threshold but close match = small change

### Key Concepts

**Competitive Threshold**: 8.3% of the NTRP rating range (~1/12)
- NTRP: 0.5 points (e.g., 4.0 vs 4.5)
- Matches within this threshold produce performance-based changes
- Matches beyond this threshold (expected outcomes) produce zero change

**Dominance Factor**: Based on game margin, not ratio
- Per-set formula: (games won - games lost) / (games won + games lost)
- Match dominance = average of the per-set dominances (a lost set counts as a negative term)
- 6-0 = 1.0 dominance (maximum)
- 6-4 = 0.2 dominance
- 7-6 = 0.077 dominance (very close)
- 6-0, 3-6, 6-2 = (1.0 - 0.333 + 0.5) / 3 = 0.389 dominance

**K-Factor**:
- NTRP: K = 0.16 (typical changes ±0.032 to ±0.160)

### Rating Smoothing (Optional)

Skopeo supports **USTA NTRP Dynamic-style rating smoothing** to create more stable and predictable ratings:

**What is smoothing?**
- Blends calculated rating changes with previous ratings
- Reduces volatility from single exceptional/poor performances
- Provides gradual convergence toward true skill level

**Smoothing Factors:**
- **0.5** - USTA standard (recommended default, 50% of change applied)
- **0.3** - Conservative (30% applied, for established players)
- **0.7** - Aggressive (70% applied, for newer players)
- **1.0** - Full change (no smoothing, equivalent to disabled)

**Example Impact** (4.0 NTRP players, 6-0 score):
```
Without smoothing: +0.160 / -0.160
With 0.3 factor:   +0.048 / -0.048  (30% applied)
With 0.5 factor:   +0.080 / -0.080  (50% applied - USTA style)
With 0.7 factor:   +0.112 / -0.112  (70% applied)
```

**Usage:**
```json
{
  "teams": { ... },
  "matchScore": { ... },
  "options": {
    "smoothingEnabled": true,
    "smoothingFactor": 0.5
  }
}
```

See **[RATING_SMOOTHING.md](docs/product/RATING_SMOOTHING.md)** for complete documentation with examples and best practices.

### Rating Boundaries
- **NTRP**: 1.0 (beginner) to 7.0 (world-class)

### Want More Details?
- **[RATING_SMOOTHING.md](docs/product/RATING_SMOOTHING.md)** - Complete rating smoothing guide with examples and best practices
- **[RATING_CALCULATION_ALGORITHM.md](docs/product/RATING_CALCULATION_ALGORITHM.md)** - Complete algorithm explanation with formulas, edge cases, and technical details

## Documentation

Comprehensive documentation is available in the `docs/` directory:

- **[API_DOCUMENTATION.md](docs/engineering/api/API_DOCUMENTATION.md)** - Complete API reference
  - Endpoint specifications
  - Request/response formats
  - Data models and validation rules
  - Examples and error codes

- **[RATING_SMOOTHING.md](docs/product/RATING_SMOOTHING.md)** - Rating smoothing algorithm (NEW)
  - USTA NTRP Dynamic-style smoothing explained
  - Mathematical formulas and examples
  - Smoothing factor recommendations (0.3, 0.5, 0.7)
  - Usage guide and best practices
  - Performance and backward compatibility

- **[RATING_CALCULATION_ALGORITHM.md](docs/product/RATING_CALCULATION_ALGORITHM.md)** - Complete algorithm behavior guide
  - Performance-based Elo system overview
  - Five adjustment cases explained with examples
  - Edge cases and special handling
  - Magic constant explanations
  - Known limitations and test coverage

- **[AUDIT_TRAIL.md](docs/engineering/architecture/AUDIT_TRAIL.md)** - Audit trail design
  - Monadic pattern explanation
  - Pure function benefits
  - Testing without mocking
  - Usage examples

- **[TESTING_STRATEGY.md](docs/engineering/quality/TESTING_STRATEGY.md)** - Testing pyramid and strategy
  - Test organization and pyramid
  - Unit vs integration tests
  - Pure function testing
  - Coverage goals and best practices

- **[CODE_COVERAGE.md](docs/engineering/quality/CODE_COVERAGE.md)** - Code coverage guide
  - JaCoCo configuration
  - Coverage reports (HTML/XML)
  - Enforced thresholds (75% lines / 70% branches)
  - CI/CD integration

- **[JVM_COMPATIBILITY.md](docs/engineering/operations/JVM_COMPATIBILITY.md)** - JVM version strategy
  - Build failure investigation (detekt vs Java 25+)
  - Gradle daemon pinned to Java 21 LTS and why
  - GCP/AWS Java runtime support survey
  - Upgrade path when detekt 2.0 ships

- **[DEPLOYMENT_GCP.md](docs/engineering/operations/DEPLOYMENT_GCP.md)** - Cloud deployment guide
  - Platform decision: GCP (Cloud Run + Cloud SQL) vs AWS, with costs
  - Step-by-step deployment of the API and PostgreSQL
  - Day-2 operations, scaling path, and teardown

- **[WEB_UI_ARCHITECTURE.md](docs/engineering/architecture/WEB_UI_ARCHITECTURE.md)** - Web UI decisions & roadmap
  - Decoupled frontend, monorepo `web/` layout
  - SPA vs SSR analysis; recommended tech stack (SPA + PWA + Capacitor path)
  - Authentication approach (token-based via Firebase Auth, verified at the API)

- **[CICD.md](docs/engineering/operations/CICD.md)** - CI/CD plan (GitHub Actions)
  - Phase 1: CI gate (`./gradlew check`) + branch protection for the PR workflow
  - Phase 2: keyless Cloud Run deploys via Workload Identity Federation
  - Phase 3: web UI CI/CD (Firebase Hosting, path-filtered)

## Testing

Skopeo uses a comprehensive testing strategy across unit and integration layers:

### Test Distribution

```
Unit Tests:        Fast, isolated, pure-function testing (business logic + algorithm)
Integration Tests: API contracts, HTTP layer (Ktor testApplication + Testcontainers Postgres)
```

**Test Quality**:
- ✅ All tests use Kotest DSL assertions (enforced by Detekt)
- ✅ No mocking required for business logic (pure functions)
- ✅ Audit trail testing for transparency
- ✅ Fast feedback loop (~500ms for unit tests)

### Running Tests

```bash
# Run all tests (automatically generates coverage report)
./gradlew test

# Run unit tests only (fast)
./gradlew test --tests "org.skopeo.service.*"

# Run specific test class
./gradlew test --tests "*.PerformanceBasedRankingCalculatorImplTest"

# Check coverage against the thresholds (75% line / 70% branch)
./scripts/check-coverage.sh

# Verify coverage thresholds (Gradle task)
./gradlew jacocoTestCoverageVerification

# View coverage report
open build/reports/jacoco/test/html/index.html
```

### Key Features

- **Pure Function Testing**: No mocking required for business logic
- **Audit Trail Testing**: Can verify audit information directly
- **Fast Feedback**: Unit tests run in ~500ms
- **Enforced Coverage**: 75% line / 70% branch (JaCoCo `jacocoTestCoverageVerification`)

See [TESTING_STRATEGY.md](docs/engineering/quality/TESTING_STRATEGY.md) for complete details.

## Development

### Code Style

This project uses ktlint for consistent code formatting.

#### Automatic Formatting on Commit (Recommended)

Install the Git pre-commit hook to automatically format code before every commit:

```bash
# Install the pre-commit hook (one-time setup)
./gradlew installGitHooks

# Uninstall if needed
./gradlew uninstallGitHooks
```

Once installed, the hook will:
1. Auto-format your code with ktlint
2. Auto-stage the formatted files
3. Run ktlint check to verify
4. Abort commit if style violations can't be fixed

#### Manual Formatting

```bash
# Auto-fix style violations
./scripts/format-code.sh

# Or use Gradle directly
./gradlew ktlintFormat

# Check for style violations
./gradlew ktlintCheck
```

### Building

```bash
# Build the project
./gradlew build

# Run tests
./gradlew test

# Build distribution
./gradlew installDist
```

## License

Copyright (C) 2026 Lange Pantoja

Skopeo is licensed under the **GNU Affero General Public License v3.0 or later**
(`AGPL-3.0-or-later`). You may use, modify, and distribute it under those terms; in
particular, if you run a modified version as a network service, you must make the
corresponding source available to its users. See [LICENSE](LICENSE) for the full text.

Source files carry [SPDX](https://spdx.dev) headers
(`SPDX-License-Identifier: AGPL-3.0-or-later`).
