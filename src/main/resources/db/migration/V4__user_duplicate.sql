-- =============================================================================
-- V4: Duplicate-profile rectification (issue #124)
-- =============================================================================
-- One person must have one profile (else the rating system can be gamed). When an ADMINISTRATOR
-- identifies duplicate accounts, one is kept as the canonical ("true") account and the others are
-- disabled (is_active = false) and point at it via canonical_user_id. Duplicates are never deleted
-- (records are retained) and the action is reversible; ratings/match data are NOT consolidated.
-- ON DELETE SET NULL mirrors the other user self-references (e.g. fk_contact_verified_by).

ALTER TABLE users
    ADD COLUMN canonical_user_id UUID REFERENCES users(id) ON DELETE SET NULL;

CREATE INDEX idx_users_canonical_user_id ON users(canonical_user_id) WHERE canonical_user_id IS NOT NULL;
