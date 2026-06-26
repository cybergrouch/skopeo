-- =============================================================================
-- V2: Persist the rating-calculation breakdown on each history row (issue #97)
-- =============================================================================
-- The per-match calculation derivatives (#89) were previously computed only for
-- the dry-run preview and discarded on commit. We now store them on the history
-- row at commit time so the calculation behind a committed rating can be shown
-- faithfully later — recomputing on demand would drift if the algorithm
-- constants (K-factor, competitive threshold, upset multiplier) ever change.
--
-- `dominance_factor` already exists (V1) and is reused for the dominance term.
-- All columns are nullable: rows committed before this migration keep NULLs, and
-- initial admin-set assessments (match_id IS NULL) have no calculation.

ALTER TABLE user_rating_history
    ADD COLUMN scale NUMERIC(10, 6),
    ADD COLUMN rating_gap NUMERIC(10, 6),
    ADD COLUMN normalized_gap NUMERIC(10, 6),
    ADD COLUMN competitive_threshold_pct NUMERIC(10, 6),
    ADD COLUMN is_upset BOOLEAN,
    ADD COLUMN upset_multiplier NUMERIC(10, 6),
    ADD COLUMN k_factor NUMERIC(10, 6);

COMMENT ON COLUMN user_rating_history.scale IS 'Calculation breakdown (#97): the change scale factor at commit time';
COMMENT ON COLUMN user_rating_history.rating_gap IS 'Calculation breakdown (#97): rating gap vs the opponent at commit time';
COMMENT ON COLUMN user_rating_history.normalized_gap IS 'Calculation breakdown (#97): rating gap normalized over the NTRP range';
COMMENT ON COLUMN user_rating_history.competitive_threshold_pct IS 'Calculation breakdown (#97): competitive threshold percent used';
COMMENT ON COLUMN user_rating_history.is_upset IS 'Calculation breakdown (#97): whether the result was an upset';
COMMENT ON COLUMN user_rating_history.upset_multiplier IS 'Calculation breakdown (#97): upset multiplier applied';
COMMENT ON COLUMN user_rating_history.k_factor IS 'Calculation breakdown (#97): NTRP K-factor used at commit time';
