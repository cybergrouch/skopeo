# Tennis Levelr

A Ktor API for dynamic calculation of tennis rankings based on match results.

## Overview

Tennis Levelr provides real-time ranking calculations for tennis players using either NTRP (National Tennis Rating Program) or UTR (Universal Tennis Rating) systems. The API calculates updated rankings for both players based on match outcomes, deriving expected results from the difference in their current rankings.

## Features

### ✅ Implemented Features (v0.1)

#### 1. **Rating Calculation Engine** (Core)
The heart of the Tennis Levelr system - a sophisticated performance-based rating calculator.

- **Dynamic Rating Calculation**: Real-time calculation of updated player ratings based on match results
- **Elo-Based Algorithm**: Advanced ranking system that considers:
  - Rating differential between players
  - Match dominance (games won ratio)
  - Expected vs actual performance
  - Upset detection and amplification
- **Multiple Rating Systems**: Full support for both rating systems
  - NTRP: 1.0-7.0 range with 0.5 published level increments
  - UTR: 1.0-16.0 range with 1.0 published level increments
- **Published Levels**: Discrete rating buckets with automatic level change detection
  - v1: Stateless calculation (published level calculated dynamically)
  - v2 ready: Database-backed with scheduled updates (future)
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
- **Metrics**: GET `/metrics` - Prometheus metrics for monitoring
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

- **Test Suite**: 69 automated tests
  - Unit tests for business logic
  - Integration tests for API contracts
  - Edge case tests for algorithm validation
  - Kotest DSL assertions (enforced by Detekt)
- **Code Coverage**: ~79% line coverage, ~75% branch coverage
  - JaCoCo reports (HTML/XML)
  - Threshold enforcement (75% minimum)
- **Code Quality**: ktlint + Detekt
  - Automatic formatting via Git hooks
  - Style enforcement
  - Best practice rules

### Input/Output

**Input** (POST `/api/v1/calculate-ranking`):
```json
{
  "players": {
    "P1": {
      "playerId": "P1",
      "name": "Player 1",
      "rating": { "value": "4.5", "system": "NTRP" }
    },
    "P2": {
      "playerId": "P2",
      "name": "Player 2",
      "rating": { "value": "4.0", "system": "NTRP" }
    }
  },
  "matchScore": {
    "sets": [
      { "games": { "P1": 6, "P2": 2 }, "winner": "P1" }
    ]
  }
}
```

**Output**:
```json
{
  "ratingChanges": {
    "P1": {
      "change": "+0.032000",
      "previousRating": { "value": "4.5", "system": "NTRP", "publishedLevel": {...} },
      "newRating": { "value": "4.532000", "system": "NTRP", "publishedLevel": {...} },
      "percentChange": "+0.71%",
      "levelChanged": false
    },
    "P2": { ... }
  }
}
```

## Current Limitations

The current version (v0.1) is **stateless** and does not persist:
- ❌ Player profiles (no database storage)
- ❌ Historical ratings (no rating history)
- ❌ Match results (no match database)
- ❌ Player rankings (no leaderboard/ranking table)

These limitations will be addressed in the MVP roadmap below.

## Roadmap to MVP (Minimum Viable Product)

To reach MVP status, Tennis Levelr needs to evolve from a **stateless rating calculator** to a **complete player ranking system** with persistence. The following features are required:

### 🎯 MVP Feature Set

#### 1. **Player Profile Management** (PRIORITY: HIGH)
Complete player lifecycle management with identity verification.

**Core Features**:
- ✅ Create new player profiles
  - Name, contact information, birthdate
  - Initial rating assignment (self-assessment or default)
  - Profile photo upload
- ✅ Update player information
  - Contact details, preferences
  - Rating system preference (NTRP vs UTR)
- ✅ View player profile
  - Current rating and published level
  - Match history summary
  - Win/loss record
- ✅ Archive/deactivate players
  - Soft delete for historical data integrity

**Sub-Feature: Player Identity Verification (KYC)** 🇵🇭
Philippine-specific identity verification for tournament play eligibility.

- ✅ **Philippine Government ID Validation**
  - Passport number verification
  - Driver's License validation
  - UMID (Unified Multi-Purpose ID) support
  - SSS/GSIS ID validation
  - National ID (PhilSys) integration
- ✅ **Automatic Verification Flow**
  - OCR for ID document scanning
  - API integration with government databases (if available)
  - Manual verification fallback for admin review
- ✅ **Verification Status Tracking**
  - Pending, Verified, Rejected states
  - Verification expiry dates
  - Re-verification workflows

**Database Schema (Proposed)**:
```
players
  - id (UUID)
  - name (String)
  - email (String, unique)
  - phone (String)
  - birthdate (Date)
  - current_rating_ntrp (Decimal)
  - current_rating_utr (Decimal)
  - preferred_system (Enum: NTRP|UTR)
  - photo_url (String)
  - status (Enum: ACTIVE|INACTIVE|SUSPENDED)
  - created_at (Timestamp)
  - updated_at (Timestamp)

player_verifications (Philippine KYC)
  - id (UUID)
  - player_id (FK → players.id)
  - id_type (Enum: PASSPORT|DRIVERS_LICENSE|UMID|SSS|GSIS|NATIONAL_ID)
  - id_number (String, encrypted)
  - verification_status (Enum: PENDING|VERIFIED|REJECTED)
  - verified_at (Timestamp)
  - verified_by (FK → admins.id)
  - expiry_date (Date)
  - document_url (String, encrypted)
```

#### 2. **Match Tracking System** (PRIORITY: HIGH)
Complete CRUD operations for match management.

**Core Features**:
- ✅ **Create Match**
  - Select two players from database
  - Record match scores (sets, games, tiebreaks)
  - Match metadata (date, location, tournament/casual)
  - Surface type (hard, clay, grass)
- ✅ **Read/View Matches**
  - Match details with player names and ratings
  - Historical match lookup
  - Filter by player, date range, tournament
- ✅ **Update Match**
  - Correct score errors
  - Add missing metadata
  - Admin override for disputes
- ✅ **Delete Match**
  - Soft delete with audit trail
  - Rating recalculation on deletion
  - Admin-only operation

**Match Validation**:
- Both players must exist in database
- Both players must use same rating system for the match
- Score validation (legal tennis scores)
- Duplicate match prevention (same players, same date)

**Database Schema (Proposed)**:
```
matches
  - id (UUID)
  - player1_id (FK → players.id)
  - player2_id (FK → players.id)
  - match_date (Date)
  - location (String)
  - surface (Enum: HARD|CLAY|GRASS|INDOOR)
  - tournament_id (FK → tournaments.id, nullable)
  - match_type (Enum: CASUAL|TOURNAMENT|LEAGUE)
  - rating_system_used (Enum: NTRP|UTR)
  - status (Enum: PENDING|CONFIRMED|DISPUTED|DELETED)
  - created_at (Timestamp)
  - updated_at (Timestamp)

match_scores
  - id (UUID)
  - match_id (FK → matches.id)
  - set_number (Integer)
  - player1_games (Integer)
  - player2_games (Integer)
  - tiebreak_player1 (Integer, nullable)
  - tiebreak_player2 (Integer, nullable)
  - winner_id (FK → players.id)
```

#### 3. **Player Ranking System** (PRIORITY: HIGH)
Dynamic ranking table with historical tracking.

**Core Features**:
- ✅ **Dynamic Rating Updates**
  - Automatic rating recalculation on match confirmation
  - Published level updates (immediate in v1, scheduled in v2)
  - Rating history tracking
- ✅ **Leaderboard/Rankings Table**
  - Current rankings for all active players
  - Filter by rating system (NTRP/UTR)
  - Filter by published level (e.g., all 4.5 NTRP players)
  - Sort by rating, win percentage, recent activity
- ✅ **Player Statistics**
  - Win/loss record
  - Winning percentage
  - Average match dominance
  - Upset wins/losses
  - Rating trend (gaining/losing points)
- ✅ **Rating History**
  - Historical ratings over time
  - Rating graph visualization
  - Milestone tracking (level changes)

**Database Schema (Proposed)**:
```
ratings_history
  - id (UUID)
  - player_id (FK → players.id)
  - match_id (FK → matches.id, nullable for manual adjustments)
  - rating_system (Enum: NTRP|UTR)
  - previous_rating (Decimal)
  - new_rating (Decimal)
  - rating_change (Decimal)
  - previous_published_level (String)
  - new_published_level (String)
  - level_changed (Boolean)
  - reason (Enum: MATCH_WIN|MATCH_LOSS|ADMIN_ADJUSTMENT|SEASON_RESET)
  - created_at (Timestamp)

player_statistics
  - player_id (FK → players.id)
  - rating_system (Enum: NTRP|UTR)
  - matches_played (Integer)
  - wins (Integer)
  - losses (Integer)
  - win_percentage (Decimal)
  - upset_wins (Integer)
  - upset_losses (Integer)
  - average_dominance (Decimal)
  - current_streak (Integer, can be negative)
  - updated_at (Timestamp)
```

#### 4. **System Integration** (PRIORITY: HIGH)
All MVP components working together.

**Key Integration Points**:
- Match creation triggers rating recalculation
- Rating updates automatically update rankings
- Player verification status affects tournament eligibility
- Statistics update in real-time with match results

### 🔮 Post-MVP Features (Future)

These features will be considered after MVP is achieved:

- **Tournament Management**: Create and manage tournaments with brackets
- **League/Season Support**: Seasonal ratings with resets
- **Mobile App**: iOS/Android apps for match recording
- **Social Features**: Friend lists, challenge system, activity feed
- **Advanced Analytics**: Predictive modeling, strength of schedule, trend analysis
- **Admin Dashboard**: Management interface for verification, disputes, data cleanup
- **Email Notifications**: Match confirmations, rating changes, tournament invites
- **Multi-language Support**: Tagalog, English, other Philippine languages
- **Payment Integration**: Tournament fees, membership dues (GCash, PayMaya)
- **SMS Verification**: Phone number verification for Philippine users

## Technology Stack

### Current (v0.1)
- **Language**: Kotlin 2.2.21
- **Web Framework**: Ktor 3.0.3 (Netty server)
- **Serialization**: kotlinx.serialization (JSON)
- **Build Tool**: Gradle 9.5.1
- **Code Quality**: ktlint + Detekt
- **Testing**: JUnit 5 + Kotest assertions
- **Coverage**: JaCoCo
- **Monitoring**: Micrometer + Prometheus
- **API Docs**: Swagger UI + OpenAPI 3.0

### Planned for MVP
- **Database**: PostgreSQL (primary candidate)
  - Alternative: MySQL/MariaDB
- **ORM**: Exposed (Kotlin SQL framework)
  - Alternative: jOOQ, Hibernate
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
docker build -t tennis-levelr .

# Run the container
docker run -d -p 8080:8080 --name tennis-levelr tennis-levelr

# View logs
docker logs -f tennis-levelr

# Stop the container
docker stop tennis-levelr
```

#### Option 3: Using the helper script
```bash
# Build with version tag
./scripts/docker-build.sh 1.0.0

# Run with Docker
docker run -d -p 8080:8080 tennis-levelr:1.0.0
```

See [docs/DOCKER_DEPLOYMENT.md](docs/DOCKER_DEPLOYMENT.md) for comprehensive Docker deployment guide.

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
| `check-coverage.sh` | Run tests and verify 85% coverage threshold |

See `scripts/README.md` for detailed documentation.

## API Endpoints

### Current Endpoints

| Method | Endpoint | Description | Response |
|--------|----------|-------------|----------|
| GET | `/` | Root endpoint | `Tennis Levelr API` |
| GET | `/health` | Health check | JSON with status and version |
| GET | `/metrics` | Prometheus metrics | Metrics in Prometheus format |
| GET | `/swagger` | Swagger UI | Interactive API documentation |
| GET | `/openapi.yaml` | OpenAPI specification | Raw OpenAPI spec (YAML) |
| POST | `/api/v1/calculate-ranking` | Calculate player rankings | JSON with updated ratings |

### API Documentation

- **Interactive**: Visit `/swagger` for Swagger UI (try API calls in browser)
- **Machine-readable**: Download `/openapi.yaml` for code generation and tooling
- **Detailed specs**: See [docs/API_DOCUMENTATION.md](docs/API_DOCUMENTATION.md)

## How Ratings Are Calculated

Tennis Levelr uses a **performance-based rating system** with normalized gaps to ensure fair calculations across different rating systems. The algorithm considers **how dominantly** you won and whether the match was an **upset** or **expected outcome**.

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
   - At threshold (0.5 NTRP, 1.25 UTR): Zero change
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

**Competitive Threshold**: 8.3% of rating range (~1/12)
- NTRP: 0.5 points (e.g., 4.0 vs 4.5)
- UTR: 1.25 points (e.g., 10.0 vs 11.25)
- Matches within this threshold produce performance-based changes
- Matches beyond this threshold (expected outcomes) produce zero change

**Dominance Factor**: Based on game margin, not ratio
- Formula: (games won - games lost) / (games won + games lost)
- 6-0 = 1.0 dominance (maximum)
- 6-4 = 0.2 dominance
- 7-6 = 0.077 dominance (very close)

**K-Factor Scaling**:
- NTRP: K = 0.16 (typical changes ±0.032 to ±0.160)
- UTR: K = 0.4 (2.5× larger, proportional to range)

### Rating Smoothing (Optional)

Tennis Levelr supports **USTA NTRP Dynamic-style rating smoothing** to create more stable and predictable ratings:

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
  "players": { ... },
  "matchScore": { ... },
  "options": {
    "smoothingEnabled": true,
    "smoothingFactor": 0.5
  }
}
```

See **[RATING_SMOOTHING.md](docs/RATING_SMOOTHING.md)** for complete documentation with examples and best practices.

### Rating Boundaries
- **NTRP**: 1.0 (beginner) to 7.0 (world-class)
- **UTR**: 1.0+ (no upper limit, but 16+ is pro level)

### Want More Details?
- **[RATING_SMOOTHING.md](docs/RATING_SMOOTHING.md)** - Complete rating smoothing guide with examples and best practices
- **[ALGORITHM_BEHAVIOR.md](docs/ALGORITHM_BEHAVIOR.md)** - Complete algorithm explanation with formulas, edge cases, and technical details

## Documentation

Comprehensive documentation is available in the `docs/` directory:

- **[API_DOCUMENTATION.md](docs/API_DOCUMENTATION.md)** - Complete API reference
  - Endpoint specifications
  - Request/response formats
  - Data models and validation rules
  - Examples and error codes

- **[RATING_SMOOTHING.md](docs/RATING_SMOOTHING.md)** - Rating smoothing algorithm (NEW)
  - USTA NTRP Dynamic-style smoothing explained
  - Mathematical formulas and examples
  - Smoothing factor recommendations (0.3, 0.5, 0.7)
  - Usage guide and best practices
  - UTR vs NTRP smoothing behavior
  - Performance and backward compatibility

- **[ALGORITHM_BEHAVIOR.md](docs/ALGORITHM_BEHAVIOR.md)** - Complete algorithm behavior guide
  - Performance-based Elo system overview
  - Five adjustment cases explained with examples
  - Edge cases and special handling
  - Magic constant explanations
  - Known limitations and test coverage

- **[RANKING_ALGORITHM.md](docs/RANKING_ALGORITHM.md)** - Original algorithm design
  - Elo-based system explanation
  - Mathematical formulas
  - Parameter tuning guidelines
  - Examples and limitations

- **[AUDIT_TRAIL.md](docs/AUDIT_TRAIL.md)** - Audit trail design
  - Monadic pattern explanation
  - Pure function benefits
  - Testing without mocking
  - Usage examples

- **[TESTING_STRATEGY.md](docs/TESTING_STRATEGY.md)** - Testing pyramid and strategy
  - Test organization (89 tests including smoothing tests)
  - Unit vs integration tests
  - Pure function testing
  - Coverage goals and best practices

- **[CODE_COVERAGE.md](docs/CODE_COVERAGE.md)** - Code coverage guide
  - JaCoCo configuration
  - Coverage reports (HTML/XML)
  - Current metrics (~79% lines, ~75% branches)
  - CI/CD integration

## Testing

Tennis Levelr uses a comprehensive testing strategy with **69 tests** across unit, integration, and edge case layers:

### Test Distribution

```
Unit Tests (38):        55% - Fast, isolated, pure function testing
Integration Tests (21): 30% - API contracts, HTTP layer
Edge Case Tests (10):   15% - Algorithm behavior validation
Total:                  69 tests in ~6 seconds
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
./gradlew test --tests "org.lange.tennis.levelr.service.*"

# Run specific test class
./gradlew test --tests "RankingCalculatorUnitTest"

# Check coverage against 85% threshold
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
- **High Coverage**: ~85% line coverage, ~80% branch coverage

See [TESTING_STRATEGY.md](docs/TESTING_STRATEGY.md) for complete details.

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

TBD
