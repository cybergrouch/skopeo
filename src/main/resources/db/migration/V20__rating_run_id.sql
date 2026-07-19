-- V20: stamp each rating-calculation batch with a rating_run_id for deterministic ordering (#481).
--
-- calculated_at is stamped once per calc run (all rows a run writes share one identical value, captured
-- as LocalDateTime.now()), so it can order BATCHES but not rows WITHIN a batch, and intra-batch ties on
-- completed_at can't be broken without reconstructing the per-player rating chain. rating_run_id gives
-- each calc run an explicit identity: a cheap, unambiguous key to order and group "what one run produced"
-- (supports #478 event-scoped reversal and future replay tooling).
--
-- Nullable by design: admin/self-set initial-rating rows (match_id IS NULL) are not calc batches and
-- correctly carry no run id. Going forward RatingCalculationService.commit() generates one id per run and
-- stamps every row it writes (mirrors how it captures calculated_at once per run).

ALTER TABLE user_rating_history ADD COLUMN rating_run_id UUID;
COMMENT ON COLUMN user_rating_history.rating_run_id IS
  'Identity of the calc batch that produced this row (#481); one id per run, shared by all its rows — a deterministic ordering/grouping key. NULL for admin/self-set rows (not calc batches).';

-- Backfill only match-linked rows: calculated_at IS the batch key (a run writes one identical value),
-- so grouping by it reconstructs the exact historical batches and assigns one uuid per distinct batch.
-- match-less rows (admin/self-set) stay NULL. uuid_generate_v4() matches V1's uuid-ossp usage.
UPDATE user_rating_history h
SET rating_run_id = g.run_id
FROM (
  SELECT calculated_at, uuid_generate_v4() AS run_id
  FROM user_rating_history
  WHERE match_id IS NOT NULL
  GROUP BY calculated_at
) g
WHERE h.calculated_at = g.calculated_at AND h.match_id IS NOT NULL;

CREATE INDEX idx_rating_history_run ON user_rating_history (rating_run_id);
