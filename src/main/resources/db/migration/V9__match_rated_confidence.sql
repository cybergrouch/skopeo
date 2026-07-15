-- V9: rating confidence becomes a computed, non-stored value (#343).
--
-- Confidence is now derived (log-logistic decay) from the timestamp of the match-result calculation
-- that last set the rating; it is not stored. A rating that isn't match-derived (a sign-up self-rating
-- or an admin/RATER override) has no such timestamp and therefore confidence 0.

-- The match-calc timestamp the decay measures from; null once a non-match write (override) supersedes it.
ALTER TABLE user_ratings ADD COLUMN match_rated_at TIMESTAMP;
COMMENT ON COLUMN user_ratings.match_rated_at IS
    'Timestamp of the match calc that last set this rating (#343); null = self-rating/override ⇒ 0% confidence.';

-- Matches since the last reset (override / self-rating / NTRP band jump); ramps confidence up over
-- ~5 matches (scale = min(1, m/5)).
ALTER TABLE user_ratings ADD COLUMN matches_since_reset INTEGER NOT NULL DEFAULT 0;
COMMENT ON COLUMN user_ratings.matches_since_reset IS
    'Matches applied since the last override / band jump (#343); scale = min(1, m/5). Reset to 0 on either.';

-- Best-effort backfill: seed from the most recent match-linked history row per user. A later override
-- isn''t detected here, but self-corrects on the next calculation or override.
UPDATE user_ratings ur
SET match_rated_at = (
    SELECT MAX(h.calculated_at)
    FROM user_rating_history h
    WHERE h.user_id = ur.user_id AND h.match_id IS NOT NULL
);

-- Backfill matches_since_reset = match-linked history rows recorded after the latest reset row (an
-- override, match_id IS NULL, or a band jump, level_changed = true). No reset ⇒ count all match rows.
UPDATE user_ratings ur
SET matches_since_reset = (
    SELECT COUNT(*)
    FROM user_rating_history h
    WHERE h.user_id = ur.user_id
      AND h.match_id IS NOT NULL
      AND h.calculated_at > COALESCE(
          (SELECT MAX(r.calculated_at)
           FROM user_rating_history r
           WHERE r.user_id = ur.user_id AND (r.match_id IS NULL OR r.level_changed = TRUE)),
          '-infinity'::timestamp
      )
);

-- Confidence is no longer stored.
ALTER TABLE user_ratings DROP COLUMN confidence_score;
