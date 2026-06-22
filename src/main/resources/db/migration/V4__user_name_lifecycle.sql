-- Append-only user-name lifecycle (mirrors the contact-information model).
--
-- Name values are immutable: instead of editing, a name is disabled and a new one is
-- added, preserving the full history of every name a profile has held. A user may hold
-- multiple active names (legal, preferred, nicknames); the only constraint is a single
-- primary (display) name among the ACTIVE ones.

ALTER TABLE user_names
    ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE user_names
    ADD COLUMN disabled_at TIMESTAMP;

-- The primary (display) name must be unique per user among ACTIVE names; disabling the
-- primary leaves the user with none until another active name is promoted.
DROP INDEX uq_user_primary_name;
CREATE UNIQUE INDEX uq_user_primary_name
    ON user_names (user_id)
    WHERE is_primary AND is_active;
