# Skopeo

A Ktor API for dynamic calculation of tennis rankings based on match results.

## Overview

Skopeo provides real-time ranking calculations for tennis players using either NTRP (National Tennis Rating Program) or UTR (Universal Tennis Rating) systems. The API calculates updated rankings for both players based on match outcomes, deriving expected results from the difference in their current rankings.

## Features

### ✅ Implemented Features (v0.1)

#### 1. **Rating Calculation Engine** (Core)
The heart of the Skopeo system - a sophisticated performance-based rating calculator.

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

- **Test Suite**: 123 automated tests
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
      { "games": { "P1": 6, "P2": 2 }, "winnerTeamId": "P1" }
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

## Product Roadmap

Skopeo's evolution from a **stateless rating calculator** to a **comprehensive player ranking platform** with advanced features for the Philippine tennis community.

### Feature Overview Table

| # | Feature | Priority | Status | Dependencies | Description |
|---|---------|----------|--------|--------------|-------------|
| **CORE SYSTEM (IMPLEMENTED)** |
| 1 | Rating Calculation Engine | ✅ DONE | Implemented | None | Performance-based Elo calculator with NTRP/UTR support |
| 2 | REST API | ✅ DONE | Implemented | #1 | HTTP API with JSON serialization |
| 3 | API Documentation | ✅ DONE | Implemented | #2 | Swagger UI + OpenAPI 3.0 spec |
| 4 | Quality Assurance | ✅ DONE | Implemented | All | 123 tests, coverage, code quality tools |
| **MVP REQUIREMENTS (REQUIRED FOR PRODUCTION)** |
| 5 | Player Profile Management | 🔴 CRITICAL | Not Started | Database | CRUD operations for player profiles with photos |
| 6 | Player Identity Verification (KYC) 🇵🇭 | 🔴 CRITICAL | Not Started | #5 | Philippine government ID validation (Passport, DL, UMID, SSS, GSIS, National ID) |
| 6a | Social Media Verification | 🟡 NICE-TO-HAVE | Not Started | #6 | Automated verification via social media accounts (Facebook, Instagram, Twitter) |
| 7 | Match Tracking System | 🔴 CRITICAL | Not Started | #5, Database | CRUD for matches with score validation and audit trail |
| 8 | Player Ranking System | 🔴 CRITICAL | Not Started | #5, #7, #1 | Dynamic rankings, leaderboards, statistics, rating history |
| 9 | System Integration | 🔴 CRITICAL | Not Started | #5-#8 | Real-time updates across all MVP components |
| **NICE-TO-HAVE FEATURES (ENHANCE MVP)** |
| 10 | Seeding Generation | 🟡 NICE-TO-HAVE | Not Started | #8 | Auto-generate tournament seedings from current rankings |
| 11 | UTR Integration | 🟡 NICE-TO-HAVE | Not Started | #8, External API | Sync with official UTR ratings for cross-validation |
| 12 | Dynamic Rating Confidence | 🟡 NICE-TO-HAVE | Not Started | #8 | Time-based confidence score for ratings (accounts for player inactivity) |
| **POST-MVP FEATURES (FUTURE ENHANCEMENTS)** |
| 13 | Doubles Support | 🟢 FUTURE | Not Started | #7, #8 | Support for doubles matches (2v2) with team ratings |
| 14 | Tournament Management | 🟢 FUTURE | Not Started | #8, #10 | Create and manage tournaments with brackets |
| 15 | League/Season Support | 🟢 FUTURE | Not Started | #8 | Seasonal ratings with resets and historical tracking |
| 16 | Mobile Apps | 🟢 FUTURE | Not Started | All APIs | iOS/Android apps for match recording |
| 17 | Social Features | 🟢 FUTURE | Not Started | #5 | Friend lists, challenge system, activity feed |
| 18 | Advanced Analytics | 🟢 FUTURE | Not Started | #8 | Predictive modeling, strength of schedule, trend analysis |
| 19 | Admin Dashboard | 🟢 FUTURE | Not Started | All | Management interface for verification, disputes, cleanup |
| 20 | Email Notifications | 🟢 FUTURE | Not Started | #7, #14 | Match confirmations, rating changes, tournament invites |
| 21 | Multi-language Support | 🟢 FUTURE | Not Started | All | Tagalog, English, other Philippine languages |
| 22 | Payment Integration 🇵🇭 | 🟢 FUTURE | Not Started | #14 | GCash, PayMaya for tournament fees and membership |
| 23 | SMS Verification 🇵🇭 | 🟢 FUTURE | Not Started | #5 | Phone number verification for Philippine users |

**Priority Legend:**
- 🔴 **CRITICAL**: Required for MVP launch
- 🟡 **NICE-TO-HAVE**: Enhances MVP, recommended before full production
- 🟢 **FUTURE**: Post-MVP enhancements

### 🎯 MVP Feature Set (Detailed)

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

#### 4. **System Integration** (PRIORITY: CRITICAL)
All MVP components working together.

**Key Integration Points**:
- Match creation triggers rating recalculation
- Rating updates automatically update rankings
- Player verification status affects tournament eligibility
- Statistics update in real-time with match results

### 🟡 Nice-to-Have Features (Recommended Before Full Production)

#### 5. **Seeding Generation** (PRIORITY: NICE-TO-HAVE)
Automated tournament seeding based on current dynamic rankings.

**Core Features**:
- ✅ **Automatic Seeding Lists**
  - Generate ordered seeding list from player rankings
  - Support for different tournament formats (single elimination, round-robin, etc.)
  - Configurable seeding rules (strict rating order, geographic distribution, etc.)
- ✅ **Real-time Updates**
  - Seedings reflect latest rating changes
  - Re-seeding capabilities for late registrations
  - Handle tie-breaking scenarios (equal ratings)
- ✅ **Export Capabilities**
  - PDF export for tournament directors
  - CSV export for tournament software integration
  - Bracket visualization with seeded positions

**Use Cases**:
- Tournament directors can instantly generate fair seedings
- Eliminates manual ranking lookups and calculations
- Ensures competitive balance in tournament draws
- Reduces seeding disputes with transparent algorithm

**Algorithm Considerations**:
```
Seeding Order:
1. Sort by dynamic rating (descending)
2. For ties: use confidence value (higher confidence = better seed)
3. For still tied: use total matches played (more matches = better seed)
4. For still tied: use win percentage
5. Last resort: random assignment
```

#### 6a. **Social Media Verification** (Sub-feature of KYC)
Automated player verification through social media account validation.

**Core Features**:
- ✅ **Supported Platforms**
  - Facebook (most popular in Philippines)
  - Instagram (photo verification)
  - Twitter/X (identity confirmation)
  - LinkedIn (professional players)
- ✅ **Verification Methods**
  - OAuth integration for account ownership proof
  - Profile data matching (name, photo, location)
  - Account age and activity verification
  - Friend/follower count thresholds (anti-fake account)
- ✅ **Verification Levels**
  - Basic: Account ownership confirmed
  - Standard: Profile data matches player profile
  - Enhanced: Multiple platforms verified + high activity

**Benefits**:
- Complements government ID verification
- Faster verification for casual players
- Additional fraud prevention layer
- Community trust building

**Privacy Considerations**:
- Players opt-in to social media verification
- Only public profile data accessed
- No posting capabilities requested
- Clear data usage policy

#### 7. **UTR Integration** (PRIORITY: NICE-TO-HAVE)
Integration with Universal Tennis Rating (UTR) official system.

**Core Features**:
- ✅ **UTR API Integration**
  - Fetch official UTR ratings for registered players
  - Periodic sync (daily/weekly) to keep ratings current
  - Player matching by name, birthdate, location
- ✅ **Cross-Validation**
  - Compare Skopeo UTR vs Official UTR
  - Highlight significant discrepancies (>0.5 difference)
  - Use official UTR as baseline for new players
- ✅ **Hybrid Rating Display**
  - Show both Skopeo rating and Official UTR
  - Indicate last sync date
  - Allow players to choose which rating to use for tournaments
- ✅ **Rating Conversion**
  - Map official UTR to NTRP equivalent
  - Help players understand their level across systems

**Use Cases**:
- Players with official UTR can import their rating
- Tournament directors can verify player levels
- Cross-system validation increases rating credibility
- Appeals process can reference official UTR

**API Requirements**:
- UTR API key and subscription
- Player consent for data sharing
- Rate limiting handling
- Fallback when API unavailable

**Database Schema Addition**:
```
utr_sync_history
  - player_id (FK → players.id)
  - official_utr_rating (Decimal)
  - skopeo_utr (Decimal)
  - rating_difference (Decimal)
  - sync_date (Timestamp)
  - match_confidence (Enum: HIGH|MEDIUM|LOW)
```

#### 8. **Dynamic Rating Confidence Value** (PRIORITY: NICE-TO-HAVE)
Time-based confidence scoring for dynamic ratings to account for player inactivity.

**Core Features**:
- ✅ **Confidence Score Calculation**
  - Formula: `confidence = base_confidence × activity_factor × recency_factor`
  - Base confidence: Based on number of matches (minimum 10 for 100%)
  - Activity factor: Matches in last 90 days vs total matches
  - Recency factor: Time since last match (decays over time)
- ✅ **Confidence Levels**
  - 🟢 **HIGH** (90-100%): Active player, rating is reliable
    - 10+ matches, last match within 30 days
  - 🟡 **MEDIUM** (70-89%): Moderately active, rating mostly reliable
    - 5-9 matches or last match 31-90 days ago
  - 🟠 **LOW** (50-69%): Inactive player, rating uncertain
    - <5 matches or last match 91-180 days ago
  - 🔴 **VERY LOW** (<50%): Highly inactive, rating unreliable
    - Last match >180 days ago
- ✅ **Confidence Decay Algorithm**
  ```
  recency_factor = 1.0 - (days_since_last_match / 365)
  min_recency_factor = 0.3  // Never goes below 30%

  activity_factor = min(matches_last_90_days / 5, 1.0)
  // 5+ matches in 90 days = 100% activity factor

  base_confidence = min(total_matches / 10, 1.0)
  // 10+ lifetime matches = 100% base confidence

  final_confidence = base_confidence × activity_factor × max(recency_factor, 0.3)
  ```

**Visual Indicators**:
- Display confidence badge next to rating
- Color-coded confidence levels in leaderboards
- Tooltip with last match date and match count
- Warning for low-confidence ratings in seeding

**Use Cases**:
- Tournament directors can see which ratings are current
- Returning players after long absence have lower confidence
- Helps identify players who need re-rating matches
- Fairer seeding by considering rating reliability

**Impact on Seeding**:
- In tie-breaking scenarios, higher confidence wins
- Low confidence ratings can trigger "provisional" status
- Suggested: require 1-2 re-rating matches for <50% confidence

**Database Schema Addition**:
```
ALTER TABLE player_statistics ADD COLUMN:
  - rating_confidence (Decimal) // 0.0 to 1.0
  - confidence_level (Enum: VERY_LOW|LOW|MEDIUM|HIGH)
  - last_confidence_update (Timestamp)
  - matches_last_90_days (Integer)
```

**Benefits**:
- More accurate tournament seedings
- Identifies stale ratings
- Encourages player activity
- Transparent rating reliability

### 🔮 Post-MVP Features (Future Enhancements)

These features will be considered after MVP and nice-to-have features are implemented:

#### 1. **Doubles Support** (#13) 🎾
Support for doubles matches (2 vs 2) with team-based rating calculations.

**⚠️ Design Implications for Current Match Model**

This feature has **critical implications** for how matches are currently represented in the database. To support doubles in the future, the match tracking system (#7) should be designed with flexibility in mind.

**Core Features**:
- ✅ **Match Type Support**
  - Singles (1v1) - current implementation
  - Doubles (2v2) - future support
  - Mixed Doubles (male + female pairs)
- ✅ **Team Formation**
  - Two players form a team
  - Team selection during match creation
  - Partner history tracking
- ✅ **Doubles Rating System**
  - Separate doubles rating per player (distinct from singles)
  - Team rating calculation (average of partners or combined formula)
  - Partner chemistry factor (optional enhancement)
- ✅ **Match Results**
  - Team 1 vs Team 2 scoring
  - Individual player statistics within team context
  - Win/loss records for both individual and team

**Rating Calculation Approaches**:

*Option 1: Individual Doubles Ratings*
```
Each player has:
- Singles rating (independent)
- Doubles rating (independent)

Match outcome affects each player's doubles rating individually
Team rating = average of both partners' doubles ratings
```

*Option 2: Team-Based Ratings*
```
Rating assigned to player pairs (teams)
Players can have different ratings with different partners
More complex but accounts for partner chemistry
```

**Recommended Approach**: Option 1 (Individual Doubles Ratings)
- Simpler to implement and understand
- Players maintain consistent doubles rating regardless of partner
- Similar to how USTA and UTR handle doubles
- Easier migration path from singles-only system

**Database Schema Implications** (Design Considerations for #7):

**Current Match Model** (Singles-focused):
```sql
matches
  - player1_id (FK)
  - player2_id (FK)
  - winner_id (FK)
```

**Recommended Future-Proof Design**:
```sql
matches
  - id (UUID)
  - match_type (Enum: SINGLES|DOUBLES|MIXED_DOUBLES)
  - match_date (Date)
  - location, surface, etc.

match_participants
  - match_id (FK → matches.id)
  - player_id (FK → players.id)
  - team_number (Integer: 1 or 2)
  - position (Integer: 1 or 2, for doubles only)
  - is_winner (Boolean)

-- This design supports:
-- Singles: 2 participants (team_number = 1 or 2, position = 1)
-- Doubles: 4 participants (team_number = 1 or 2, position = 1 or 2)
```

**Rating Storage Implications**:
```sql
players
  - current_rating_ntrp_singles (Decimal)
  - current_rating_ntrp_doubles (Decimal)
  - current_rating_utr_singles (Decimal)
  - current_rating_utr_doubles (Decimal)

ratings_history
  - rating_type (Enum: SINGLES|DOUBLES)
  -- existing columns
```

**Migration Path**:
1. **Phase 1** (MVP): Build singles-only with flexible schema
2. **Phase 2** (Future): Add doubles support without breaking changes
3. Existing singles matches remain valid
4. New match_type field defaults to SINGLES for backward compatibility

**UI/UX Considerations**:
- Match creation: Select "Singles" or "Doubles"
- For doubles: Select 4 players instead of 2
- Leaderboards: Separate tabs for Singles and Doubles rankings
- Player profiles: Show both singles and doubles ratings

**Statistics Tracking**:
- Separate win/loss records for singles and doubles
- Partner statistics (most common partners, win rate with each)
- Performance comparison (singles vs doubles rating differential)

**Use Cases**:
- Tournament directors can run both singles and doubles events
- Players track their performance in both formats
- Clubs can organize doubles leagues
- Partner matching based on compatible ratings

**Benefits**:
- Complete tennis experience (singles + doubles)
- More engagement opportunities for players
- Aligns with real-world tennis tournaments
- Separate skill tracking for different game formats

**Implementation Priority**: Future (after MVP)
- MVP focuses on singles to validate core rating algorithm
- Doubles adds complexity that should come after singles is proven
- However, match model should be designed with doubles in mind

---

**Other Post-MVP Features**:

- **Tournament Management** (#14): Create and manage tournaments with brackets
- **League/Season Support** (#15): Seasonal ratings with resets
- **Mobile Apps** (#16): iOS/Android apps for match recording
- **Social Features** (#17): Friend lists, challenge system, activity feed
- **Advanced Analytics** (#18): Predictive modeling, strength of schedule, trend analysis
- **Admin Dashboard** (#19): Management interface for verification, disputes, data cleanup
- **Email Notifications** (#20): Match confirmations, rating changes, tournament invites
- **Multi-language Support** (#21): Tagalog, English, other Philippine languages
- **Payment Integration** (#22) 🇵🇭: Tournament fees, membership dues (GCash, PayMaya)
- **SMS Verification** (#23) 🇵🇭: Phone number verification for Philippine users

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
| GET | `/` | Root endpoint | `Skopeo API` |
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
- Per-set formula: (games won - games lost) / (games won + games lost)
- Match dominance = average of the per-set dominances (a lost set counts as a negative term)
- 6-0 = 1.0 dominance (maximum)
- 6-4 = 0.2 dominance
- 7-6 = 0.077 dominance (very close)
- 6-0, 3-6, 6-2 = (1.0 - 0.333 + 0.5) / 3 = 0.389 dominance

**K-Factor Scaling**:
- NTRP: K = 0.16 (typical changes ±0.032 to ±0.160)
- UTR: K = 0.4 (2.5× larger, proportional to range)

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
- **[RATING_CALCULATION_ALGORITHM.md](docs/RATING_CALCULATION_ALGORITHM.md)** - Complete algorithm explanation with formulas, edge cases, and technical details

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

- **[RATING_CALCULATION_ALGORITHM.md](docs/RATING_CALCULATION_ALGORITHM.md)** - Complete algorithm behavior guide
  - Performance-based Elo system overview
  - Five adjustment cases explained with examples
  - Edge cases and special handling
  - Magic constant explanations
  - Known limitations and test coverage

- **[AUDIT_TRAIL.md](docs/AUDIT_TRAIL.md)** - Audit trail design
  - Monadic pattern explanation
  - Pure function benefits
  - Testing without mocking
  - Usage examples

- **[TESTING_STRATEGY.md](docs/TESTING_STRATEGY.md)** - Testing pyramid and strategy
  - Test organization and pyramid
  - Unit vs integration tests
  - Pure function testing
  - Coverage goals and best practices

- **[CODE_COVERAGE.md](docs/CODE_COVERAGE.md)** - Code coverage guide
  - JaCoCo configuration
  - Coverage reports (HTML/XML)
  - Current metrics (~79% lines, ~75% branches)
  - CI/CD integration

- **[JVM_COMPATIBILITY.md](docs/JVM_COMPATIBILITY.md)** - JVM version strategy
  - Build failure investigation (detekt vs Java 25+)
  - Gradle daemon pinned to Java 21 LTS and why
  - GCP/AWS Java runtime support survey
  - Upgrade path when detekt 2.0 ships

- **[DEPLOYMENT_GCP.md](docs/DEPLOYMENT_GCP.md)** - Cloud deployment guide
  - Platform decision: GCP (Cloud Run + Cloud SQL) vs AWS, with costs
  - Step-by-step deployment of the API and PostgreSQL
  - Day-2 operations, scaling path, and teardown

- **[WEB_UI_ARCHITECTURE.md](docs/WEB_UI_ARCHITECTURE.md)** - Web UI decisions & roadmap
  - Decoupled frontend, monorepo `web/` layout
  - SPA vs SSR analysis; recommended tech stack (SPA + PWA + Capacitor path)
  - Authentication approach (token-based via Firebase Auth, verified at the API)

- **[CICD.md](docs/CICD.md)** - CI/CD plan (GitHub Actions)
  - Phase 1: CI gate (`./gradlew check`) + branch protection for the PR workflow
  - Phase 2: keyless Cloud Run deploys via Workload Identity Federation
  - Phase 3: web UI CI/CD (Firebase Hosting, path-filtered)

## Testing

Skopeo uses a comprehensive testing strategy with **123 tests** across unit and integration layers:

### Test Distribution

```
Unit Tests (107):       87% - Fast, isolated, pure function testing
Integration Tests (16): 13% - API contracts, HTTP layer
Total:                  123 tests in ~6 seconds
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
