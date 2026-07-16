-- V15: event type + finalize state (#403 Phase A).
--
-- Two additions to the events model, ahead of the points-budget/awarding work (#403 Phase B+):
--   1. A per-event TYPE (OPEN_PLAY / LEAGUE / TOURNAMENT) — the class of event, which later drives
--      the per-type points budget. Existing rows backfill to OPEN_PLAY (the default). This is a NEW
--      event-level type, distinct from the match-level match_type rating factors (#108).
--   2. A terminal FINALIZE state — an event is finalized iff finalized_at IS NOT NULL, stamped with
--      the acting user (finalized_by). Finalizing closes the event to further changes and is what now
--      queues the event's matches for rating (the rating-queue trigger moves from result-upload time
--      to finalize time). Event-less matches keep queuing immediately.

ALTER TABLE events ADD COLUMN type VARCHAR(16) NOT NULL DEFAULT 'OPEN_PLAY';
COMMENT ON COLUMN events.type IS
    'Event class (#403): OPEN_PLAY | LEAGUE | TOURNAMENT; drives the per-type points budget (Phase B).';

ALTER TABLE events ADD COLUMN finalized_at TIMESTAMP;
ALTER TABLE events ADD COLUMN finalized_by UUID REFERENCES users(id) ON DELETE SET NULL;
COMMENT ON COLUMN events.finalized_at IS
    'When the event was finalized (#403); null while open. Finalized is terminal and queues rating.';
COMMENT ON COLUMN events.finalized_by IS
    'The user who finalized the event (#403); SET NULL on user delete.';
