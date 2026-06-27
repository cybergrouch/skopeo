-- =============================================================================
-- V2: Audit log — provenance for domain actions (issue #100)
-- =============================================================================
-- Append-only record of who did what, when, to which entity. A single log keyed by
-- (entity_type, action); domain tables do NOT reference it. Typed columns carry what we always
-- query/sort/display (actor, action, entity, time, summary); the JSONB `details` holds per-action
-- extras (before/after diffs, etc.) so new event types need no migration. actor_user_id is null
-- for SYSTEM / self-driven actions. Rows are never updated or deleted.

CREATE TABLE audit_log (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    action VARCHAR(64) NOT NULL,
    entity_type VARCHAR(40) NOT NULL,
    entity_id UUID,
    summary TEXT NOT NULL,
    details JSONB,
    -- A free-text note an administrator can attach to an entry; the one mutable field, and its
    -- edits are intentionally not themselves audited.
    comment TEXT
);

-- Newest-first reads, both overall and filtered per action/category (the admin trace viewer, #102).
CREATE INDEX idx_audit_occurred_at ON audit_log (occurred_at DESC);
CREATE INDEX idx_audit_action_time ON audit_log (action, occurred_at DESC);

COMMENT ON TABLE audit_log IS 'Append-only provenance of domain actions (who/what/when); see issue #100';
