-- V29: widen matches.placement_bracket and matches.match_type (#462, #552).
--
-- The placement-bracket taxonomy grows (#552) from SUPER_FINALS/PLATE_FINALS to CHAMPIONSHIP_FINALS,
-- SEMI_FINALS_NO_PLATE, SEMI_FINALS_WITH_PLATE, PLATE_FINALS. SEMI_FINALS_WITH_PLATE is 22 chars and
-- match_type's TOURNAMENT_INITIAL_ROUND is 24 — both past the old VARCHAR(20) (#462). Widen both, and
-- migrate the existing SUPER_FINALS value name.

ALTER TABLE matches ALTER COLUMN placement_bracket TYPE VARCHAR(32);
ALTER TABLE matches ALTER COLUMN match_type TYPE VARCHAR(32);

UPDATE matches SET placement_bracket = 'CHAMPIONSHIP_FINALS' WHERE placement_bracket = 'SUPER_FINALS';
