-- =============================================================================
-- V5: Duplicate-account detection candidates (issue #126)
-- =============================================================================
-- The detection side of duplicate rectification (#124): suspected same-person account pairs are flagged
-- for ADMINISTRATOR review (never auto-disabled). A candidate is raised automatically when a phone
-- contact matches another active user's, or manually by an admin. The pair is stored ordered
-- (user_a_id < user_b_id) so the same two accounts collapse to one row regardless of order; a partial
-- unique index keeps at most one OPEN candidate per pair. Confirming a candidate resolves it by marking
-- one account a duplicate of the other (via the #124 tool). flagged_by is null for a system flag.

CREATE TABLE duplicate_candidates (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_a_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    user_b_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    signal VARCHAR(20) NOT NULL,
    detail TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    flagged_by UUID REFERENCES users(id) ON DELETE SET NULL,
    flagged_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_by UUID REFERENCES users(id) ON DELETE SET NULL,
    resolved_at TIMESTAMP,
    CONSTRAINT chk_dup_candidate_signal CHECK (signal IN ('DUPLICATE_PHONE', 'MANUAL')),
    CONSTRAINT chk_dup_candidate_status CHECK (status IN ('OPEN', 'DISMISSED', 'RESOLVED')),
    CONSTRAINT chk_dup_candidate_distinct CHECK (user_a_id <> user_b_id)
);

-- At most one OPEN candidate per (ordered) pair.
CREATE UNIQUE INDEX uq_duplicate_candidates_open_pair
    ON duplicate_candidates(user_a_id, user_b_id) WHERE status = 'OPEN';

-- The admin queue: open candidates, newest first.
CREATE INDEX idx_duplicate_candidates_status ON duplicate_candidates(status, flagged_at DESC);
