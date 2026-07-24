-- V26: tournaments — circuit link, club sanction, and placement matches (#525).
--
-- A tournament is an EventType.TOURNAMENT event that (a) belongs to a circuit, (b) inherits its
-- sanction status from its club, and (c) awards points by final placement decided by "placement
-- matches" (Super Finals → 1st/2nd, Plate Finals → 3rd/4th). This migration adds the columns; the
-- service enforces circuit-required-for-tournaments and the placement-based awarding.

-- (a) The circuit a tournament event belongs to. Nullable at the DB level (clubless/legacy and
-- non-tournament events have none); required for TOURNAMENT events in the service (#525).
ALTER TABLE events ADD COLUMN circuit_id UUID REFERENCES circuits(id) ON DELETE SET NULL;

-- (b) Whether tournaments hosted by a club are sanctioned (#525). A tournament inherits this from its
-- club; a tournament with no club is unsanctioned. Toggled by CLUB_OWNER/ADMINISTRATOR.
ALTER TABLE clubs ADD COLUMN tournaments_sanctioned BOOLEAN NOT NULL DEFAULT FALSE;

-- (c) Placement matches (#525): a fixture flagged as deciding a placement, and which bracket it
-- decides. SUPER_FINALS → winner 1st / loser 2nd; PLATE_FINALS → winner 3rd / loser 4th.
ALTER TABLE matches ADD COLUMN is_placement_match BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE matches ADD COLUMN placement_bracket VARCHAR(20);
