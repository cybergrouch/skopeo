-- V23: placeholder (dummy) player accounts + claim/adopt process (#496).
--
-- A HOST/CLUB_OWNER/ADMINISTRATOR can create a login-less "placeholder" player so a backlog of
-- played events can be recorded against attendees who don't yet have a Skopeo account. A placeholder
-- is an ordinary users row with firebase_uid = NULL (already nullable + unique in V1) and
-- placeholder = TRUE, carrying the PLAYER capability only. It accrues ratings/points/standings like
-- any player. Later the real person claims it via a secret, backend-generated claim code; the
-- placeholder's history is re-pointed onto the claimant's (empty) account and the placeholder is
-- retired via the existing canonical_user_id duplicate-merge pattern (is_active = FALSE,
-- canonical_user_id = claimant). See docs/product/PLACEHOLDER_ACCOUNTS.md.

-- Flag + claim provenance on users. `placeholder` marks the login-less dummy; `claimed_at`/`claimed_by`
-- record when and into whom it was adopted (set on claim, alongside canonical_user_id + is_active).
ALTER TABLE users ADD COLUMN placeholder BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN claimed_at TIMESTAMP;
ALTER TABLE users ADD COLUMN claimed_by UUID REFERENCES users(id) ON DELETE SET NULL;

COMMENT ON COLUMN users.placeholder IS
  'TRUE for a login-less placeholder ("dummy") player (#496): firebase_uid is NULL and only the PLAYER capability is held. The row is retired via canonical_user_id once claimed.';
COMMENT ON COLUMN users.claimed_at IS
  'When this placeholder was claimed/adopted into a real account (#496); NULL for unclaimed placeholders and non-placeholder users.';
COMMENT ON COLUMN users.claimed_by IS
  'The real account that claimed this placeholder (#496); mirrors canonical_user_id on claim. NULL until claimed.';

-- Partial index: the pending-claim/management views list unclaimed placeholders — cheap over the rare rows.
CREATE INDEX idx_users_placeholder ON users (placeholder) WHERE placeholder = TRUE;

-- Secret claim codes. The backend generates a cryptographically-random code per admin request, stores
-- only its hash (never the plaintext), and returns the plaintext once. One ACTIVE code per placeholder
-- at a time: re-issuing supersedes the prior ACTIVE code (CONSUMED). A code is single-use and expires.
CREATE TABLE placeholder_claim_codes (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    placeholder_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- SHA-256 hex of the plaintext code; the plaintext is never stored.
    code_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    -- ACTIVE (usable) or CONSUMED (claimed, or superseded by a re-issue).
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    consumed_at TIMESTAMP,
    consumed_by UUID REFERENCES users(id) ON DELETE SET NULL,

    CONSTRAINT chk_claim_code_status CHECK (status IN ('ACTIVE', 'CONSUMED'))
);

CREATE INDEX idx_claim_codes_placeholder ON placeholder_claim_codes (placeholder_user_id);
CREATE INDEX idx_claim_codes_hash ON placeholder_claim_codes (code_hash);

COMMENT ON TABLE placeholder_claim_codes IS
  'Backend-generated secret claim codes for placeholder accounts (#496): hashed, one-time-use, expiring. The plaintext is returned once on generation and never stored.';
