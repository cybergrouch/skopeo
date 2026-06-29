-- Event Organizer (issue #138): hosts run events/meets that contain matches. An event has a name,
-- a date range, participants, and a shareable public code (mirroring matches/users). Matches may
-- optionally belong to an event (event-centric tab; event_id stays nullable for back-compat).

CREATE TABLE events (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    public_code VARCHAR(6) NOT NULL,
    name VARCHAR(255) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    created_by UUID,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    disabled_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_events_created_by FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_event_dates CHECK (end_date >= start_date)
);

CREATE UNIQUE INDEX uq_events_public_code ON events (public_code);

CREATE TABLE event_participants (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    event_id UUID NOT NULL,
    user_id UUID NOT NULL,
    added_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_event_participants_event FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE CASCADE,
    CONSTRAINT fk_event_participants_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uq_event_participant UNIQUE (event_id, user_id)
);

ALTER TABLE matches ADD COLUMN event_id UUID;
ALTER TABLE matches ADD CONSTRAINT fk_matches_event FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE SET NULL;
