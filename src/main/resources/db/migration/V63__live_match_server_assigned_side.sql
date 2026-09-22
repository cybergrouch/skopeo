-- SERVER_ASSIGNED names a SIDE, not a player (#1098).
--
-- The serve is a consequence of scoring, so ScoreEngine now advances it as part of its (state, event)
-- fold rather than the service appending a row for each rotation. A reducer that advances a *player*
-- would need the roster in its state; advancing a *side* needs nothing. Nothing ever used the
-- individual anyway -- the umpire board is a two-side toggle that picked the side's first player as a
-- stand-in -- so the per-player column was asserting knowledge the application did not have.
--
-- DROP the old rule, BACKFILL, then add the new one -- in that order, and all three in the one
-- transaction Flyway wraps this file in, so either everything lands or nothing does.
--
-- The order is the whole difficulty, and it is pinned from both ends:
--
--   * the backfill cannot run LAST, because `ADD CONSTRAINT ... CHECK` validates existing rows and
--     would abort against any live match's log (the #799 lesson);
--   * the backfill cannot run FIRST either, because the OLD rule requires SERVER_ASSIGNED to carry
--     `player_id IS NOT NULL AND side IS NULL` -- exactly what the backfill is undoing. Writing the
--     new shape while the old rule is still enforced fails with 23514 on the UPDATE itself.
--
-- Neither CI nor a fresh dev database can catch the second one: with no SERVER_ASSIGNED rows there is
-- nothing for the UPDATE to touch, so the old constraint is never evaluated and the migration passes.
-- It surfaced on first contact with real data.
ALTER TABLE live_match_events DROP CONSTRAINT chk_live_match_events_payload;

UPDATE live_match_events e
SET side =
        CASE
            WHEN EXISTS (
                SELECT 1 FROM team_users tu
                JOIN matches m ON m.team1_id = tu.team_id
                WHERE m.id = e.match_id AND tu.user_id = e.player_id
            ) THEN 'TEAM1'
            WHEN EXISTS (
                SELECT 1 FROM team_users tu
                JOIN matches m ON m.team2_id = tu.team_id
                WHERE m.id = e.match_id AND tu.user_id = e.player_id
            ) THEN 'TEAM2'
        END,
    player_id = NULL
WHERE e.kind = 'SERVER_ASSIGNED';

-- `team_users.left_at` is deliberately NOT filtered here. No repository reads it -- team membership
-- is read unconditionally everywhere in the application -- so filtering would make this migration
-- stricter than the app's own notion of who is on a team, and a departed member would resolve to no
-- side and abort the deploy for a row that is perfectly valid.
--
-- No ELSE on that CASE, deliberately. A server who belongs to neither team is not a case to guess at:
-- the row keeps a NULL side, the constraint below rejects it, and the whole migration rolls back
-- loudly rather than silently recording the wrong side of the net.

ALTER TABLE live_match_events ADD CONSTRAINT chk_live_match_events_payload CHECK (
    (kind = 'UNDONE'          AND target_sequence IS NOT NULL AND side IS NULL AND player_id IS NULL) OR
    (kind IN ('TIEBREAK_STARTED', 'SET_STARTED', 'GAME_STARTED', 'MATCH_STARTED', 'PAUSED', 'RESUMED')
         AND side IS NULL AND player_id IS NULL AND target_sequence IS NULL) OR
    (kind IN ('POINT_WON', 'GAME_AWARDED', 'SET_AWARDED', 'RETIRED', 'DEFAULTED', 'MATCH_AWARDED',
              'SERVER_ASSIGNED')
         AND side IS NOT NULL AND player_id IS NULL AND target_sequence IS NULL));

-- The column stays. It is the only nullable reference to users on this table and dropping it would
-- forfeit the ability to record a per-player serve when doubles rotation is modelled properly -- which
-- `ServerControl` already calls a simplification with a known expiry.
COMMENT ON COLUMN live_match_events.player_id IS
    'Unused since #1098: SERVER_ASSIGNED moved to `side`. Kept for a future per-player doubles serve.';
