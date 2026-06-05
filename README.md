# Tennis Levelr

A Ktor API for dynamic calculation of tennis rankings based on match results.

## Overview

Tennis Levelr provides real-time ranking calculations for tennis players using either NTRP (National Tennis Rating Program) or UTR (Universal Tennis Rating) systems. The API calculates updated rankings for both players based on match outcomes, deriving expected results from the difference in their current rankings.

## Features

### Current Functionality

- **Dynamic Ranking Calculation**: Calculate updated player rankings based on match results
- **Elo-Based Algorithm**: Sophisticated ranking system that considers:
  - Rating differential between players
  - Match dominance (games won ratio)
  - Expected vs actual performance
- **Multiple Rating Systems**: Support for both NTRP and UTR ranking systems
  - NTRP: 1.0-7.0 range with continuous decimal values
  - UTR: 1.0+ range with continuous decimal values
- **Comprehensive Validation**: Input validation for player profiles, ratings, and match scores

### Input

The API accepts match data including:
- Player 1 current ranking (NTRP or UTR)
- Player 2 current ranking (NTRP or UTR)
- Match scores

### Output

The API returns:
- Player 1 updated ranking
- Player 2 updated ranking

## Limitations

The current version does **not** persist:
- Player profiles
- Historical rankings
- Match results

## Roadmap

Future enhancements include:
- Player profile storage
- Match result persistence
- Historical ranking tracking
- Statistical analysis and trends

## Technology Stack

- Kotlin 2.2.21
- Ktor 3.0.3 (Netty server)
- Gradle 9.5.1
- ktlint (code formatting)

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

See `scripts/README.md` for detailed documentation.

## API Endpoints

### Current Endpoints

| Method | Endpoint | Description | Response |
|--------|----------|-------------|----------|
| GET | `/` | Root endpoint | `Tennis Levelr API` |
| GET | `/health` | Health check | JSON with status and version |
| GET | `/metrics` | Prometheus metrics | Metrics in Prometheus format |
| POST | `/api/v1/calculate-ranking` | Calculate player rankings | JSON with updated ratings |

See [docs/API_DOCUMENTATION.md](docs/API_DOCUMENTATION.md) for detailed API specifications.

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

Tennis Levelr uses a comprehensive testing strategy with **79 tests** across unit, integration, and edge case layers:

### Test Distribution

```
Unit Tests (40):        51% - Fast, isolated, pure function testing
Integration Tests (27): 34% - API contracts, HTTP layer
Edge Case Tests (12):   15% - Algorithm behavior validation
Total:                  79 tests in ~6 seconds
```

### Running Tests

```bash
# Run all tests (automatically generates coverage report)
./gradlew test

# Run unit tests only (fast)
./gradlew test --tests "org.lange.tennis.levelr.service.*"

# Run specific test class
./gradlew test --tests "RankingCalculatorUnitTest"

# Verify coverage thresholds
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
