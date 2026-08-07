-- V39: let a seeding belong to either a player list (#111) OR an event's participants (#714).
--
-- The seeding store was list-keyed (seedings.list_id NOT NULL, one per list). Event Organizer now
-- generates the same deterministic seeding from an event's roster, so the store is generalized to a
-- source-keyed shape while preserving FK integrity for BOTH sources (no polymorphic id-without-FK):
--   * list_id becomes NULLABLE (existing rows all have it set — no backfill needed).
--   * a new NULLABLE event_id FK → events(id) ON DELETE CASCADE (a deleted event drops its seeding).
--   * a CHECK enforces that EXACTLY ONE of (list_id, event_id) is set.
--   * a unique index per source keeps "one current seeding per source" (regenerate overwrites).
--
-- The existing uq_seedings_list UNIQUE(list_id) already tolerates the new NULLs (Postgres treats NULLs
-- as distinct), so it keeps enforcing one-per-list; we add the matching one-per-event index.

ALTER TABLE seedings
    ALTER COLUMN list_id DROP NOT NULL;

ALTER TABLE seedings
    ADD COLUMN event_id UUID;

ALTER TABLE seedings
    ADD CONSTRAINT fk_seedings_event FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE CASCADE;

ALTER TABLE seedings
    ADD CONSTRAINT ck_seedings_exactly_one_source
        CHECK ((list_id IS NOT NULL) <> (event_id IS NOT NULL));

CREATE UNIQUE INDEX uq_seedings_event ON seedings(event_id);

COMMENT ON COLUMN seedings.event_id IS 'Source event whose participants were seeded (#714); mutually exclusive with list_id';
