-- V17: fixture point designation + event points config (#403 Phase C).
--
-- Where the club budget actually gets spent: an event of a budgeted type (TOURNAMENT / LEAGUE)
-- captures a per-match reward window, and each of its fixtures *designates* points for the eventual
-- winner. Two additions, no new table:
--   1. events — the per-event points config: min/max points per match (validated against the global
--      per-type policy) and the point validity window (start/end). All nullable — required only for
--      TOURNAMENT / LEAGUE and enforced in the service; OPEN_PLAY / clubless carry no config.
--   2. matches — designated_points: how many points this fixture designates for the winner. Nullable;
--      null for OPEN_PLAY / event-less fixtures (no budget source, no reservation).
--
-- Reservation is EMERGENT — there is deliberately NO reservation table and no release logic. The
-- club's Reserved for a (club, type) is computed by summing the designations of its active,
-- non-finalized fixtures × team size; voiding / cancelling a fixture drops it from that sum, which is
-- the "release". See PointsBudgetRepository.sumReservedPoints and POINTS_AWARDING_AND_BUDGET.md §3.

ALTER TABLE events ADD COLUMN min_points_per_match INT NULL;
ALTER TABLE events ADD COLUMN max_points_per_match INT NULL;
ALTER TABLE events ADD COLUMN point_validity_start DATE NULL;
ALTER TABLE events ADD COLUMN point_validity_end DATE NULL;
COMMENT ON COLUMN events.min_points_per_match IS
    'Event points config (#403 Phase C): min per-match reward; required for TOURNAMENT/LEAGUE, null for OPEN_PLAY.';
COMMENT ON COLUMN events.max_points_per_match IS
    'Event points config (#403 Phase C): max per-match reward; required for TOURNAMENT/LEAGUE, null for OPEN_PLAY.';
COMMENT ON COLUMN events.point_validity_start IS
    'Event points config (#403 Phase C): first day an awarded point is valid; required for TOURNAMENT/LEAGUE.';
COMMENT ON COLUMN events.point_validity_end IS
    'Event points config (#403 Phase C): last day an awarded point is valid; required for TOURNAMENT/LEAGUE.';

ALTER TABLE matches ADD COLUMN designated_points INT NULL;
COMMENT ON COLUMN matches.designated_points IS
    'Points designated for the winner (#403 Phase C); null for OPEN_PLAY / event-less fixtures. Cost = designated x team size; reservation is emergent (summed, no table).';
