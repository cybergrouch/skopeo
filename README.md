# Tennis Levelr

A Ktor API for dynamic calculation of tennis rankings based on match results.

## Overview

Tennis Levelr provides real-time ranking calculations for tennis players using either NTRP (National Tennis Rating Program) or UTR (Universal Tennis Rating) systems. The API calculates updated rankings for both players based on match outcomes, deriving expected results from the difference in their current rankings.

## Features

### Current Functionality

- **Dynamic Ranking Calculation**: Calculate updated player rankings based on match results
- **Multiple Rating Systems**: Support for both NTRP and UTR ranking systems
- **Match-based Updates**: Rankings are calculated using:
  - Current rankings of both players (NTRP or UTR)
  - Match scores
  - Expected outcome based on ranking differential

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
| GET | `/health` | Health check | `OK` |

### Coming Soon

- `POST /calculate-ranking` - Calculate new player rankings based on match results

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
