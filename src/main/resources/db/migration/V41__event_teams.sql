-- V41: durable, event-scoped teams (#720).
--
-- A team is a purely organizational grouping of an event's participants — it does NOT affect rating
-- calculation or seeding (both stay player-based). "Durable" means the membership is stable during
-- the event; a team is considered dissolved once the event is over. Fixtures snapshot player ids at
-- creation (V1 teams/team_users), so editing or dissolving an event team later never rewrites the
-- fixtures it helped populate.
--
--   * event_teams        — the team (event_id FK → events ON DELETE CASCADE, name).
--   * event_team_members — an ordered slot/position per member (Player 1 / Player 2 for doubles),
--                          with EXCLUSIVE membership: a participant is in at most one team per event.
--
-- The member row denormalizes event_id (alongside team_id) so the "one team per event per user"
-- rule can be a plain UNIQUE(event_id, user_id) constraint, not just an application check.

CREATE TABLE event_teams (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    event_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_event_teams_event FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE CASCADE
);

CREATE INDEX idx_event_teams_event ON event_teams(event_id);

CREATE TABLE event_team_members (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    team_id UUID NOT NULL,
    event_id UUID NOT NULL,
    user_id UUID NOT NULL,
    position INTEGER NOT NULL,

    CONSTRAINT fk_event_team_members_team FOREIGN KEY (team_id) REFERENCES event_teams(id) ON DELETE CASCADE,
    CONSTRAINT fk_event_team_members_event FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE CASCADE,
    CONSTRAINT fk_event_team_members_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_event_team_member_position CHECK (position IN (1, 2)),
    -- One slot per position within a team (Player 1 / Player 2).
    CONSTRAINT uq_event_team_slot UNIQUE (team_id, position),
    -- Exclusive membership: a participant belongs to at most one team per event.
    CONSTRAINT uq_event_team_member_exclusive UNIQUE (event_id, user_id)
);

CREATE INDEX idx_event_team_members_team ON event_team_members(team_id);
CREATE INDEX idx_event_team_members_user ON event_team_members(user_id);

COMMENT ON TABLE event_teams IS 'Durable, event-scoped organizational teams (#720); no rating/seeding impact.';
COMMENT ON TABLE event_team_members IS 'Ordered members of an event team (#720); exclusive membership per event.';
