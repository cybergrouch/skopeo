# Tennis Levelr

A Spring Boot API for dynamic calculation of tennis rankings based on match results.

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

- Kotlin
- Spring Boot
- Gradle

## Getting Started

```bash
./gradlew bootRun
```

## License

TBD
