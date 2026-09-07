-- V50: every match belongs to an event (#898).
--
-- matches.event_id has been nullable purely for back-compat since #138 added events to a schema that
-- already had matches ("Optional owning event (#138); nullable for back-compat" — V1). No product
-- surface can produce an eventless match: the web has one fixture form (EventFixtureForm) and it is
-- event-scoped by construction, and the backend has one insert path (MatchRepository.createFixture).
-- Only a direct POST /api/v1/matches with eventId omitted could, which this migration closes for good.
--
-- Hardening this makes match_number (the follow-up in #898) total: every match has an event to be
-- "#1 of", so the number can be NOT NULL with a plain unique index rather than a partial one.

-- Precondition guard. There is deliberately no backfill here: an eventless match has no event to
-- infer, so inventing one would be worse than refusing. Production held zero such rows when this was
-- written, and Flyway wraps the migration in a transaction, so a non-empty database rolls back
-- cleanly with an actionable message instead of Postgres's bare "column contains null values".
--
-- Note for anyone reading this in a failure: PostgresTestDatabase migrates a fresh, empty container,
-- so CI proves nothing about production here (#799). This guard is the check that actually runs.
DO $$
DECLARE
    orphan_count BIGINT;
BEGIN
    SELECT count(*) INTO orphan_count FROM matches WHERE event_id IS NULL;
    IF orphan_count > 0 THEN
        RAISE EXCEPTION
            'V50 cannot proceed: % match(es) have no event_id. Every match must belong to an event '
            '(#898). Assign each to an event, or soft-delete and re-file it, then re-run. '
            'Inspect them with: SELECT id, match_date, status, rated_at, is_active FROM matches '
            'WHERE event_id IS NULL ORDER BY match_date;', orphan_count;
    END IF;
END $$;

ALTER TABLE matches ALTER COLUMN event_id SET NOT NULL;

-- The V1 foreign key declared ON DELETE SET NULL, which can no longer satisfy a NOT NULL column: a
-- hard delete of an events row would now fail with a null-violation rather than the intended cascade
-- behaviour. Nothing exercises it today (EventService.delete is a soft delete that flips is_active),
-- but leaving a contradictory action in place is a trap. RESTRICT states the real rule: an event with
-- matches cannot be hard-deleted, and deletion goes through the soft-delete path that already exists.
ALTER TABLE matches DROP CONSTRAINT fk_matches_event;
ALTER TABLE matches ADD CONSTRAINT fk_matches_event
    FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE RESTRICT;

COMMENT ON COLUMN matches.event_id IS
    'Owning event (#138). NOT NULL since #898 — every match belongs to an event.';
