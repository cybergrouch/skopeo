-- V5: clubs and club owners (#313).
--
-- A club is a named organization an event can optionally belong to. Administrators create clubs and
-- assign CLUB_OWNER(s) to them; later work adds an optional club on events and groups the Event
-- Organizer by club. This migration introduces only the club entity and its owner association.

CREATE TABLE clubs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(120) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- A club has zero or more owners; a user can own several clubs. One row per (club, user).
CREATE TABLE club_owners (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    club_id UUID NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_club_owner UNIQUE (club_id, user_id)
);

CREATE INDEX idx_club_owners_club ON club_owners (club_id);
CREATE INDEX idx_club_owners_user ON club_owners (user_id);
