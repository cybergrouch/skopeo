-- Calibration period for manually-rated accounts (#881).
--
-- A rating assigned by a human is a guess. While it is being calibrated, the player's own rating moves
-- but their opponents'/partners' do not, so a mis-assessment cannot permanently drag settled players
-- with it. The window runs from the designation until the Nth RATED match, where N is a global
-- app-setting (default 10) read at evaluation time.
--
-- This column records only WHEN the window opened. Whether a player is currently calibrating is
-- DERIVED -- (calibration_started_at, count of rated matches since, current N) -- deliberately not
-- stored: N is global and mutable, so lowering it from 10 to 5 must end several in-flight calibrations
-- at once with no backfill or sweep. A stored boolean would need exactly that sweep.
--
-- Prospective by construction: every existing row is NULL, so nobody enters calibration retroactively.
-- Applying it to existing manually-rated players would silently freeze rating changes for settled
-- players the moment this deploys.
--
-- Nullable with no default and no constraint tightening, so there is no precondition to backfill
-- (see docs/engineering/operations/DB_MIGRATIONS.md).
ALTER TABLE user_ratings
    ADD COLUMN calibration_started_at TIMESTAMP NULL;

COMMENT ON COLUMN user_ratings.calibration_started_at IS
    'When the current calibration window opened -- stamped on every manual rating designation (#881). '
    'NULL means never manually designated since the feature shipped. Whether calibration is ACTIVE is '
    'derived from this plus the rated-match count and the global N, never stored.';
