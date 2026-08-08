-- V40: promote match format to the Event as a REQUIRED (NOT NULL) field (#720).
--
-- An event now carries a single organizing FORMAT (SINGLES / DOUBLES / MIXED_DOUBLES). It is the
-- organizing constraint behind durable, event-scoped teams (V41) — it sets each team's size (1 for
-- singles, 2 for doubles/mixed) — and it is the default format for fixtures created within the event
-- (still overridable per fixture; the per-match match_format column stays). This is distinct from the
-- event-class `type` (#403: OPEN_PLAY / TOURNAMENT).
--
-- The column is NOT NULL. Existing rows are backfilled: derive each event's format from the most
-- common format among its own matches (deterministic tiebreak by name), falling back to SINGLES for
-- events with no matches to derive from.

ALTER TABLE events ADD COLUMN format VARCHAR(20);

-- Backfill from the event's matches: the most frequent match_format wins; ties break alphabetically
-- so the migration is deterministic.
UPDATE events e
SET format = ranked.match_format
FROM (
    SELECT event_id, match_format
    FROM (
        SELECT
            event_id,
            match_format,
            ROW_NUMBER() OVER (
                PARTITION BY event_id
                ORDER BY COUNT(*) DESC, match_format ASC
            ) AS rn
        FROM matches
        WHERE event_id IS NOT NULL
        GROUP BY event_id, match_format
    ) counted
    WHERE rn = 1
) ranked
WHERE e.id = ranked.event_id;

-- Events with no matches to derive from default to SINGLES.
UPDATE events SET format = 'SINGLES' WHERE format IS NULL;

-- NOT NULL, with a DB-level default of SINGLES so any insert path that omits format (raw test inserts,
-- internal helpers) is safe. Requiredness is still enforced at the API boundary (CreateEventRequest.format
-- + service validation); the default is only a safety net.
ALTER TABLE events ALTER COLUMN format SET NOT NULL;
ALTER TABLE events ALTER COLUMN format SET DEFAULT 'SINGLES';

ALTER TABLE events
    ADD CONSTRAINT chk_event_format CHECK (format IN ('SINGLES', 'DOUBLES', 'MIXED_DOUBLES'));

COMMENT ON COLUMN events.format IS
    'Event organizing format (#720): SINGLES | DOUBLES | MIXED_DOUBLES. Sets durable team size and the default fixture format (overridable). NOT NULL.';
