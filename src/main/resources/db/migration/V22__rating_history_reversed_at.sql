-- V22: soft-delete marker for reversed rating-history rows (#478).
--
-- "Reverse Ratings" (#478) reverses an already-rated event so an erroneous score can be corrected and
-- the event re-finalized. It must NOT hard-delete the event's rating-history rows — the ledger stays
-- append-only for traceability — so it supersedes them with a soft-delete marker instead. reversed_at
-- stamps when a row was superseded; the rating-history READ paths exclude any row where it is non-null.
--
-- Nullable by design: a live (not-yet-reversed) history row carries NULL here, which is every row today
-- and the overwhelming majority forever — reversal is a rare admin correction. There is no backfill:
-- all existing rows are live.

ALTER TABLE user_rating_history ADD COLUMN reversed_at TIMESTAMP;
COMMENT ON COLUMN user_rating_history.reversed_at IS
  'When this row was superseded by an event-scoped rating reversal (#478); NULL = live. Reversed rows are excluded from the rating-history read paths (soft-delete, not hard-delete — the ledger stays append-only).';

-- Partial index over the live rows only: the read paths filter reversed_at IS NULL, and reversed rows
-- are rare, so a partial index keeps the common read cheap without indexing the seldom-set column.
CREATE INDEX idx_rating_history_live ON user_rating_history (user_id) WHERE reversed_at IS NULL;
