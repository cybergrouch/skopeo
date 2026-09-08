-- V56: an official match start, and pause/resume (#911).
--
-- Three kinds join chk_live_match_events_kind. Each carries no side and no player, so the payload rule
-- treats them exactly like TIEBREAK_STARTED.
--
-- WHY PAUSE EXISTS, since it replaces a design that was heading elsewhere. §8a had the sweep resolving
-- "abandoned" scoring sessions on a staleness timeout. That cannot work: a match suspended for weather
-- may resume DAYS later, on a court the host cannot yet book, so no timeout distinguishes "abandoned"
-- from "waiting for a court" without being wrong in one direction. Saying it explicitly is the only
-- honest answer — a paused match is paused, not stale — and it is what lets the log be kept until
-- finalize rather than swept.
--
-- MATCH_STARTED is separate from the first point on purpose. The interval between the umpire opening the
-- app and the players actually starting is exactly what would corrupt a match-duration figure. Every row
-- here already carries recorded_at, so with a start anchor and pause/resume pairs the playing time is
-- derivable: (last event - MATCH_STARTED) minus the sum of the pause intervals.
--
-- A WIDENING, so every existing row already satisfies it and there is nothing to backfill.
ALTER TABLE live_match_events DROP CONSTRAINT chk_live_match_events_kind;
ALTER TABLE live_match_events ADD CONSTRAINT chk_live_match_events_kind CHECK (kind IN (
    'POINT_WON', 'GAME_AWARDED', 'SET_AWARDED', 'TIEBREAK_STARTED',
    'SERVER_ASSIGNED', 'RETIRED', 'DEFAULTED', 'MATCH_AWARDED', 'UNDONE',
    'MATCH_STARTED', 'PAUSED', 'RESUMED'));

ALTER TABLE live_match_events DROP CONSTRAINT chk_live_match_events_payload;
ALTER TABLE live_match_events ADD CONSTRAINT chk_live_match_events_payload CHECK (
    (kind = 'UNDONE'          AND target_sequence IS NOT NULL AND side IS NULL AND player_id IS NULL) OR
    (kind = 'SERVER_ASSIGNED' AND player_id       IS NOT NULL AND side IS NULL AND target_sequence IS NULL) OR
    (kind IN ('TIEBREAK_STARTED', 'MATCH_STARTED', 'PAUSED', 'RESUMED')
         AND side IS NULL AND player_id IS NULL AND target_sequence IS NULL) OR
    (kind IN ('POINT_WON', 'GAME_AWARDED', 'SET_AWARDED', 'RETIRED', 'DEFAULTED', 'MATCH_AWARDED')
         AND side IS NOT NULL AND player_id IS NULL AND target_sequence IS NULL));
