-- =============================================================================
-- V3: Match occasion — per-occasion rating factor (issue #108)
-- =============================================================================
-- Each fixture records the occasion it was played under (open play, league, tournament, and their
-- playoffs). The occasion scales the calculated rating change by a per-occasion factor (applied in
-- the calculator), because different occasions carry different competitive pressure. This replaces
-- the vestigial match_format column (BEST_OF_THREE/SINGLE_SET), which the rating algorithm never used.
-- Occasion is required at fixture creation; the app always supplies it (no column default).

ALTER TABLE matches DROP COLUMN match_format;

ALTER TABLE matches ADD COLUMN occasion VARCHAR(20);
UPDATE matches SET occasion = 'OPEN_PLAY' WHERE occasion IS NULL;  -- backfill any pre-existing rows
ALTER TABLE matches ALTER COLUMN occasion SET NOT NULL;
