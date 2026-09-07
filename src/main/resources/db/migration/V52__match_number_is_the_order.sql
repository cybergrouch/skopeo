-- V52: match_number becomes the intra-event ordering key, and calc_sequence retires (#898).
--
-- The rating calculation orders event-first, then within the event. That inner key was
-- (match_date, calc_sequence NULLS LAST, completed_at, id); it is now simply match_number.
--
-- Why the number has to REPLACE match_date rather than sit under it: #898 requires that matches read
-- lowest number first. Keeping the date as the primary key and the number as a tiebreaker would show
-- "Match #2, Match #1" whenever fixtures were created out of date order. For display order to be a
-- consequence of the number, the number has to BE the order.
--
-- This does change behaviour: the intra-event sequence now follows the host's arrangement rather than
-- chronology. A Saturday match numbered #5 is rated after a Sunday match numbered #3. That is the
-- point — explicit and controllable beats implicit — and V51 seeded match_number FROM the old tuple, so
-- the order is identical for every match that exists today. Divergence begins only with fixtures
-- created out of date order after this, which the host corrects by reordering.
--
-- calc_sequence had exactly one remaining job: ordering event-less same-date matches. V50 made those
-- impossible, so it is dead. Dropping it is a one-way door — any hand-set ordering it holds is gone —
-- and that is acceptable precisely because V51's backfill READ it: the intent survives inside
-- match_number, only the column goes.

-- The partial index from V7 was keyed on the old tuple; the queue now orders by match_number.
DROP INDEX IF EXISTS idx_matches_pending_calc;
CREATE INDEX idx_matches_pending_calc ON matches (event_id, match_number)
    WHERE is_active AND status = 'COMPLETED' AND rated_at IS NULL;

ALTER TABLE matches DROP COLUMN calc_sequence;
