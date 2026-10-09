-- V64: the ACCOUNT_SWEEPER capability — who may run the stale-account sweep (#1122).
--
-- The sweep soft-deletes self-sign-ups that stayed unrated, with no history, past a configurable number
-- of days. It is driven by a Cloud Scheduler job holding an API key, and that key should not be a full
-- ADMINISTRATOR key just because deleting accounts is otherwise administrator-only. ACCOUNT_SWEEPER is the
-- narrow scope for it.
--
-- `user_capabilities.chk_capability` (V1, widened by V16, V54 and V60) enumerates the permitted strings,
-- so a grant is rejected by the database until this widens it. API key scopes (`api_keys.scopes`, V33) are
-- a free-text list validated in Kotlin, so they need no change here.
--
-- A WIDENING, not a tightening: every existing row already satisfies the new CHECK, so there is no
-- precondition to backfill (docs/engineering/operations/DB_MIGRATIONS.md).
ALTER TABLE user_capabilities DROP CONSTRAINT chk_capability;
ALTER TABLE user_capabilities ADD CONSTRAINT chk_capability CHECK (capability IN
    ('PLAYER', 'HOST', 'CLUB_OWNER', 'ADMINISTRATOR', 'RATER', 'RESEARCHER', 'POINTS_MANAGER', 'SCORER',
     'ACCOUNT_MANAGER', 'ACCOUNT_SWEEPER'));

COMMENT ON COLUMN user_capabilities.capability IS
    'Authorization role, mirroring the Kotlin Capability enum. Constrained by chk_capability, which must '
    'be widened in a new migration whenever a value is added to that enum (#1122 added ACCOUNT_SWEEPER).';
