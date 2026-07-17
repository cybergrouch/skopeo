-- V19: link a ranking-point award back to the specific MATCH that granted it (#448).
--
-- V18 added event_id (which event produced the award on finalize); the points audit on the profile
-- (#448) needs to link each active award to the exact winning FIXTURE, not just the event. Finalize
-- awarding (EventFinalizeAwarder) iterates each qualifying match, so it can record the match id here.
--
-- Nullable: manual / external / ad-hoc grants (#146) and every pre-V19 finalize award carry no match
-- linkage and leave this null; the audit row then falls back to linking the event (event_id). ON
-- DELETE SET NULL keeps the append-only ledger row (and the player's owned points) intact if the match
-- is ever hard-deleted — the award survives, it just loses its match link.

ALTER TABLE ranking_point_awards
    ADD COLUMN match_id UUID NULL REFERENCES matches(id) ON DELETE SET NULL;
COMMENT ON COLUMN ranking_point_awards.match_id IS
    'Match (fixture) that granted this award on finalize (#448); null for manual grants and pre-V19 awards.';

CREATE INDEX idx_ranking_point_awards_match ON ranking_point_awards (match_id);
