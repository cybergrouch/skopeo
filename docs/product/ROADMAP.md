# Product Roadmap

> Extracted from the root `README.md` so the README stays focused on what exists today. This captures Skopeo's evolution from a stateless rating calculator toward a full ranking platform. Shipped items are marked ✅; the schemas in the "Detailed"/"Future" sections are **proposals**, not necessarily the built shape — see [database-schema.md](../engineering/architecture/database-schema.md) for the actual schema.

Skopeo's evolution from a **stateless rating calculator** to a **comprehensive player ranking platform** with advanced features for the Philippine tennis community.

### Feature Overview Table

| # | Feature | Priority | Status | Dependencies | Description |
|---|---------|----------|--------|--------------|-------------|
| **CORE SYSTEM (IMPLEMENTED)** |
| 1 | Rating Calculation Engine | ✅ DONE | Implemented | None | Performance-based Elo calculator (NTRP only; UTR removed) |
| 2 | REST API | ✅ DONE | Implemented | #1 | HTTP API with JSON serialization |
| 3 | API Documentation | ✅ DONE | Implemented | #2 | Swagger UI + OpenAPI 3.0 spec |
| 4 | Quality Assurance | ✅ DONE | Implemented | All | Extensive JUnit/Kotest suite, coverage (75% line / 70% branch), code quality tools |
| 5 | Player Profile Management | ✅ DONE | Implemented | Database | Capability-gated profiles; sex + date of birth required at sign-up |
| 6 | Match Tracking System | ✅ DONE | Implemented | #5, Database | Match fixtures + result upload with score validation |
| 7 | Rating Persistence | ✅ DONE | Implemented | #5, #6, #1 | Admin-set initial ratings, calculation trigger (dry-run/commit), rating history |
| 8 | Web UI | ✅ DONE | Implemented | #5-#7 | Sign-up + capability-gated dashboard (Profile / Settings / Research / Standings / Claim / Event Organizer / Seeding / Placeholder Players / Ratings / Invites / Activity Log / Reports / Points Management / Admin / About) |
| **PLATFORM & GOVERNANCE (IMPLEMENTED)** |
| 24 | Capability-Based Authorization | ✅ DONE | Implemented | #5 | Role gates: PLAYER, HOST, CLUB_OWNER, ADMINISTRATOR, RATER (#106), RESEARCHER (#107) |
| 25 | Event Organizer | ✅ DONE | Implemented | #6, #8 | Host-run events/meets: participants + participant-scoped fixtures; results editable until rated, then read-only (#138) |
| 26 | Public Pages + QR Sharing | ✅ DONE | Implemented | #5, #6 | Shareable code pages for players / matches / events, with QR (#56 / #136 / #137 / #138) |
| 27 | Player Search & Research | ✅ DONE | Implemented | #5 | Accent-insensitive name/code search with sex/age/rating filters; Research tab (#86 / #87 / #107) |
| 28 | Audit / Activity Log | ✅ DONE | Implemented | #5 | Append-only provenance of domain actions + admin activity viewer (#100 / #102) |
| 29 | Duplicate Detection & Rectification | ✅ DONE | Implemented | #5 | Auto/manual duplicate-candidate flagging + reversible canonical merge (#124 / #126) |
| 30 | Admin Invitations | ✅ DONE | Implemented | #5 | Invite-gated manual onboarding (email/password & email-link) (#74) |
| 31 | Re-rate Requests | ✅ DONE | Implemented | #7 | Players request a rating reconsideration; a RATER approves (new rating) or denies (#140) |
| **MVP REQUIREMENTS (REMAINING)** |
| 9 | Player Identity Verification (KYC) 🇵🇭 | 🔴 CRITICAL | Not Started | #5 | Philippine government ID validation (Passport, DL, UMID, SSS, GSIS, National ID) |
| 9a | Social Media Verification | 🟡 NICE-TO-HAVE | Not Started | #9 | Automated verification via social media accounts (Facebook, Instagram, Twitter) |
| 10 | Player Ranking System | ✅ DONE | Implemented | #5, #6, #7 | Per-NTRP-band "Ranking Race" standings / leaderboards (#113) |
| **NICE-TO-HAVE FEATURES (ENHANCE MVP)** |
| 11 | Seeding Generation | ✅ DONE | Implemented | #10 | Host-curated player lists → rating-sorted seeding with CSV export (#111) |
| 12 | Dynamic Rating Confidence | 🟡 NICE-TO-HAVE | Not Started | #10 | Time-based confidence score for ratings (accounts for player inactivity) |
| **POST-MVP FEATURES (FUTURE ENHANCEMENTS)** |
| 13 | Doubles Support | 🟢 FUTURE | Not Started | #7, #8 | Support for doubles matches (2v2) with team ratings |
| 14 | Tournament Management | 🟢 FUTURE | Not Started | #8, #10 | Create and manage tournaments with brackets |
| 15 | League/Season Support | 🟢 FUTURE | Not Started | #8 | Seasonal ratings with resets and historical tracking |
| 16 | Mobile Apps | 🟢 FUTURE | Not Started | All APIs | iOS/Android apps for match recording |
| 17 | Social Features | 🟢 FUTURE | Not Started | #5 | Friend lists, challenge system, activity feed |
| 18 | Advanced Analytics | 🟢 FUTURE | Not Started | #8 | Predictive modeling, strength of schedule, trend analysis |
| 19 | Admin Dashboard | ✅ DONE | Implemented | All | Capability-gated Admin tab: manage players, duplicate detection/rectification, pending assessment/calculation, audit/activity log |
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

#### 7. **Dynamic Rating Confidence Value** (PRIORITY: NICE-TO-HAVE)
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
- Similar to how USTA handles doubles
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

