-- V38: remove the LEAGUE event type and the LEAGUE_PLAY / LEAGUE_PLAYOFFS match types (#669).
--
-- Event type and match type are collapsed to exactly two values each so they align 1:1:
--   EventType = { OPEN_PLAY, TOURNAMENT }
--   MatchType = { OPEN_PLAY, TOURNAMENT }
--
-- Any existing LEAGUE data reclassifies to OPEN_PLAY (the confirmed decision — there is no known live
-- LEAGUE data, so this is a defensive, idempotent backfill that MUST run before the trimmed enums are
-- relied on). events.type and matches.match_type are plain VARCHAR columns with NO CHECK constraint
-- or enum type enumerating allowed values (see V1 / V15 / V29), so there is nothing to ALTER beyond
-- the data reclassification. Re-running is a no-op once no LEAGUE rows remain.

UPDATE events SET type = 'OPEN_PLAY' WHERE type = 'LEAGUE';

UPDATE matches SET match_type = 'OPEN_PLAY' WHERE match_type IN ('LEAGUE_PLAY', 'LEAGUE_PLAYOFFS');
