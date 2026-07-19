-- V21: per-side, per-fixture rating handicap (#486).
--
-- A handicap is a fairness safety-valve an organizer sets on a fixture so a physically lopsided —
-- but competitively rated — matchup produces fairer rating changes. It is a per-side deduction in
-- team-mean (player-level) NTRP units, applied ONLY to that side's rating for the rating-delta
-- computation; the resulting delta is applied to the players' TRUE ratings (band/points/confidence
-- keep using the true rating). See docs/product/RATING_HANDICAP.md.
--
-- Two nullable columns on matches, one per side; null = no handicap. Range 0 < h <= 1.0 is enforced
-- in the DTO/model init and mirrored here with a CHECK for a defence-in-depth invariant. Editable only
-- while the match is unrated (rated_at IS NULL), guarded in MatchService like designated_points.
-- NUMERIC(4,3) holds 3 decimals over (0.000, 1.000], matching the calculator's precision needs.

ALTER TABLE matches ADD COLUMN team1_handicap NUMERIC(4, 3) NULL;
ALTER TABLE matches ADD COLUMN team2_handicap NUMERIC(4, 3) NULL;

ALTER TABLE matches ADD CONSTRAINT chk_team1_handicap
    CHECK (team1_handicap IS NULL OR (team1_handicap > 0 AND team1_handicap <= 1.0));
ALTER TABLE matches ADD CONSTRAINT chk_team2_handicap
    CHECK (team2_handicap IS NULL OR (team2_handicap > 0 AND team2_handicap <= 1.0));

COMMENT ON COLUMN matches.team1_handicap IS
    'Per-side rating handicap (#486) for team1, in team-mean NTRP units; null = none. 0 < h <= 1.0. Deducted from the side for the delta calc only; applied delta hits true ratings.';
COMMENT ON COLUMN matches.team2_handicap IS
    'Per-side rating handicap (#486) for team2, in team-mean NTRP units; null = none. 0 < h <= 1.0. Deducted from the side for the delta calc only; applied delta hits true ratings.';
