-- V65: a per-player calibration override (#1126).
--
-- Calibration (#881) is DERIVED on every read: a player is calibrating while their rated matches since the
-- last manual designation (`calibration_matches_rated`, #1051) are below the global N. That stays. What is
-- added is the one thing a derivation cannot express: a person's decision to end calibration early or keep
-- it going. Only the override is stored, never the verdict, so changing N still applies instantly to every
-- AUTOMATIC player with no sweep.
--
--   AUTOMATIC  (default) the derived rule
--   FORCED_OFF out of calibration whatever the count
--   FORCED_ON  in calibration whatever the count
--
-- Who set it, when and why sit beside it, so the Ratings tab can show the reason without reading the audit
-- log (the Activity Log has the full history). A manual re-rating resets all four (RatingRepository.setRating).
--
-- Not a tightening of existing data: the NOT NULL column arrives WITH its default, so Postgres fills every
-- existing row with 'AUTOMATIC' in the same statement, and the CHECK is satisfied by that value. Every
-- existing player keeps exactly today's behaviour (docs/engineering/operations/DB_MIGRATIONS.md).
ALTER TABLE user_ratings
    ADD COLUMN calibration_override VARCHAR(16) NOT NULL DEFAULT 'AUTOMATIC',
    ADD COLUMN calibration_override_reason TEXT,
    ADD COLUMN calibration_override_by UUID REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN calibration_override_at TIMESTAMP;

ALTER TABLE user_ratings
    ADD CONSTRAINT chk_calibration_override CHECK (calibration_override IN ('AUTOMATIC', 'FORCED_OFF', 'FORCED_ON'));

COMMENT ON COLUMN user_ratings.calibration_override IS
    'Per-player calibration override (#1126): AUTOMATIC (derived rule), FORCED_OFF or FORCED_ON. The verdict '
    'itself is never stored; CalibrationService evaluates it from this, the stored count and the global N.';
