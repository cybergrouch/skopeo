-- V30: remove the per-event points budget + designation subsystem (#559/#561, superseding #403 Phase B/C).
--
-- The per-club points *budget*, the per-event min/max/validity points *config*, and the per-match
-- point *designation* are all removed. Awarding is now controlled by a single per-event flag,
-- "Award Ranking Points" (default on); when set, finalizing pays points per the GLOBAL schedules
-- (#553 open-play, #552 tournament) with validity derived from the event end + the schedule's window.
--
-- Three parts:
--   1. events.award_ranking_points — the new single opt-out flag (default true, backfilled true).
--   2. events — drop the obsolete per-event points config columns.
--   3. matches.designated_points — drop the obsolete per-fixture designation.
--   4. club_point_budgets — drop the now-unused budget table (nothing reads it).
--
-- The POINTS_MANAGER capability is intentionally retained: it still gates the global points
-- schedules (#552/#553) and the ranking-points admin surfaces (#472).

ALTER TABLE events ADD COLUMN award_ranking_points BOOLEAN NOT NULL DEFAULT TRUE;
COMMENT ON COLUMN events.award_ranking_points IS
    'Whether finalizing this event awards ranking points per the global schedules (#559); default true.';

ALTER TABLE events DROP COLUMN IF EXISTS min_points_per_match;
ALTER TABLE events DROP COLUMN IF EXISTS max_points_per_match;
ALTER TABLE events DROP COLUMN IF EXISTS point_validity_start;
ALTER TABLE events DROP COLUMN IF EXISTS point_validity_end;

ALTER TABLE matches DROP COLUMN IF EXISTS designated_points;

DROP TABLE IF EXISTS club_point_budgets;
