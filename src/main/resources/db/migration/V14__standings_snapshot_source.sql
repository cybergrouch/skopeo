-- V14: tag a standings snapshot with the source that produced it (#146, phase 2).
--
-- Phase 1 (#220) built standings from ratings; phase 2 recomputes them from the ranking-points ledger
-- (#146). A source tag lets the two coexist non-disruptively: reads prefer the latest PUBLISHED
-- POINTS snapshot when one exists, else fall back to the latest PUBLISHED RATING snapshot. Existing
-- rows were all rating-derived, so they backfill to 'RATING' via the DEFAULT.

ALTER TABLE standings_snapshots ADD COLUMN source VARCHAR(8) NOT NULL DEFAULT 'RATING';

-- Widen the source-agnostic ordering value from NUMERIC(6,4) (sized for an NTRP rating <= 7.0000) so it
-- can also hold a points total, which is unbounded above (ATP-style totals reach the hundreds/thousands).
-- The tie-break rating stays NUMERIC(6,4): it only ever holds a rating. Widening is lossless for the
-- existing rating-derived rows.
ALTER TABLE standings_entries ALTER COLUMN ordering_value TYPE NUMERIC(12,4);
