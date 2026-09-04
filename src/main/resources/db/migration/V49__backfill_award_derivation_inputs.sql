-- Backfill the derivation inputs on awards paid before #862 recorded them (#892).
--
-- #862 made every award explain itself: how the amount was reached, per set, from the schedule version
-- and the two band strings the award records. Awards paid BEFORE that have no bands, so every one of them
-- reports "This award predates the change that records how amounts are derived" — measured on production,
-- that was 48 of 48 awards, i.e. the feature was inert on all real data.
--
-- The inputs are recoverable without guessing, because each award already records ITS OWN recipient's
-- band (tagged at award time). So for a match with one award per side:
--
--     team_band     = this award's own band
--     opponent_band = the band on the award on the other side of the same match
--
-- Both are historical values captured when the award was written, not present-day lookups, so the band
-- drift of #882 cannot corrupt them.
--
-- WHY THIS IS SAFE TO DO IN SQL, WITHOUT RECOMPUTING ANYTHING HERE
--
-- Filling inputs does not prove the arithmetic still reproduces the amount paid: the pre-V47
-- `points_config` was upsert-keyed, so a schedule edited back then overwrote its predecessor and
-- `points_schedule_version = 1` may not hold the rates these awards were actually paid under.
--
-- Rather than reimplement the dominance table in SQL to check — duplicating the algorithm in a second
-- language, which is exactly the divergence that caused #882 — the check lives at READ time, in
-- `AwardDerivationAssembler`: it recomputes, compares against the paid figure, and reports the award
-- unexplainable when they disagree. That guarantee covers every award forever, not just the rows this
-- migration touches, so this file can be pure data movement.
--
-- SINGLES ONLY, DELIBERATELY — AND GUARDED ON BOTH SIDES
--
-- In doubles a team's band is the MEAN of its two members' bands (see EventFinalizeAwarder.teamBand),
-- which is not what the join below produces: it would take this award's own band as the team band, and
-- one arbitrary opponent's band as the opponent band. Both are wrong whenever the pair's bands differ.
--
-- So the update requires exactly one distinct band on EACH side:
--
--   * `distinct_opponent_bands = 1` — otherwise the opponent band would be an arbitrary pick;
--   * `distinct_own_bands = 1`      — otherwise the team band would be this player's rather than the
--                                     team's. Guarding only the opponent side would still write a wrong
--                                     team band for a mixed-band pair, which is subtler and just as bad.
--
-- Anything else is skipped and keeps its honest "not recorded" message. Production has zero doubles
-- fixtures among the affected awards, so nothing is skipped today — the guards make this safe by
-- construction rather than by luck. Note a doubles pair whose members share a band satisfies both
-- conditions and is filled correctly, since the mean of two equal bands is that band.
--
-- Fills NULLs only, so an input recorded correctly at award time can never be overwritten.
-- No constraint is tightened, so there is no precondition to satisfy (docs/.../DB_MIGRATIONS.md).
WITH sides AS (
    SELECT a.id       AS award_id,
           a.match_id AS match_id,
           a.band     AS band,
           CASE WHEN tu.team_id = m.team1_id THEN 1 ELSE 2 END AS side
    FROM ranking_point_awards a
             JOIN matches m ON m.id = a.match_id
             JOIN team_users tu
                  ON tu.user_id = a.user_id
                      AND tu.team_id IN (m.team1_id, m.team2_id)
    WHERE a.match_id IS NOT NULL
      AND a.band IS NOT NULL
),
resolved AS (
    SELECT s.award_id,
           s.band                 AS team_band,
           min(o.band)            AS opponent_band,
           count(DISTINCT o.band) AS distinct_opponent_bands,
           -- The award's OWN side, counted the same way: with a mixed-band doubles pair this is > 1 and
           -- `s.band` would be that one player's band rather than the team's mean.
           (SELECT count(DISTINCT w.band)
              FROM sides w
             WHERE w.match_id = s.match_id
               AND w.side = s.side) AS distinct_own_bands
    FROM sides s
             JOIN sides o
                  ON o.match_id = s.match_id
                      AND o.side <> s.side
    GROUP BY s.award_id, s.band, s.match_id, s.side
)
UPDATE ranking_point_awards a
SET team_band     = r.team_band,
    opponent_band = r.opponent_band
FROM resolved r
WHERE a.id = r.award_id
  -- One unambiguous band on each side, or leave the award alone.
  AND r.distinct_opponent_bands = 1
  AND r.distinct_own_bands = 1
  -- Never overwrite an input that was recorded properly at award time.
  AND (a.team_band IS NULL OR a.opponent_band IS NULL);
