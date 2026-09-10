-- V58: mark the set a retirement or default stopped (#972).
--
-- The open-play points rule for a retirement applies to the ABANDONED set only: the retiring side is
-- paid nothing for it, the opponent is paid only if they were ahead on games, and sets completed
-- before the retirement score normally. Applying that needs to know which set was abandoned, and
-- nothing in the row implies it.
--
-- Two RETIRED matches that must score differently are otherwise indistinguishable:
--
--   sets 6-4, 5-1  -- won set 1, retired mid-set 2   -> set 2 pays nobody
--   sets 6-4       -- won set 1, retired before set 2 -> nothing abandoned, set 1 pays normally
--
-- Both are COMPLETED/RETIRED with a plausible last set, and the games floor does not separate them:
-- 5-1 clears it (5 >= 4, margin >= 2), so it reads exactly like a legitimately completed short set.
-- Guessing "the last set" would retract points from a set that was genuinely won, which is the one
-- outcome the rule forbids.
--
-- Deliberately NOT the same proposition as the set winner V53 dropped (#917). That column duplicated a
-- derivation -- the games already said who won, so the two could only agree or drift. This is a fact
-- nothing else in the row implies and only the scorer knows: LiveMatchService appends the in-progress
-- set last and then discards the knowledge that it was in progress. A stored value is the only way to
-- keep it.
--
-- No backfill needed, and that is a fact rather than a hope: `abandoned` means "play stopped during
-- this set", and no set recorded before this migration is one. Every existing row played to a
-- conclusion, so FALSE is correct for all of them -- which is why the NOT NULL can land immediately
-- rather than needing the backfill-then-constrain shape (#799).

ALTER TABLE match_sets ADD COLUMN abandoned BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN match_sets.abandoned IS
    'True when play stopped during this set -- a retirement or default (#972). Drives the open-play '
    'points rule: an abandoned set pays the conceding side nothing, and the opponent only if ahead.';
