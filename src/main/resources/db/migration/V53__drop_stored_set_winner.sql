-- V53: drop the stored set winner (#917).
--
-- Who won a set is a pure function of that set's own games and tiebreak points, so
-- match_sets.winner_team_id and match_set_tiebreaks.winner_team_id were a stored derivation: a second
-- source of truth for something each row already determines, with nothing preventing the two from
-- disagreeing. Nothing has read them since the derivation moved into the entity->domain mapper.
--
-- Distinct from matches.winner_team_id, which STAYS. That one is the *designated* match winner, and it
-- is the one a retirement changes (#911): the opponent is awarded the match while the retiring player
-- may have been leading the unfinished set, and the rating follows the set. Separating the two is the
-- whole point of #917 -- they stop competing for one column.
--
-- Verified against a restore of production before dropping: 959 sets, zero rows where the stored winner
-- disagreed with its games, zero sets tied on games, and zero tiebreak rows at all. Fully reconstructible,
-- which is exactly when it is safe to stop storing.

ALTER TABLE match_sets DROP COLUMN winner_team_id;
ALTER TABLE match_set_tiebreaks DROP COLUMN winner_team_id;
