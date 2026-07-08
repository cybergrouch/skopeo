-- V3: order rating history newest-first with a stable intra-batch tiebreaker (#301).
--
-- The rating-calculation batch stamps every row it writes in a single run with one identical
-- calculated_at (captured once as LocalDateTime.now()), so calculated_at alone cannot order rows
-- within a batch. We denormalize the source match's completed_at (its result-upload time) onto the
-- history row and use it as the tiebreaker, ordering (calculated_at DESC, completed_at DESC).
--
-- This is a point-in-time snapshot, not a cache of a live value: a history row is appended in the
-- same transaction that marks its match rated, and rated matches are frozen (result edits and
-- disabling are both blocked once rated_at is set, and rated_at is never cleared). So this value can
-- never drift. It is also more robust than a read-time join: match_id is ON DELETE SET NULL, so a
-- denormalized copy keeps its ordering key even if a match were ever hard-deleted.
--
-- NULL for match-less rows (initial assessments / admin overrides), which sort last (earliest).

ALTER TABLE user_rating_history ADD COLUMN completed_at TIMESTAMP;

COMMENT ON COLUMN user_rating_history.completed_at IS
  'Snapshot of the source match''s completed_at at calc-commit time; intra-batch tiebreaker for newest-first ordering (#301). NULL for match-less rows.';

-- Backfill existing rows from the (immutable, rated) source match. Every match_id-bearing row points
-- to a COMPLETED+rated match, which always has a non-null completed_at; match-less rows stay NULL.
UPDATE user_rating_history h
SET completed_at = m.completed_at
FROM matches m
WHERE h.match_id = m.id;

-- Support the newest-first read. Supersedes idx_rating_history_user_calc (a prefix of this one).
DROP INDEX IF EXISTS idx_rating_history_user_calc;
CREATE INDEX idx_rating_history_user_order
    ON user_rating_history (user_id, calculated_at DESC, completed_at DESC);
