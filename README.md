# Tennis Levelr

A Ktor API for dynamic calculation of tennis rankings based on match results.

## Overview

Tennis Levelr provides real-time ranking calculations for tennis players using either NTRP (National Tennis Rating Program) or UTR (Universal Tennis Rating) systems. The API calculates updated rankings for both players based on match outcomes, deriving expected results from the difference in their current rankings.

## Features

### Current Functionality

- **Dynamic Ranking Calculation**: Calculate updated player rankings based on match results
- **ELO-Based Algorithm**: Sophisticated ranking system that considers:
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

## Documentation

Comprehensive documentation is available in the `docs/` directory:

- **[API_DOCUMENTATION.md](docs/API_DOCUMENTATION.md)** - Complete API reference
  - Endpoint specifications
  - Request/response formats
  - Data models and validation rules
  - Examples and error codes

- **[RANKING_ALGORITHM.md](docs/RANKING_ALGORITHM.md)** - Ranking algorithm details
  - ELO-based system explanation
  - Mathematical formulas
  - Parameter tuning guidelines
  - Examples and limitations

- **[AUDIT_TRAIL.md](docs/AUDIT_TRAIL.md)** - Audit trail design
  - Monadic pattern explanation
  - Pure function benefits
  - Testing without mocking
  - Usage examples

- **[TESTING_STRATEGY.md](docs/TESTING_STRATEGY.md)** - Testing pyramid and strategy
  - Test organization (70 tests)
  - Unit vs integration tests
  - Pure function testing
  - Coverage goals and best practices

- **[CODE_COVERAGE.md](docs/CODE_COVERAGE.md)** - Code coverage guide
  - JaCoCo configuration
  - Coverage reports (HTML/XML)
  - Current metrics (~79% lines, ~75% branches)
  - CI/CD integration

## Testing

Tennis Levelr uses a comprehensive testing strategy with **70 tests** across unit and integration layers:

### Test Distribution

```
Unit Tests (40):        57% - Fast, isolated, pure function testing
Integration Tests (30): 43% - API contracts, HTTP layer
Total:                  70 tests in ~5.5 seconds
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

This project uses ktlint for consistent code formatting. Before committing:

```bash
# Check for style violations
./gradlew ktlintCheck

# Auto-fix style violations
./gradlew ktlintFormat
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
