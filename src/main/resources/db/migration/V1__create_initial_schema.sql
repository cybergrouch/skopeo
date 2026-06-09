-- Tennis Levelr MVP Schema - Phase 1
-- This migration creates the core tables for:
-- - Player Profile Management with Philippine KYC
-- - Match Tracking (singles only initially)
-- - Player Rating System (NTRP/UTR)

-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- =============================================================================
-- CORE PLAYER MANAGEMENT
-- =============================================================================

-- Players table
CREATE TABLE players (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email VARCHAR(255) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    phone VARCHAR(50),
    date_of_birth DATE,
    gender VARCHAR(10),
    city VARCHAR(100),
    country VARCHAR(2) DEFAULT 'PH',
    kyc_verified BOOLEAN DEFAULT FALSE,
    kyc_verified_at TIMESTAMP,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_players_email CHECK (email ~* '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$'),
    CONSTRAINT chk_players_gender CHECK (gender IN ('M', 'F', 'Other'))
);

CREATE INDEX idx_players_created_at ON players(created_at);
CREATE INDEX idx_players_is_active ON players(is_active);
CREATE INDEX idx_players_email ON players(email);

-- Player KYC table (Philippine government IDs)
CREATE TABLE player_kyc (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    player_id UUID NOT NULL,
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

    CONSTRAINT fk_player_kyc_player FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE,
    CONSTRAINT chk_kyc_id_type CHECK (id_type IN ('PASSPORT', 'DRIVERS_LICENSE', 'UMID', 'SSS', 'GSIS', 'NATIONAL_ID')),
    CONSTRAINT chk_kyc_status CHECK (verification_status IN ('PENDING', 'VERIFIED', 'REJECTED')),
    CONSTRAINT uq_kyc_id_per_type UNIQUE (id_type, id_number)
);

CREATE INDEX idx_player_kyc_player ON player_kyc(player_id);
CREATE INDEX idx_player_kyc_status ON player_kyc(verification_status);

-- =============================================================================
-- RATING SYSTEM
-- =============================================================================

-- Player Ratings (current state)
CREATE TABLE player_ratings (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    player_id UUID NOT NULL,
    rating_system VARCHAR(10) NOT NULL,
    current_rating NUMERIC(10, 6) NOT NULL,
    current_level VARCHAR(10),
    confidence_score NUMERIC(3, 2) DEFAULT 0.50,
    matches_played INTEGER DEFAULT 0,
    last_match_date DATE,
    utr_rating NUMERIC(10, 6),
    utr_last_synced TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_player_ratings_player FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE,
    CONSTRAINT uq_player_rating_system UNIQUE (player_id, rating_system),
    CONSTRAINT chk_rating_system CHECK (rating_system IN ('NTRP', 'UTR')),
    CONSTRAINT chk_rating_range_ntrp CHECK (rating_system != 'NTRP' OR (current_rating >= 1.0 AND current_rating <= 7.0)),
    CONSTRAINT chk_rating_range_utr CHECK (rating_system != 'UTR' OR (current_rating >= 1.0 AND current_rating <= 16.0)),
    CONSTRAINT chk_confidence_range CHECK (confidence_score >= 0.0 AND confidence_score <= 1.0)
);

CREATE INDEX idx_player_ratings_player ON player_ratings(player_id);
CREATE INDEX idx_player_ratings_system ON player_ratings(rating_system);
CREATE INDEX idx_player_ratings_last_match ON player_ratings(last_match_date);

-- Player Rating History (immutable audit trail)
CREATE TABLE player_rating_history (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    player_id UUID NOT NULL,
    match_id UUID,
    rating_system VARCHAR(10) NOT NULL,
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
    calculated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_rating_history_player FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE,
    CONSTRAINT chk_history_rating_system CHECK (rating_system IN ('NTRP', 'UTR'))
);

CREATE INDEX idx_rating_history_player ON player_rating_history(player_id);
CREATE INDEX idx_rating_history_player_calc ON player_rating_history(player_id, calculated_at DESC);
CREATE INDEX idx_rating_history_system ON player_rating_history(rating_system);

-- =============================================================================
-- TEAM AND MATCH STRUCTURE
-- =============================================================================

-- Teams (singles/doubles)
CREATE TABLE teams (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    team_type VARCHAR(20) NOT NULL,
    is_temporary BOOLEAN DEFAULT TRUE,
    rating_system VARCHAR(10) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_team_type CHECK (team_type IN ('SINGLES', 'DOUBLES', 'MIXED_DOUBLES')),
    CONSTRAINT chk_team_rating_system CHECK (rating_system IN ('NTRP', 'UTR'))
);

CREATE INDEX idx_teams_team_type ON teams(team_type);
CREATE INDEX idx_teams_is_temporary ON teams(is_temporary);

-- Team Players (junction table)
CREATE TABLE team_players (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    team_id UUID NOT NULL,
    player_id UUID NOT NULL,
    position INTEGER,
    joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    left_at TIMESTAMP,

    CONSTRAINT fk_team_players_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE,
    CONSTRAINT fk_team_players_player FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE,
    CONSTRAINT chk_team_position CHECK (position IN (1, 2)),
    CONSTRAINT uq_team_player_joined UNIQUE (team_id, player_id, joined_at)
);

CREATE INDEX idx_team_players_team ON team_players(team_id);
CREATE INDEX idx_team_players_player ON team_players(player_id);
CREATE INDEX idx_team_players_active ON team_players(team_id, player_id) WHERE left_at IS NULL;

-- Matches
CREATE TABLE matches (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    team1_id UUID NOT NULL,
    team2_id UUID NOT NULL,
    winner_team_id UUID NOT NULL,
    match_type VARCHAR(20) NOT NULL,
    match_format VARCHAR(20) DEFAULT 'BEST_OF_THREE',
    rating_system VARCHAR(10) NOT NULL,
    match_date DATE NOT NULL,
    venue VARCHAR(255),
    tournament_name VARCHAR(255),
    match_round VARCHAR(50),
    status VARCHAR(20) DEFAULT 'COMPLETED',
    metadata JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_matches_team1 FOREIGN KEY (team1_id) REFERENCES teams(id) ON DELETE RESTRICT,
    CONSTRAINT fk_matches_team2 FOREIGN KEY (team2_id) REFERENCES teams(id) ON DELETE RESTRICT,
    CONSTRAINT fk_matches_winner FOREIGN KEY (winner_team_id) REFERENCES teams(id) ON DELETE RESTRICT,
    CONSTRAINT chk_match_type CHECK (match_type IN ('SINGLES', 'DOUBLES', 'MIXED_DOUBLES')),
    CONSTRAINT chk_match_format CHECK (match_format IN ('BEST_OF_THREE', 'BEST_OF_FIVE')),
    CONSTRAINT chk_match_status CHECK (status IN ('SCHEDULED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT chk_match_teams_different CHECK (team1_id != team2_id),
    CONSTRAINT chk_match_rating_system CHECK (rating_system IN ('NTRP', 'UTR'))
);

CREATE INDEX idx_matches_match_date ON matches(match_date DESC);
CREATE INDEX idx_matches_status ON matches(status);
CREATE INDEX idx_matches_rating_system ON matches(rating_system);
CREATE INDEX idx_matches_team1_date ON matches(team1_id, match_date DESC);
CREATE INDEX idx_matches_team2_date ON matches(team2_id, match_date DESC);

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

ALTER TABLE player_rating_history
    ADD CONSTRAINT fk_rating_history_match
    FOREIGN KEY (match_id) REFERENCES matches(id) ON DELETE SET NULL;

CREATE INDEX idx_rating_history_match ON player_rating_history(match_id);

-- =============================================================================
-- UPDATED_AT TRIGGER FUNCTION
-- =============================================================================

-- Function to automatically update updated_at timestamp
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Apply trigger to all tables with updated_at column
CREATE TRIGGER update_players_updated_at BEFORE UPDATE ON players
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_player_kyc_updated_at BEFORE UPDATE ON player_kyc
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_player_ratings_updated_at BEFORE UPDATE ON player_ratings
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_teams_updated_at BEFORE UPDATE ON teams
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_matches_updated_at BEFORE UPDATE ON matches
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- =============================================================================
-- COMMENTS FOR DOCUMENTATION
-- =============================================================================

COMMENT ON TABLE players IS 'Core player profile information';
COMMENT ON TABLE player_kyc IS 'Philippine government ID verification for KYC compliance';
COMMENT ON TABLE player_ratings IS 'Current rating state for each player per rating system';
COMMENT ON TABLE player_rating_history IS 'Immutable audit trail of all rating changes';
COMMENT ON TABLE teams IS 'Match participants - singles (1 player) or doubles (2 players)';
COMMENT ON TABLE team_players IS 'Junction table for team membership with position tracking';
COMMENT ON TABLE matches IS 'Match records between two teams';
COMMENT ON TABLE match_sets IS 'Set-by-set scoring for each match';
COMMENT ON TABLE match_set_tiebreaks IS 'Tiebreak details when a set goes to tiebreak';

COMMENT ON COLUMN players.kyc_verified IS 'Whether player has passed government ID verification';
COMMENT ON COLUMN player_ratings.confidence_score IS 'Rating confidence (0.0-1.0) that decays over time';
COMMENT ON COLUMN teams.is_temporary IS 'TRUE for ad-hoc teams, FALSE for established partnerships';
COMMENT ON COLUMN matches.metadata IS 'Flexible JSON field for additional match data';
