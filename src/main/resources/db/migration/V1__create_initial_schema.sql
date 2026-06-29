-- SPDX-FileCopyrightText: 2026 Lange Pantoja
-- SPDX-License-Identifier: AGPL-3.0-or-later

-- Skopeo Schema (single consolidated migration) — the OFFICIAL V1 baseline shipped to production.
-- Pre-production baseline: the entire incremental history has been folded back into this one file.
-- No persistent database had been provisioned, so collapsing the migrations was safe. This is the
-- last consolidation: from the first deployment onward this file is FROZEN — never edit an applied
-- migration; every schema change ships as a new V2, V3, … added incrementally.
--
-- Folded in (original migration → what it added):
--   V1  initial schema (users, names, identities, contacts, capabilities, KYC, ratings, history,
--       teams, matches, sets, tiebreaks, invites)
--   V2  audit_log (#100)
--   V3  match dimensions rework (#108): match_format = SINGLES/DOUBLES, match_type = competitive context
--   V4  users.canonical_user_id — duplicate-profile rectification (#124)
--   V5  duplicate_candidates — duplicate detection (#126)
--   V6  RATER capability (#106)
--   V7  RESEARCHER capability (#107)
--   V8  host seeding: player_lists, player_list_members, seedings, seeding_entries (#111)
--   V9  user_rating_history.set_breakdown — per-set calculation breakdown (#110)
--   V10 matches.public_code (#136)
--   V11 events, event_participants, matches.event_id (#138)
--   V12 rating_requests — re-rate requests (#140)
--   +   unaccent extension — accent-insensitive name search (#150-era search fixes)
--
-- "Users" is the single identity for everyone — players, hosts, club owners, raters, researchers,
-- administrators. What a user may do is governed by user_capabilities, not by which table they live
-- in. Names and contacts are normalized into their own tables (Filipino nicknames vs legal names;
-- per-contact verification state). Names, contacts, capabilities, and matches are append-only
-- (is_active + disabled/revoked timestamps) so history is preserved instead of mutated.

-- Enable extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
-- pg_trgm powers typo-tolerant name search (GET /api/v1/users?name=).
CREATE EXTENSION IF NOT EXISTS pg_trgm;
-- unaccent powers accent-insensitive name search ("maria" finds "María García"); user search wraps
-- unaccent(...) around stored names. Ships with the standard PostgreSQL contrib bundle (like pg_trgm).
CREATE EXTENSION IF NOT EXISTS unaccent;

-- =============================================================================
-- CORE USER MANAGEMENT
-- =============================================================================

-- Users (every person in the system; role is determined by user_capabilities)
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    -- Firebase Auth UID: the auth anchor the API resolves a user from (the verified
    -- JWT's `uid`). Nullable + unique so an admin-provisioned user can exist before
    -- claiming/linking a login. Renameable if the project ever leaves Firebase.
    firebase_uid VARCHAR(128) UNIQUE,
    photo_url TEXT,
    date_of_birth DATE,
    -- "sex" (biologically defined; only Male/Female), not "gender".
    sex VARCHAR(10),
    city VARCHAR(100),
    country VARCHAR(2) DEFAULT 'PH',
    kyc_verified BOOLEAN DEFAULT FALSE,
    kyc_verified_at TIMESTAMP,
    is_active BOOLEAN DEFAULT TRUE,
    -- Short, human-readable, shareable player code (#56): 6 Crockford-base32 chars; app-generated, unique.
    public_code VARCHAR(6) NOT NULL,
    -- Optional self-reported NTRP rating at sign-up (#75); a proposal only — an admin approves/overrides.
    proposed_rating NUMERIC(10, 6),
    -- Duplicate-profile rectification (#124): when an admin marks this account a duplicate, it is
    -- disabled (is_active = false) and points at the kept ("true") account here. Reversible; never deleted.
    canonical_user_id UUID,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_users_canonical FOREIGN KEY (canonical_user_id) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_users_sex CHECK (sex IN ('Male', 'Female'))
);

CREATE INDEX idx_users_created_at ON users(created_at);
CREATE INDEX idx_users_is_active ON users(is_active);
CREATE UNIQUE INDEX uq_users_public_code ON users (public_code);
CREATE INDEX idx_users_canonical_user_id ON users(canonical_user_id) WHERE canonical_user_id IS NOT NULL;

-- User Names (append-only; Filipino nicknames are distinct from legal names).
-- Name values are immutable: instead of editing, a name is disabled and a new one
-- added. The display name is a dedicated DISPLAY name type (one active per user),
-- not a boolean flag.
CREATE TABLE user_names (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL,
    name_type VARCHAR(20) NOT NULL,
    value VARCHAR(255) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    disabled_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_user_names_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_name_type CHECK (name_type IN
        ('FIRST', 'MIDDLE', 'LAST', 'SUFFIX', 'NICKNAME', 'PREFERRED', 'FULL', 'GOVERNMENT', 'DISPLAY'))
);

CREATE INDEX idx_user_names_user ON user_names(user_id);
-- At most one ACTIVE display name per user (the "at least one" half is guaranteed by
-- provisioning and protected by the API, which won't disable a user's only display name).
CREATE UNIQUE INDEX uq_user_display_name
    ON user_names (user_id)
    WHERE name_type = 'DISPLAY' AND is_active;
-- Trigram GIN index on lower(value) accelerates the ILIKE substring match and the
-- similarity() lookups user search runs across every name on a profile.
CREATE INDEX idx_user_names_value_trgm
    ON user_names USING gin (lower(value) gin_trgm_ops);

-- User Identities (which provider(s) a user signs in with; brokered by Firebase)
CREATE TABLE user_identities (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL,
    provider VARCHAR(20) NOT NULL,
    -- The provider's stable subject id (Google `sub`, Facebook id) as reported by Firebase.
    provider_uid VARCHAR(255) NOT NULL,
    is_primary BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_user_identities_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_identity_provider CHECK (provider IN ('GOOGLE', 'FACEBOOK', 'PASSWORD')),
    CONSTRAINT uq_identity_provider_uid UNIQUE (provider, provider_uid)
);

CREATE INDEX idx_user_identities_user ON user_identities(user_id);

-- Contact Information (emails & phones; append-only, one ACTIVE of each per user).
-- Contact values are immutable: instead of editing, a contact is disabled and a new
-- one added, preserving the full history of every email/phone a profile has held.
CREATE TABLE contact_information (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL,
    contact_type VARCHAR(10) NOT NULL,
    value VARCHAR(255) NOT NULL,
    is_primary BOOLEAN DEFAULT FALSE,
    -- Drives the initial verification state: GOOGLE/FACEBOOK contacts are inserted
    -- VERIFIED (method OAUTH_PROVIDER); MANUAL contacts start PENDING.
    source VARCHAR(20) NOT NULL,
    verification_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    verification_method VARCHAR(20),
    verified_at TIMESTAMP,
    -- Which administrator performed a manual verification (ADMIN_OVERRIDE audit trail).
    verified_by UUID,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    disabled_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_contact_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_contact_verified_by FOREIGN KEY (verified_by) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_contact_type CHECK (contact_type IN ('EMAIL', 'PHONE')),
    CONSTRAINT chk_contact_source CHECK (source IN ('GOOGLE', 'FACEBOOK', 'MANUAL')),
    CONSTRAINT chk_contact_status CHECK (verification_status IN ('PENDING', 'VERIFIED', 'FAILED')),
    CONSTRAINT chk_contact_method CHECK (
        verification_method IS NULL OR
        verification_method IN (
            'OAUTH_PROVIDER', 'EMAIL_LINK', 'SMS_OTP', 'WHATSAPP_OTP', 'VIBER_OTP', 'ADMIN_OVERRIDE'
        )
    )
);

CREATE INDEX idx_contact_user ON contact_information(user_id);
CREATE INDEX idx_contact_type_value ON contact_information(contact_type, value);
-- Policy: one ACTIVE email + one ACTIVE phone per user; disabled rows accumulate as history.
CREATE UNIQUE INDEX uq_contact_active_per_type
    ON contact_information (user_id, contact_type)
    WHERE is_active;
-- Global: a given email/phone can be VERIFIED for only one ACTIVE contact at a time.
CREATE UNIQUE INDEX uq_contact_verified_value
    ON contact_information (contact_type, value)
    WHERE is_active AND verification_status = 'VERIFIED';

-- User Capabilities (authorization; append-only grants with a full audit trail).
-- A grant is an active row; a revoke flips it inactive (revoked_by/revoked_at);
-- re-granting inserts a fresh active row. At most one ACTIVE row per capability.
-- Capabilities: PLAYER, HOST, CLUB_OWNER, ADMINISTRATOR, RATER (#106), RESEARCHER (#107).
CREATE TABLE user_capabilities (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL,
    capability VARCHAR(20) NOT NULL,
    granted_by UUID,
    granted_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    revoked_at TIMESTAMP,
    revoked_by UUID,

    CONSTRAINT fk_user_capabilities_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_capabilities_granted_by FOREIGN KEY (granted_by) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT fk_user_capabilities_revoked_by FOREIGN KEY (revoked_by) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_capability CHECK (capability IN
        ('PLAYER', 'HOST', 'CLUB_OWNER', 'ADMINISTRATOR', 'RATER', 'RESEARCHER'))
);

CREATE INDEX idx_user_capabilities_user ON user_capabilities(user_id);
CREATE INDEX idx_user_capabilities_capability ON user_capabilities(capability);
-- One ACTIVE grant per (user, capability); revoked rows accumulate as history.
CREATE UNIQUE INDEX uq_user_capability_active
    ON user_capabilities (user_id, capability)
    WHERE is_active;

-- User KYC (Philippine government IDs)
CREATE TABLE user_kyc (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL,
    id_type VARCHAR(50) NOT NULL,
    id_number VARCHAR(100) NOT NULL,
    full_name VARCHAR(255),
    expiry_date DATE,
    verification_status VARCHAR(20) DEFAULT 'PENDING',
    verification_notes TEXT,
    document_url TEXT,
    verified_at TIMESTAMP,
    verified_by UUID,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_user_kyc_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_kyc_verified_by FOREIGN KEY (verified_by) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_kyc_id_type CHECK (id_type IN ('PASSPORT', 'DRIVERS_LICENSE', 'UMID', 'SSS', 'GSIS', 'NATIONAL_ID')),
    CONSTRAINT chk_kyc_status CHECK (verification_status IN ('PENDING', 'VERIFIED', 'REJECTED')),
    CONSTRAINT uq_kyc_id_per_type UNIQUE (id_type, id_number)
);

CREATE INDEX idx_user_kyc_user ON user_kyc(user_id);
CREATE INDEX idx_user_kyc_status ON user_kyc(verification_status);

-- Duplicate-account detection candidates (#126): the detection side of #124. Suspected same-person
-- account pairs are flagged for ADMINISTRATOR review (never auto-disabled). Raised automatically when a
-- phone contact matches another active user's, or manually by an admin. The pair is stored ordered
-- (user_a_id < user_b_id) so the same two accounts collapse to one row; a partial unique index keeps at
-- most one OPEN candidate per pair. Confirming resolves it via the #124 tool. flagged_by is null for a
-- system flag.
CREATE TABLE duplicate_candidates (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_a_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    user_b_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    signal VARCHAR(20) NOT NULL,
    detail TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    flagged_by UUID REFERENCES users(id) ON DELETE SET NULL,
    flagged_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_by UUID REFERENCES users(id) ON DELETE SET NULL,
    resolved_at TIMESTAMP,
    CONSTRAINT chk_dup_candidate_signal CHECK (signal IN ('DUPLICATE_PHONE', 'MANUAL')),
    CONSTRAINT chk_dup_candidate_status CHECK (status IN ('OPEN', 'DISMISSED', 'RESOLVED')),
    CONSTRAINT chk_dup_candidate_distinct CHECK (user_a_id <> user_b_id)
);

-- At most one OPEN candidate per (ordered) pair.
CREATE UNIQUE INDEX uq_duplicate_candidates_open_pair
    ON duplicate_candidates(user_a_id, user_b_id) WHERE status = 'OPEN';

-- The admin queue: open candidates, newest first.
CREATE INDEX idx_duplicate_candidates_status ON duplicate_candidates(status, flagged_at DESC);

-- =============================================================================
-- RATING SYSTEM (NTRP-only)
-- =============================================================================

-- User Ratings (current state; one per user)
CREATE TABLE user_ratings (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL,
    current_rating NUMERIC(10, 6) NOT NULL,
    current_level VARCHAR(10),
    confidence_score NUMERIC(3, 2) DEFAULT 0.50,
    matches_played INTEGER DEFAULT 0,
    last_match_date DATE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_user_ratings_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uq_user_rating UNIQUE (user_id),
    CONSTRAINT chk_user_rating_range CHECK (current_rating >= 1.0 AND current_rating <= 7.0),
    CONSTRAINT chk_confidence_range CHECK (confidence_score >= 0.0 AND confidence_score <= 1.0)
);

CREATE INDEX idx_user_ratings_user ON user_ratings(user_id);
CREATE INDEX idx_user_ratings_last_match ON user_ratings(last_match_date);

-- User Rating History (immutable audit trail)
CREATE TABLE user_rating_history (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL,
    match_id UUID,
    previous_rating NUMERIC(10, 6) NOT NULL,
    new_rating NUMERIC(10, 6) NOT NULL,
    rating_change NUMERIC(10, 6) NOT NULL,
    percent_change NUMERIC(10, 6),
    previous_level VARCHAR(10),
    new_level VARCHAR(10),
    level_changed BOOLEAN DEFAULT FALSE,
    dominance_factor NUMERIC(10, 6),
    smoothing_applied BOOLEAN DEFAULT FALSE,
    smoothing_factor NUMERIC(3, 2),
    -- Persisted calculation breakdown (issue #97): the per-match derivatives (#89) stored at commit
    -- time so the calculation behind a committed rating can be shown faithfully later — recomputing
    -- on demand would drift if the algorithm constants (K-factor, competitive threshold, upset
    -- multiplier) ever change. Nullable: initial admin-set assessments (match_id IS NULL) have none.
    -- The dominance term reuses dominance_factor above.
    scale NUMERIC(10, 6),
    rating_gap NUMERIC(10, 6),
    normalized_gap NUMERIC(10, 6),
    competitive_threshold_pct NUMERIC(10, 6),
    is_upset BOOLEAN,
    upset_multiplier NUMERIC(10, 6),
    k_factor NUMERIC(10, 6),
    -- Per-set calculation breakdown (#110): the v2 calculator records one step per set, stored as
    -- nullable JSON; null/empty for v1, initial assessments, and rows committed before #110.
    set_breakdown TEXT,
    calculated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_rating_history_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_rating_history_user ON user_rating_history(user_id);
CREATE INDEX idx_rating_history_user_calc ON user_rating_history(user_id, calculated_at DESC);

-- Re-rate requests (issue #140, phase 2 of #106): a player raises a rating-reconsideration request
-- with a justification; a RATER approves (applies a new rating) or denies (with a reason). At most
-- one PENDING request per player at a time (partial unique index).
CREATE TABLE rating_requests (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL,
    justification TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    new_rating NUMERIC(10, 6),
    reason TEXT,
    resolved_by UUID,
    resolved_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_rating_requests_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_rating_requests_resolver FOREIGN KEY (resolved_by) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_rating_request_status CHECK (status IN ('PENDING', 'APPROVED', 'DENIED'))
);

-- At most one open (PENDING) request per player.
CREATE UNIQUE INDEX uq_rating_requests_open ON rating_requests (user_id) WHERE status = 'PENDING';

-- =============================================================================
-- EVENTS (Event Organizer #138)
-- =============================================================================

-- Hosts run events/meets that contain matches. An event has a name, a date range, participants, and a
-- shareable public code (mirroring matches/users). Matches may optionally belong to an event
-- (event-centric tab; matches.event_id stays nullable for back-compat).
CREATE TABLE events (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    public_code VARCHAR(6) NOT NULL,
    name VARCHAR(255) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    created_by UUID,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    disabled_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_events_created_by FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_event_dates CHECK (end_date >= start_date)
);

CREATE UNIQUE INDEX uq_events_public_code ON events (public_code);

CREATE TABLE event_participants (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    event_id UUID NOT NULL,
    user_id UUID NOT NULL,
    added_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_event_participants_event FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE CASCADE,
    CONSTRAINT fk_event_participants_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uq_event_participant UNIQUE (event_id, user_id)
);

-- =============================================================================
-- TEAM AND MATCH STRUCTURE
-- =============================================================================

-- Teams (singles/doubles)
CREATE TABLE teams (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    team_type VARCHAR(20) NOT NULL,
    is_temporary BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_team_type CHECK (team_type IN ('SINGLES', 'DOUBLES', 'MIXED_DOUBLES'))
);

CREATE INDEX idx_teams_team_type ON teams(team_type);
CREATE INDEX idx_teams_is_temporary ON teams(is_temporary);

-- Team Users (junction table)
CREATE TABLE team_users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    team_id UUID NOT NULL,
    user_id UUID NOT NULL,
    position INTEGER,
    joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    left_at TIMESTAMP,

    CONSTRAINT fk_team_users_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE,
    CONSTRAINT fk_team_users_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_team_position CHECK (position IN (1, 2)),
    CONSTRAINT uq_team_user_joined UNIQUE (team_id, user_id, joined_at)
);

CREATE INDEX idx_team_users_team ON team_users(team_id);
CREATE INDEX idx_team_users_user ON team_users(user_id);
CREATE INDEX idx_team_users_active ON team_users(team_id, user_id) WHERE left_at IS NULL;

-- Matches (append-only fixtures & results; ratings computed by a separate admin trigger).
-- A fixture is created first (SCHEDULED, no winner); results are uploaded later
-- (COMPLETED, completed_at). Ratings are NOT computed on upload. Corrections disable
-- the original (is_active=false, only while unrated) and add a new one.
-- Two dimensions (#108): match_format = SINGLES/DOUBLES/MIXED_DOUBLES; match_type = competitive
-- context (OPEN_PLAY, LEAGUE_PLAY, TOURNAMENT_INITIAL_ROUND, LEAGUE_PLAYOFFS, TOURNAMENT_PLAYOFFS)
-- which scales the calculated rating change by a per-type factor (applied in the calculator).
CREATE TABLE matches (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    team1_id UUID NOT NULL,
    team2_id UUID NOT NULL,
    -- Null for a scheduled fixture; set when results are uploaded.
    winner_team_id UUID,
    match_format VARCHAR(20) NOT NULL,
    match_type VARCHAR(20) NOT NULL,
    match_date DATE NOT NULL,
    venue VARCHAR(255),
    tournament_name VARCHAR(255),
    match_round VARCHAR(50),
    status VARCHAR(20) DEFAULT 'COMPLETED',
    metadata JSONB,
    -- Stable, shareable public code (#136), mirroring users.public_code: 6 Crockford-base32 chars, unique.
    public_code VARCHAR(6) NOT NULL,
    -- Optional owning event (#138); nullable for back-compat (a match need not belong to an event).
    event_id UUID,
    completed_at TIMESTAMP, -- when results were uploaded (calculation ordering key)
    rated_at TIMESTAMP, -- when the rating calculation finalized this match
    rated_by UUID,
    created_by UUID, -- who created the fixture
    recorded_by UUID, -- who uploaded the results
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    disabled_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_matches_team1 FOREIGN KEY (team1_id) REFERENCES teams(id) ON DELETE RESTRICT,
    CONSTRAINT fk_matches_team2 FOREIGN KEY (team2_id) REFERENCES teams(id) ON DELETE RESTRICT,
    CONSTRAINT fk_matches_winner FOREIGN KEY (winner_team_id) REFERENCES teams(id) ON DELETE RESTRICT,
    CONSTRAINT fk_matches_event FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE SET NULL,
    CONSTRAINT fk_matches_created_by FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT fk_matches_recorded_by FOREIGN KEY (recorded_by) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT fk_matches_rated_by FOREIGN KEY (rated_by) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_match_format CHECK (match_format IN ('SINGLES', 'DOUBLES', 'MIXED_DOUBLES')),
    CONSTRAINT chk_match_status CHECK (status IN ('SCHEDULED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT chk_match_teams_different CHECK (team1_id != team2_id)
);

CREATE INDEX idx_matches_match_date ON matches(match_date DESC);
CREATE INDEX idx_matches_status ON matches(status);
CREATE INDEX idx_matches_team1_date ON matches(team1_id, match_date DESC);
CREATE INDEX idx_matches_team2_date ON matches(team2_id, match_date DESC);
CREATE UNIQUE INDEX uq_matches_public_code ON matches (public_code);
-- Oversight queries: pending-calculation (completed, unrated) and awaiting-results
-- (scheduled past its match_date).
CREATE INDEX idx_matches_pending_calc ON matches (completed_at) WHERE is_active AND status = 'COMPLETED' AND rated_at IS NULL;
CREATE INDEX idx_matches_awaiting_results ON matches (match_date) WHERE is_active AND status = 'SCHEDULED';

-- Match Sets
CREATE TABLE match_sets (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    match_id UUID NOT NULL,
    set_number INTEGER NOT NULL,
    team1_games INTEGER NOT NULL,
    team2_games INTEGER NOT NULL,
    winner_team_id UUID NOT NULL,
    has_tiebreak BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_match_sets_match FOREIGN KEY (match_id) REFERENCES matches(id) ON DELETE CASCADE,
    CONSTRAINT fk_match_sets_winner FOREIGN KEY (winner_team_id) REFERENCES teams(id) ON DELETE RESTRICT,
    CONSTRAINT chk_set_number CHECK (set_number BETWEEN 1 AND 5),
    CONSTRAINT chk_set_games_positive CHECK (team1_games >= 0 AND team2_games >= 0)
);

CREATE INDEX idx_match_sets_match ON match_sets(match_id);
CREATE INDEX idx_match_sets_match_set ON match_sets(match_id, set_number);

-- Match Set Tiebreaks
CREATE TABLE match_set_tiebreaks (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    match_set_id UUID NOT NULL UNIQUE,
    team1_points INTEGER NOT NULL,
    team2_points INTEGER NOT NULL,
    winner_team_id UUID NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_tiebreaks_set FOREIGN KEY (match_set_id) REFERENCES match_sets(id) ON DELETE CASCADE,
    CONSTRAINT fk_tiebreaks_winner FOREIGN KEY (winner_team_id) REFERENCES teams(id) ON DELETE RESTRICT,
    CONSTRAINT chk_tiebreak_points_positive CHECK (team1_points >= 0 AND team2_points >= 0)
);

CREATE INDEX idx_tiebreaks_set ON match_set_tiebreaks(match_set_id);

-- =============================================================================
-- ADD FOREIGN KEY FOR RATING HISTORY TO MATCHES (after matches table exists)
-- =============================================================================

ALTER TABLE user_rating_history
    ADD CONSTRAINT fk_rating_history_match
    FOREIGN KEY (match_id) REFERENCES matches(id) ON DELETE SET NULL;

CREATE INDEX idx_rating_history_match ON user_rating_history(match_id);

-- =============================================================================
-- HOST SEEDING (issue #111)
-- =============================================================================
-- A host curates named player lists and generates a timestamped, rating-sorted seeding (one current
-- seeding per list; regenerate overwrites). Seedings are frozen snapshots so the CSV export is
-- reproducible.

CREATE TABLE player_lists (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id   UUID        NOT NULL,
    name       VARCHAR(120) NOT NULL,
    created_at TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT fk_player_lists_owner FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_player_lists_owner ON player_lists(owner_id);

CREATE TABLE player_list_members (
    id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    list_id  UUID      NOT NULL,
    user_id  UUID      NOT NULL,
    added_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT fk_player_list_members_list FOREIGN KEY (list_id) REFERENCES player_lists(id) ON DELETE CASCADE,
    CONSTRAINT fk_player_list_members_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uq_player_list_members UNIQUE (list_id, user_id)
);

CREATE INDEX idx_player_list_members_list ON player_list_members(list_id);

CREATE TABLE seedings (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    list_id      UUID      NOT NULL,
    generated_at TIMESTAMP NOT NULL DEFAULT now(),
    generated_by UUID,
    CONSTRAINT fk_seedings_list FOREIGN KEY (list_id) REFERENCES player_lists(id) ON DELETE CASCADE,
    CONSTRAINT fk_seedings_generated_by FOREIGN KEY (generated_by) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT uq_seedings_list UNIQUE (list_id)
);

CREATE TABLE seeding_entries (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    seeding_id   UUID         NOT NULL,
    seed         INTEGER,
    position     INTEGER      NOT NULL,
    user_id      UUID,
    display_name VARCHAR(255),
    public_code  VARCHAR(16)  NOT NULL,
    ntrp_band    VARCHAR(8),
    rating       VARCHAR(32)  NOT NULL,
    sex          VARCHAR(16),
    age          INTEGER,
    CONSTRAINT fk_seeding_entries_seeding FOREIGN KEY (seeding_id) REFERENCES seedings(id) ON DELETE CASCADE,
    CONSTRAINT fk_seeding_entries_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
);

CREATE INDEX idx_seeding_entries_seeding ON seeding_entries(seeding_id);

-- =============================================================================
-- AUDIT LOG (issue #100)
-- =============================================================================
-- Append-only record of who did what, when, to which entity. A single log keyed by
-- (entity_type, action); domain tables do NOT reference it. Typed columns carry what we always
-- query/sort/display (actor, action, entity, time, summary); the JSONB `details` holds per-action
-- extras (before/after diffs, etc.) so new event types need no migration. actor_user_id is null
-- for SYSTEM / self-driven actions. Rows are never updated or deleted (the comment field aside).

CREATE TABLE audit_log (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    action VARCHAR(64) NOT NULL,
    entity_type VARCHAR(40) NOT NULL,
    entity_id UUID,
    summary TEXT NOT NULL,
    details JSONB,
    -- A free-text note an administrator can attach to an entry; the one mutable field, and its
    -- edits are intentionally not themselves audited.
    comment TEXT
);

-- Newest-first reads, both overall and filtered per action/category (the admin trace viewer, #102).
CREATE INDEX idx_audit_occurred_at ON audit_log (occurred_at DESC);
CREATE INDEX idx_audit_action_time ON audit_log (action, occurred_at DESC);

-- =============================================================================
-- INVITES (issue #74)
-- =============================================================================
-- Admin invitations for manual (email/password & email-link) onboarding. Manual sign-ups are
-- invite-only: an admin records an invite for an email here, and profile provisioning is refused for a
-- password/email-link token whose email has no open invite. OAuth sign-ups are exempt.
-- status: PENDING | ACCEPTED | REVOKED (EXPIRED is derived from expires_at, not stored).
CREATE TABLE invites (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    invited_by UUID REFERENCES users(id) ON DELETE SET NULL,
    expires_at TIMESTAMP NOT NULL,
    accepted_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_invites_email ON invites (email);

-- =============================================================================
-- UPDATED_AT TRIGGER FUNCTION
-- =============================================================================

CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Apply trigger to all tables with an updated_at column
CREATE TRIGGER update_users_updated_at BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_user_names_updated_at BEFORE UPDATE ON user_names
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_contact_information_updated_at BEFORE UPDATE ON contact_information
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_user_kyc_updated_at BEFORE UPDATE ON user_kyc
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_user_ratings_updated_at BEFORE UPDATE ON user_ratings
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_teams_updated_at BEFORE UPDATE ON teams
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_matches_updated_at BEFORE UPDATE ON matches
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- =============================================================================
-- COMMENTS FOR DOCUMENTATION
-- =============================================================================

COMMENT ON TABLE users IS 'Every person in the system; role/permissions live in user_capabilities';
COMMENT ON TABLE user_names IS 'Names per user (first/last/nickname/legal/display/etc.); append-only, supports Filipino nicknames and KYC name matching';
COMMENT ON TABLE user_identities IS 'Authentication providers linked to a user (Google/Facebook/password), brokered by Firebase Auth';
COMMENT ON TABLE contact_information IS 'User emails and phones (one active of each), each with its own source and verification state; append-only';
COMMENT ON TABLE user_capabilities IS 'Authorization: broad roles granted to a user (PLAYER/HOST/CLUB_OWNER/ADMINISTRATOR/RATER/RESEARCHER); append-only with grant/revoke audit trail';
COMMENT ON TABLE user_kyc IS 'Philippine government ID verification for KYC compliance';
COMMENT ON TABLE duplicate_candidates IS 'Suspected same-person account pairs flagged for admin review (#126); confirming resolves via the #124 rectification tool';
COMMENT ON TABLE user_ratings IS 'Current NTRP rating state for each user (one per user)';
COMMENT ON TABLE user_rating_history IS 'Immutable audit trail of all rating changes';
COMMENT ON TABLE rating_requests IS 'Player-raised rating-reconsideration requests; a RATER approves (new rating) or denies (reason) (#140)';
COMMENT ON TABLE events IS 'Host-run events/meets that contain matches; participants + shareable public code (#138)';
COMMENT ON TABLE event_participants IS 'Junction table for event membership';
COMMENT ON TABLE teams IS 'Match participants - singles (1 user) or doubles (2 users)';
COMMENT ON TABLE team_users IS 'Junction table for team membership with position tracking';
COMMENT ON TABLE matches IS 'Match records between two teams';
COMMENT ON TABLE match_sets IS 'Set-by-set scoring for each match';
COMMENT ON TABLE match_set_tiebreaks IS 'Tiebreak details when a set goes to tiebreak';
COMMENT ON TABLE player_lists IS 'Host-curated named player lists for seeding generation (#111)';
COMMENT ON TABLE player_list_members IS 'Membership of a player list';
COMMENT ON TABLE seedings IS 'Frozen, rating-sorted seeding snapshot for a player list (one current per list, #111)';
COMMENT ON TABLE seeding_entries IS 'A single seeded player row within a seeding snapshot';
COMMENT ON TABLE audit_log IS 'Append-only provenance of domain actions (who/what/when); see issue #100';
COMMENT ON TABLE invites IS 'Admin onboarding invitations; the provisioning gate admits a manual sign-up only with an open (PENDING, unexpired) invite for the email (#74)';

COMMENT ON COLUMN users.firebase_uid IS 'Firebase Auth UID; auth anchor matched against the verified JWT. Null for admin-provisioned users not yet claimed.';
COMMENT ON COLUMN users.sex IS 'Biological sex (Male/Female); informs tournament categories';
COMMENT ON COLUMN users.kyc_verified IS 'Whether the user has passed government ID verification';
COMMENT ON COLUMN users.canonical_user_id IS 'When set, this account was marked a duplicate of the referenced kept account (#124)';
COMMENT ON COLUMN contact_information.verification_method IS 'How the contact was verified: OAUTH_PROVIDER (trusted from Google/Facebook), EMAIL_LINK, an OTP channel (SMS/WhatsApp/Viber), or ADMIN_OVERRIDE';
COMMENT ON COLUMN user_ratings.confidence_score IS 'Rating confidence (0.0-1.0) that decays over time';
COMMENT ON COLUMN teams.is_temporary IS 'TRUE for ad-hoc teams, FALSE for established partnerships';
COMMENT ON COLUMN matches.match_format IS 'SINGLES/DOUBLES/MIXED_DOUBLES';
COMMENT ON COLUMN matches.match_type IS 'Competitive context (OPEN_PLAY, LEAGUE_PLAY, ...); scales the rating change by a per-type factor (#108)';
COMMENT ON COLUMN matches.public_code IS 'Stable shareable code (#136), mirroring users.public_code';
COMMENT ON COLUMN matches.event_id IS 'Optional owning event (#138); nullable for back-compat';
COMMENT ON COLUMN matches.metadata IS 'Flexible JSON field for additional match data';
COMMENT ON COLUMN matches.completed_at IS 'When results were uploaded; ordering key for the rating-calculation trigger';
COMMENT ON COLUMN matches.rated_at IS 'When the rating calculation finalized this match (null = pending calculation)';
