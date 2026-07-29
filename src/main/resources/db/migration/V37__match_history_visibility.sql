-- V37: per-player "hide match history from other players" privacy flag (#622).
--
-- Default FALSE preserves today's behaviour (match history public to everyone). When TRUE, the
-- server withholds the player's match history from unprivileged viewers (see PlayerService); the
-- owner and elevated roles still see it.

ALTER TABLE users
    ADD COLUMN match_history_hidden BOOLEAN NOT NULL DEFAULT FALSE;
