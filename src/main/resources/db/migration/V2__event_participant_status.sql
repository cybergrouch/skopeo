-- Event self-signup with host approval (#201).
-- Participants gain a status: APPROVED counts as a full roster member (eligible for fixtures/seeding);
-- PENDING is a self-signup awaiting review; HOLD is a soft deny that can later be approved.
-- Existing rows (added by a host) default to APPROVED so current behaviour is unchanged.
ALTER TABLE event_participants
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'APPROVED',
    ADD COLUMN requested_at TIMESTAMP,
    ADD COLUMN approved_by UUID REFERENCES users (id) ON DELETE SET NULL,
    ADD COLUMN approved_at TIMESTAMP;
