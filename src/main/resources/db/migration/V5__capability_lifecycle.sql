-- Append-only capability (role) grants with a full audit trail.
--
-- Roles are privileged, so every grant and revoke is retained: a grant is an active row
-- (granted_by / granted_at), a revoke flips it inactive (revoked_by / revoked_at), and
-- re-granting inserts a fresh active row. A user holds at most one ACTIVE row per capability;
-- revoked rows accumulate as history.

ALTER TABLE user_capabilities
    ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE user_capabilities
    ADD COLUMN revoked_at TIMESTAMP;

ALTER TABLE user_capabilities
    ADD COLUMN revoked_by UUID;

ALTER TABLE user_capabilities
    ADD CONSTRAINT fk_user_capabilities_revoked_by
        FOREIGN KEY (revoked_by) REFERENCES users(id) ON DELETE SET NULL;

-- "One grant per (user, capability)" becomes "one ACTIVE grant per (user, capability)".
ALTER TABLE user_capabilities
    DROP CONSTRAINT uq_user_capability;
CREATE UNIQUE INDEX uq_user_capability_active
    ON user_capabilities (user_id, capability)
    WHERE is_active;
