-- Re-rate requests (issue #140, phase 2 of #106): a player raises a rating-reconsideration request
-- with a justification; a RATER approves (applies a new rating) or denies (with a reason). At most
-- one PENDING request per player at a time (partial unique index).

CREATE TABLE rating_requests (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL,
    justification TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    new_rating NUMERIC(10, 6),
    reason TEXT,
    resolved_by UUID,
    resolved_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_rating_requests_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_rating_requests_resolver FOREIGN KEY (resolved_by) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_rating_request_status CHECK (status IN ('PENDING', 'APPROVED', 'DENIED'))
);

-- At most one open (PENDING) request per player.
CREATE UNIQUE INDEX uq_rating_requests_open ON rating_requests (user_id) WHERE status = 'PENDING';
