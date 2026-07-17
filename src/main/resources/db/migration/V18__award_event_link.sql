-- V18: link a ranking-point award back to the event that awarded it (#403 Phase D).
--
-- Finalize-time awarding (POINTS_AWARDING_AND_BUDGET.md §2.5) writes one ledger row per winning-team
-- member for each qualifying fixture of a finalized TOURNAMENT / LEAGUE event. This column records
-- WHICH event produced the award so the budget accounting can sum a club's active awards per event
-- type (the reserve -> award transition, §3) by joining the ledger to events.
--
-- Nullable: manual / external / ad-hoc grants (#146) carry no event linkage and leave this null; only
-- finalize awards set it. ON DELETE SET NULL keeps the append-only ledger row (and the player's owned
-- points) intact if the event is ever hard-deleted — the award survives, it just loses its link.

ALTER TABLE ranking_point_awards
    ADD COLUMN event_id UUID NULL REFERENCES events(id) ON DELETE SET NULL;
COMMENT ON COLUMN ranking_point_awards.event_id IS
    'Event that produced this award on finalize (#403 Phase D); null for manual / external grants.';

CREATE INDEX idx_ranking_point_awards_event ON ranking_point_awards (event_id);
