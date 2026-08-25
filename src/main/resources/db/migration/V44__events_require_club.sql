-- V44: every event belongs to a club (#794).
--
-- Each club now has its own event organizer on its public page (#780/#786), and the create form requires
-- a club, so a clubless ("Open") event has no home and no way to be created. This puts the invariant in
-- the schema rather than trusting the service layer alone.
--
-- WHY A CHECK CONSTRAINT AND NOT `SET NOT NULL`
--
-- Flyway runs on startup (config/DatabaseConfig.kt). A plain `ALTER COLUMN club_id SET NOT NULL` validates
-- every existing row immediately, so ONE legacy clubless row — including a soft-deleted one, which
-- NOT NULL does not care that we consider deleted — would fail the migration, fail the boot, and take
-- production down rather than degrading. A CHECK ... NOT VALID enforces the rule on every INSERT and
-- UPDATE from here on while leaving pre-existing rows alone, so the deploy cannot wedge.
--
-- Follow-up, once `SELECT count(*) FROM events WHERE club_id IS NULL` is confirmed 0 in production:
--   ALTER TABLE events VALIDATE CONSTRAINT events_club_id_present;
-- and, if desired, convert to a true NOT NULL. Both are safe at that point and neither is urgent — the
-- constraint above already stops anything new.

ALTER TABLE events
    ADD CONSTRAINT events_club_id_present CHECK (club_id IS NOT NULL) NOT VALID;

COMMENT ON CONSTRAINT events_club_id_present ON events IS
    'Every event belongs to a club (#794). NOT VALID so the deploy cannot fail on a legacy clubless row; VALIDATE once the null count is confirmed 0.';
