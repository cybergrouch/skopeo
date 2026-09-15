-- V60: the ACCOUNT_MANAGER capability — who runs the Account Management surfaces (#1002).
--
-- Account administration was ADMINISTRATOR-only, so the only way to let someone restore a deleted
-- account or rectify a duplicate was to make them a full administrator. ACCOUNT_MANAGER is the staff
-- role for that job: onboarding invites, restoring deleted accounts, duplicate rectification.
--
-- `user_capabilities.chk_capability` (V1, widened by V16 for POINTS_MANAGER and V54 for SCORER)
-- enumerates the permitted strings, so the grant is rejected by the database until this widens it.
-- Adding the enum value in Kotlin alone is not enough — that is the trap this migration exists to
-- close, and `CapabilityServiceTest` grants every `Capability` entry against a real database so the
-- next omission fails a test instead of production.
--
-- Drop-and-recreate is the portable way to alter a CHECK, and is the pattern V16 and V54 set.
--
-- This is a WIDENING, not a tightening: it accepts a strictly larger set of values than before, so
-- every existing row already satisfies it and there is no precondition to backfill (contrast V50/V51,
-- and the rule in docs/engineering/operations/DB_MIGRATIONS.md). Nothing here can fail on production
-- data that passes today.
ALTER TABLE user_capabilities DROP CONSTRAINT chk_capability;
ALTER TABLE user_capabilities ADD CONSTRAINT chk_capability CHECK (capability IN
    ('PLAYER', 'HOST', 'CLUB_OWNER', 'ADMINISTRATOR', 'RATER', 'RESEARCHER', 'POINTS_MANAGER', 'SCORER',
     'ACCOUNT_MANAGER'));

COMMENT ON COLUMN user_capabilities.capability IS
    'Authorization role, mirroring the Kotlin Capability enum. Constrained by chk_capability, which must '
    'be widened in a new migration whenever a value is added to that enum (#1002 added ACCOUNT_MANAGER).';
