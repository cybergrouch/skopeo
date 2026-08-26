-- V44: every event belongs to a club (#794).
--
-- Each club now has its own event organizer on its public page (#780/#786), and the create form requires
-- a club, so a clubless ("Open") event has no home and no way to be created. The invariant belongs in the
-- schema rather than resting on the service layer alone -- and with it in the schema, the domain model can
-- carry `clubId` as non-null instead of every call site re-checking.
--
-- PRE-FLIGHT, and why it matters: Flyway runs on startup (config/DatabaseConfig.kt), so this validates
-- every existing row immediately. One clubless row -- INCLUDING a soft-deleted one, which NOT NULL does
-- not care that we consider deleted -- fails the migration, fails the boot, and takes the service down
-- rather than degrading. Confirm zero before deploying, unfiltered by is_active:
--
--     SELECT count(*) FROM events WHERE club_id IS NULL;   -- must be 0
--
-- The clubless events that predated #794 have been re-filed under clubs, so this is expected to be 0.

ALTER TABLE events
    ALTER COLUMN club_id SET NOT NULL;

COMMENT ON COLUMN events.club_id IS
    'The club this event is filed under (#313). Required since #794: every organizer surface is club-scoped, so a clubless event has no home.';
