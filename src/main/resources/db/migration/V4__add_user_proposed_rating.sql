-- An optional self-reported NTRP rating captured at sign-up (issue #75). This is a *proposed*
-- value only — NOT an authoritative rating — so it lives on users (not user_ratings) and the user
-- stays in the pending-assessment list until an administrator sets the real rating (which can
-- default to this proposed value). NUMERIC(10,6) mirrors user_ratings.current_rating.
-- Additive migration (baseline is V1; never edit an applied migration).
ALTER TABLE users ADD COLUMN proposed_rating NUMERIC(10, 6);
