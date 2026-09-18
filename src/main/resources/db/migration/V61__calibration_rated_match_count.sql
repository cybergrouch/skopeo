-- Make calibration QUERYABLE by storing the rated-match COUNT, never the verdict (#1051).
--
-- Calibration (#881) is one comparison:
--
--     in_calibration  =  rated_matches_since(calibration_started_at)  <  N
--                        ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^      ^
--                        an aggregate over the match tables               live global setting
--
-- V48 stored the window's START and left the whole comparison derived, because N is a global, mutable
-- admin setting: lowering it from 10 to 5 has to end several in-flight calibrations AT ONCE, which a
-- stored boolean could only do with a sweep -- and would be wrong until the sweep ran. That reasoning is
-- untouched here. THE VERDICT IS STILL DERIVED. What moves into the schema is only the aggregate, which
-- is a fact about matches rather than a judgement: it changes when a match becomes rated or stops being
-- rated, and never because someone edited a setting.
--
-- The consequence is that calibration becomes a plain WHERE and ORDER BY on user_ratings, which is what
-- #1050 had to defer: it could not filter or sort on calibration without either reimplementing
-- CalibrationService's rule in SQL (the drift #882 records) or pulling every row into memory and
-- breaking pagination.
--
-- MEANING of the column: how many matches have counted as RATED for this player since their current
-- window opened. 0 when there is no window (calibration_started_at IS NULL) -- unused rather than
-- meaningful there, since CalibrationService answers "not calibrating" from the null stamp alone.
--
-- NOT the same as the two counters already on this table. matches_played counts every applied match
-- regardless of window, and matches_since_reset ALSO resets on an NTRP band jump (#343) -- a band jump
-- mid-calibration would silently restart the clock and extend the window -- besides being vestigial for
-- confidence (#459). Overloading either would give one column two meanings.
--
-- BACKFILL (see docs/engineering/operations/DB_MIGRATIONS.md). NOT NULL DEFAULT 0 fills every existing
-- row, and 0 is already correct for the pre-#881 rows: a null stamp means no window, which is what makes
-- the rollout prospective. It is WRONG for anyone designated since #881 shipped -- leaving a part-way
-- window at 0 would restart their clock and suppress their opponents' rating changes for another N
-- matches, silently. So the statement below derives the real count for exactly those rows, and the
-- column default remains the unconditional catch-all underneath it.
--
-- The derivation must agree EXACTLY with MatchRepository.countRatedMatchesSince, which is the single
-- definition this column caches. Point for point, that query is:
--   * the match is active (soft-deleted matches count for nothing else, so not for the clock either);
--   * its event container is active, or it has no event (events.is_active only -- a club's own is_active
--     is not in the predicate; ClubService.delete cascades onto its events explicitly);
--   * rated_at IS NOT NULL and rated_at > calibration_started_at (rated, not merely completed, and only
--     since the designation);
--   * the player is on either side via team_users, UNFILTERED BY left_at -- team_users.left_at is not
--     even mapped in Exposed, so filtering it here would make the backfill disagree with every read;
--   * counted once per match, so a degenerate self-vs-self row cannot count twice.
ALTER TABLE user_ratings
    ADD COLUMN calibration_matches_rated INTEGER NOT NULL DEFAULT 0;

UPDATE user_ratings ur
SET calibration_matches_rated = (
        SELECT count(*)
        FROM matches m
                 LEFT JOIN events e ON e.id = m.event_id
        WHERE m.is_active
          AND (m.event_id IS NULL OR e.is_active)
          AND m.rated_at IS NOT NULL
          AND m.rated_at > ur.calibration_started_at
          AND EXISTS (SELECT 1
                      FROM team_users tu
                      WHERE tu.user_id = ur.user_id
                        AND tu.team_id IN (m.team1_id, m.team2_id))
    )
WHERE ur.calibration_started_at IS NOT NULL;

COMMENT ON COLUMN user_ratings.calibration_matches_rated IS
    'Rated matches counted toward the CURRENT calibration window (#1051) -- a cache of '
    'MatchRepository.countRatedMatchesSince, maintained by the repository write paths that change which '
    'matches are rated (markRated, clearRatedForEvent, match/event soft-delete, participation merges) and '
    'reset to 0 by every fresh designation. 0 when calibration_started_at IS NULL. Whether calibration is '
    'ACTIVE is still derived -- this count against the live global N -- so changing N still takes effect '
    'for everyone at once, with no sweep.';
