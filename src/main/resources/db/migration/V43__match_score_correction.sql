-- V43: correct the score of an already-rated mid-history match (#776).
--
-- An erroneously-entered score can now be edited AFTER the match has been rated, by reversing the
-- delta that was applied and re-applying a delta recomputed from the corrected score. No downstream
-- match is re-rated (see docs/product/MATCH_SCORE_CORRECTION.md for the accepted approximation), so
-- these columns exist to (a) badge a corrected match and (b) keep the rating ledger self-explaining.

-- Badge marker: when this match's score was last corrected + how many times. Null = never corrected.
ALTER TABLE matches ADD COLUMN re_rated_at TIMESTAMP;
COMMENT ON COLUMN matches.re_rated_at IS
    'When this match''s score was last corrected after rating (#776); null = never. Drives the "Re-rated" badge.';

ALTER TABLE matches ADD COLUMN re_rated_count INTEGER NOT NULL DEFAULT 0;
COMMENT ON COLUMN matches.re_rated_count IS
    'How many times this match''s score has been corrected after rating (#776).';

-- Correction marker on the replacement rating-history row. The superseded row keeps its reversed_at
-- (the #478 soft-delete); the NEW row carries this so history reads can label it a correction rather
-- than presenting it as an ordinary match rating.
ALTER TABLE user_rating_history ADD COLUMN corrected_at TIMESTAMP;
COMMENT ON COLUMN user_rating_history.corrected_at IS
    'Set when this row replaces a reversed row from a score correction (#776); null = ordinary rating row.';

-- The signed amount actually applied to the player's CURRENT rating by the correction: newDelta - oldDelta.
-- rating_change stays the historically-faithful delta for the corrected score, so this records the net
-- in-place adjustment without conflating the two.
ALTER TABLE user_rating_history ADD COLUMN net_adjustment NUMERIC(10, 6);
COMMENT ON COLUMN user_rating_history.net_adjustment IS
    'Net applied to the current rating by a correction (#776): newDelta - oldDelta. Null for ordinary rows.';
