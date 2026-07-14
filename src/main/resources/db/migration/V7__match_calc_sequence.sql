-- V7: manual calculation-order tiebreaker on matches (#331, #332).
--
-- The rating calculation is a single global, order-sensitive pass. It now orders by match_date
-- (chronological play order, #331); calc_sequence is an optional per-date tiebreaker a host can set
-- by drag-reordering same-day match cards (#332). Null means "not manually ordered" — such matches
-- fall back to completed_at/id within their date, so behavior is unchanged until a host reorders.

ALTER TABLE matches ADD COLUMN calc_sequence INTEGER;
COMMENT ON COLUMN matches.calc_sequence IS
    'Manual same-date ordering tiebreaker for rating calculation (#331/#332); null = chronological default.';

-- The pending-calculation lookup now orders by (match_date, calc_sequence, completed_at); align the
-- partial index accordingly (was on completed_at alone).
DROP INDEX IF EXISTS idx_matches_pending_calc;
CREATE INDEX idx_matches_pending_calc ON matches (match_date, calc_sequence, completed_at)
    WHERE is_active AND status = 'COMPLETED' AND rated_at IS NULL;
