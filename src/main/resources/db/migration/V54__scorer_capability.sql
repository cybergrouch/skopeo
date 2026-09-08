-- V54: the SCORER capability — who may umpire a live match (#911, step 1).
--
-- LiveMatch gates the umpire view on a new SCORER role. `user_capabilities.chk_capability` (V1,
-- widened once already by V16 for POINTS_MANAGER) enumerates the permitted strings, so a SCORER grant
-- is rejected by the database until this widens it. Adding the enum value in Kotlin alone is not
-- enough — that is the trap this migration exists to close, and `CapabilityServiceTest` now grants
-- every `Capability` entry against a real database so the next omission fails a test instead.
--
-- Drop-and-recreate is the portable way to alter a CHECK, and is the pattern V16 set.
--
-- This is a WIDENING, not a tightening: it accepts a strictly larger set of values than before, so
-- every existing row already satisfies it and there is no precondition to backfill (contrast
-- V50/V51, and the rule in docs/engineering/operations/DB_MIGRATIONS.md). Nothing here can fail on
-- production data that passes today.
ALTER TABLE user_capabilities DROP CONSTRAINT chk_capability;
ALTER TABLE user_capabilities ADD CONSTRAINT chk_capability CHECK (capability IN
    ('PLAYER', 'HOST', 'CLUB_OWNER', 'ADMINISTRATOR', 'RATER', 'RESEARCHER', 'POINTS_MANAGER', 'SCORER'));

COMMENT ON COLUMN user_capabilities.capability IS
    'Authorization role, mirroring the Kotlin Capability enum. Constrained by chk_capability, which must '
    'be widened in a new migration whenever a value is added to that enum (#911 added SCORER).';
