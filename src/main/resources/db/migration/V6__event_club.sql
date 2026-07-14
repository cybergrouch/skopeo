-- V6: optionally associate an event with a club (#313).
--
-- Nullable: an event without a club is a "generic"/"open" event. ON DELETE SET NULL so removing a
-- club doesn't cascade-delete its events — they just become clubless.

ALTER TABLE events ADD COLUMN club_id UUID REFERENCES clubs(id) ON DELETE SET NULL;

CREATE INDEX idx_events_club ON events (club_id);
