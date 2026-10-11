-- #1121: one-off repair. Account merge (#643) moved a retired account's matches and event entries onto the
-- kept account, but left the ranking-point awards those matches produced behind on the retired account. From
-- this release the merge moves them; this moves the ones already stranded.
--
-- Production on 2026-10-04 held 6 such awards on 2 retired accounts (BD0YG0 -> NCM4P7, 2F6YCT -> PZGX27).
--
-- Two guards keep it from moving anything a merge did not:
--   * merge chains are followed to the FINAL account (a kept account may itself have been merged later);
--   * an award moves only when that final account actually holds the award's match (for a match award) or
--     event entry (for an event award). `canonical_user_id` is also set by duplicate-marking (#124), which
--     moves no records, so "has a canonical account" alone is not evidence the play moved.
-- Awards granted by hand (no match, no event) are not tied to anything a merge moves, and stay.
--
-- Data only: no constraint changes. The POINTS standings pick the moved awards up at their next recompute.

WITH RECURSIVE chain (retired_id, target_id, depth) AS (
    SELECT id, canonical_user_id, 1
    FROM users
    WHERE canonical_user_id IS NOT NULL
    UNION ALL
    SELECT chain.retired_id, next_account.canonical_user_id, chain.depth + 1
    FROM chain
    JOIN users next_account ON next_account.id = chain.target_id
    WHERE next_account.canonical_user_id IS NOT NULL
      AND chain.depth < 32
),
final_account AS (
    SELECT DISTINCT ON (retired_id) retired_id, target_id
    FROM chain
    ORDER BY retired_id, depth DESC
)
UPDATE ranking_point_awards award
SET user_id = final_account.target_id
FROM final_account
WHERE award.user_id = final_account.retired_id
  AND (
        (award.match_id IS NOT NULL AND EXISTS (
            SELECT 1
            FROM matches m
            JOIN team_users tu ON tu.team_id IN (m.team1_id, m.team2_id)
            WHERE m.id = award.match_id
              AND tu.user_id = final_account.target_id))
     OR (award.match_id IS NULL AND award.event_id IS NOT NULL AND EXISTS (
            SELECT 1
            FROM event_participants ep
            WHERE ep.event_id = award.event_id
              AND ep.user_id = final_account.target_id))
  );
