-- V45: events.club_id's FK becomes ON DELETE RESTRICT (#800).
--
-- THE CONTRADICTION THIS RESOLVES. V6 (#313) added the column as
--
--     ALTER TABLE events ADD COLUMN club_id UUID REFERENCES clubs(id) ON DELETE SET NULL
--
-- back when a clubless "open" event was legitimate. V44 (#794) then made the column NOT NULL. The two
-- rules disagree: a hard DELETE FROM clubs would make Postgres try to NULL out events.club_id to satisfy
-- the FK and immediately violate the NOT NULL, failing with a confusing 23502 on `events` instead of
-- refusing the delete. Unreachable through the application -- clubs are only ever SOFT-deleted
-- (ClubService.disable flips is_active and soft-deletes the club's events; ClubRepository never deletes
-- a clubs row) -- but reachable by hand from psql or a future cleanup script, which is not hypothetical
-- (#798 was exactly that).
--
-- RESTRICT, NOT CASCADE. Cascading a club delete into its events would silently destroy the match and
-- rating history hanging off them. Refusing the delete, so the caller soft-deletes instead, is what the
-- application already does.
--
-- WHY A DO BLOCK. Postgres has no ALTER ... ON DELETE, so changing the action is a drop-and-add. V6
-- declared the constraint without a name, so it carries Postgres' generated one -- confirmed as
-- `events_club_id_fkey` against a scratch database built from every migration in order. Rather than
-- trusting that name to be identical in every environment, the drop looks it up in pg_constraint by what
-- it actually constrains (the FK on events.club_id) and drops whatever it is called there. The re-add is
-- explicitly named, so every environment converges on `events_club_id_fkey` from here on.
--
-- NO BACKFILL, AND SAFE ON EXISTING DATA. Only the delete action changes; the column, its values and the
-- referential relationship are untouched. Every existing row already satisfies the constraint being
-- re-added (it is the same FK), so there is nothing to clean up first and nothing that can fail
-- validation -- unlike V44, whose NOT NULL had to be pre-flighted. So the backfill-first rule in
-- docs/engineering/operations/DB_MIGRATIONS.md has nothing to bite on here: this is not a tightening, it is
-- a swap of one always-satisfied constraint for another. Verified anyway against a scratch database carrying
-- clubs and events with data, not just the empty one CI migrates (#799).

DO $$
DECLARE
    existing_name TEXT;
BEGIN
    SELECT c.conname
    INTO existing_name
    FROM pg_constraint c
    JOIN pg_class t ON t.oid = c.conrelid
    JOIN pg_namespace n ON n.oid = t.relnamespace
    WHERE c.contype = 'f'
      AND t.relname = 'events'
      AND n.nspname = current_schema()
      AND c.confrelid = 'clubs'::regclass
      AND c.conkey = ARRAY[
          (SELECT a.attnum
           FROM pg_attribute a
           WHERE a.attrelid = t.oid AND a.attname = 'club_id' AND NOT a.attisdropped)
      ]::SMALLINT[];

    IF existing_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE events DROP CONSTRAINT %I', existing_name);
    END IF;
END
$$;

ALTER TABLE events
    ADD CONSTRAINT events_club_id_fkey
    FOREIGN KEY (club_id) REFERENCES clubs (id) ON DELETE RESTRICT;

COMMENT ON COLUMN events.club_id IS
    'The club this event is filed under (#313). Required since #794: every organizer surface is club-scoped, so a clubless event has no home. ON DELETE RESTRICT since #800: a club with events cannot be hard-deleted -- soft-delete it instead (ClubService.disable).';
