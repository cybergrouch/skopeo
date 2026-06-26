-- Admin invitations for manual (email/password & email-link) onboarding (issue #74). Manual
-- sign-ups are invite-only: an admin records an invite for an email here, and profile provisioning
-- (POST /api/v1/users) is refused for a password/email-link token whose email has no open invite.
-- OAuth (Google/Facebook) sign-ups are exempt (already email-verified) and never consult this table.
-- status: PENDING | ACCEPTED | REVOKED (EXPIRED is derived from expires_at, not stored).
-- Additive migration (baseline is V1; never edit an applied migration).
CREATE TABLE invites (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    invited_by UUID REFERENCES users(id) ON DELETE SET NULL,
    expires_at TIMESTAMP NOT NULL,
    accepted_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_invites_email ON invites (email);
