-- V62: GAME_STARTED joins the live-match event kinds (#1083).
--
-- The umpire view derives every control's visibility from one state machine, and it had no state for
-- "in a set, between games". Points were therefore live the instant a set started and stayed live after
-- every game closed, so a tap on a score box could land nowhere in particular -- and there was no moment
-- at which starting a tiebreak was the alternative to starting a game, which is what makes the decision
-- at 6-6 a decision. GAME_STARTED is the same split SET_STARTED made one level up (V59).
--
-- Carries no payload, exactly like SET_STARTED / TIEBREAK_STARTED / MATCH_STARTED / PAUSED / RESUMED, so
-- it joins that arm of the payload rule rather than needing one of its own.
--
-- No backfill, and that is a fact rather than a hope: this kind has never been written, so no existing
-- row can violate either constraint. Both are replaced wholesale (Postgres cannot extend a CHECK in
-- place), which is the shape V56 and V59 both used.
--
-- Logs written before this deploys stay valid and replay to a usable state: with no GAME_STARTED in
-- them, a match mid-set derives as "between games", so the umpire presses Start game once and continues.
-- Nothing is lost -- the banked games and the set in progress are untouched.

ALTER TABLE live_match_events DROP CONSTRAINT chk_live_match_events_kind;
ALTER TABLE live_match_events ADD CONSTRAINT chk_live_match_events_kind CHECK (kind IN (
    'POINT_WON', 'GAME_AWARDED', 'SET_AWARDED', 'SET_STARTED', 'GAME_STARTED', 'TIEBREAK_STARTED',
    'SERVER_ASSIGNED', 'RETIRED', 'DEFAULTED', 'MATCH_AWARDED', 'UNDONE',
    'MATCH_STARTED', 'PAUSED', 'RESUMED'));

ALTER TABLE live_match_events DROP CONSTRAINT chk_live_match_events_payload;
ALTER TABLE live_match_events ADD CONSTRAINT chk_live_match_events_payload CHECK (
    (kind = 'UNDONE'          AND target_sequence IS NOT NULL AND side IS NULL AND player_id IS NULL) OR
    (kind = 'SERVER_ASSIGNED' AND player_id       IS NOT NULL AND side IS NULL AND target_sequence IS NULL) OR
    (kind IN ('TIEBREAK_STARTED', 'SET_STARTED', 'GAME_STARTED', 'MATCH_STARTED', 'PAUSED', 'RESUMED')
         AND side IS NULL AND player_id IS NULL AND target_sequence IS NULL) OR
    (kind IN ('POINT_WON', 'GAME_AWARDED', 'SET_AWARDED', 'RETIRED', 'DEFAULTED', 'MATCH_AWARDED')
         AND side IS NOT NULL AND player_id IS NULL AND target_sequence IS NULL));
