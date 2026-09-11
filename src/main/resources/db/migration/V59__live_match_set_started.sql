-- V59: SET_STARTED joins the live-match event kinds (#984).
--
-- Awarding a set used to roll straight into the next one, so the match was already mid-set-two the
-- instant a set ended — and the moment to ask "is this over?" never existed. That is why a match played
-- to a normal finish could never be finalized: Finalize needs a declared outcome, and the only ways to
-- declare one were a retirement or a default. SET_STARTED splits the two steps, creating the pause
-- where the umpire either plays on or finalizes.
--
-- Carries no payload, exactly like TIEBREAK_STARTED / MATCH_STARTED / PAUSED / RESUMED, so it joins
-- that arm of the payload rule rather than needing one of its own.
--
-- No backfill, and that is a fact rather than a hope: this kind has never been written, so no existing
-- row can violate either constraint. Both are replaced wholesale (Postgres cannot extend a CHECK in
-- place), which is the same shape V56 used when it added the three above.

ALTER TABLE live_match_events DROP CONSTRAINT chk_live_match_events_kind;
ALTER TABLE live_match_events ADD CONSTRAINT chk_live_match_events_kind CHECK (kind IN (
    'POINT_WON', 'GAME_AWARDED', 'SET_AWARDED', 'SET_STARTED', 'TIEBREAK_STARTED',
    'SERVER_ASSIGNED', 'RETIRED', 'DEFAULTED', 'MATCH_AWARDED', 'UNDONE',
    'MATCH_STARTED', 'PAUSED', 'RESUMED'));

ALTER TABLE live_match_events DROP CONSTRAINT chk_live_match_events_payload;
ALTER TABLE live_match_events ADD CONSTRAINT chk_live_match_events_payload CHECK (
    (kind = 'UNDONE'          AND target_sequence IS NOT NULL AND side IS NULL AND player_id IS NULL) OR
    (kind = 'SERVER_ASSIGNED' AND player_id       IS NOT NULL AND side IS NULL AND target_sequence IS NULL) OR
    (kind IN ('TIEBREAK_STARTED', 'SET_STARTED', 'MATCH_STARTED', 'PAUSED', 'RESUMED')
         AND side IS NULL AND player_id IS NULL AND target_sequence IS NULL) OR
    (kind IN ('POINT_WON', 'GAME_AWARDED', 'SET_AWARDED', 'RETIRED', 'DEFAULTED', 'MATCH_AWARDED')
         AND side IS NOT NULL AND player_id IS NULL AND target_sequence IS NULL));
