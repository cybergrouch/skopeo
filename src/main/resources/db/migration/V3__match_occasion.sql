-- =============================================================================
-- V3: Match type — per-match-type rating factor (issue #108)
-- =============================================================================
-- Reworks the match dimensions:
--   * match_format now holds SINGLES/DOUBLES (renamed from the old match_type column).
--   * match_type now holds the competitive context — OPEN_PLAY, LEAGUE_PLAY,
--     TOURNAMENT_INITIAL_ROUND, LEAGUE_PLAYOFFS, TOURNAMENT_PLAYOFFS — which scales the calculated
--     rating change by a per-type factor (applied in the calculator).
-- The vestigial best-of-N format column (BEST_OF_THREE/SINGLE_SET) is dropped — the rating algorithm
-- never used it. match_type is required at fixture creation; the app always supplies it (no default).

-- Drop the vestigial best-of-N format (also drops its chk_match_format CHECK).
ALTER TABLE matches DROP COLUMN match_format;

-- The SINGLES/DOUBLES dimension becomes match_format (its CHECK travels with the rename).
ALTER TABLE matches RENAME COLUMN match_type TO match_format;
ALTER TABLE matches RENAME CONSTRAINT chk_match_type TO chk_match_format;

-- The competitive-context dimension takes over the match_type name.
ALTER TABLE matches ADD COLUMN match_type VARCHAR(20);
UPDATE matches SET match_type = 'OPEN_PLAY' WHERE match_type IS NULL;  -- backfill any pre-existing rows
ALTER TABLE matches ALTER COLUMN match_type SET NOT NULL;
