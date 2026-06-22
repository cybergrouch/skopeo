-- Append-only user-name lifecycle + display name as an explicit name type.
--
-- Name values are immutable: instead of editing, a name is disabled and a new one added,
-- preserving the full history of every name a profile has held. A user may hold many active
-- names (legal, nicknames, …). The "display name" is no longer a boolean flag — it is a
-- dedicated DISPLAY name type, of which a user has exactly one active at a time.

ALTER TABLE user_names
    ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE user_names
    ADD COLUMN disabled_at TIMESTAMP;

-- Replace the is_primary flag with an explicit DISPLAY name type.
DROP INDEX uq_user_primary_name;
ALTER TABLE user_names
    DROP COLUMN is_primary;

ALTER TABLE user_names
    DROP CONSTRAINT chk_name_type;
ALTER TABLE user_names
    ADD CONSTRAINT chk_name_type CHECK (name_type IN
        ('FIRST', 'MIDDLE', 'LAST', 'SUFFIX', 'NICKNAME', 'PREFERRED', 'FULL', 'GOVERNMENT', 'DISPLAY'));

-- At most one ACTIVE display name per user (the "at least one" half is guaranteed by
-- provisioning and protected by the API, which won't disable a user's only display name).
CREATE UNIQUE INDEX uq_user_display_name
    ON user_names (user_id)
    WHERE name_type = 'DISPLAY' AND is_active;
