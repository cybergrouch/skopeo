-- V16: points budget foundation — global policy + per-club budgets (#403 Phase B).
--
-- The "points economy" controls beneath the awarding work (#403). Two tables:
--   1. points_policies — the global master policy, one row per event type (OPEN_PLAY / LEAGUE /
--      TOURNAMENT). Per type it bounds the per-match reward (min_points .. max_points) and how long
--      an awarded point may stay valid (max_validity_days). Values are whole integers (decision #6).
--      Seeded with sensible defaults so a fresh install has a policy for every type.
--   2. club_point_budgets — each club's per-type budget allocation (budgeted_points). PK is
--      (club_id, event_type) so a club has at most one budget per type. updated_by/updated_at record
--      who last wrote it for the audit surface. Nothing consumes budget yet (reservations = Phase C,
--      awards = Phase D), so "allocated" is computed as 0 in the service until then.

-- The new POINTS_MANAGER capability (#403 §5.1) — a staff role over the points economy. The
-- chk_capability CHECK (V1) enumerates the allowed values, so it must be widened to accept it before
-- any grant can be written. Drop-and-recreate is the portable way to alter a CHECK constraint.
ALTER TABLE user_capabilities DROP CONSTRAINT chk_capability;
ALTER TABLE user_capabilities ADD CONSTRAINT chk_capability CHECK (capability IN
    ('PLAYER', 'HOST', 'CLUB_OWNER', 'ADMINISTRATOR', 'RATER', 'RESEARCHER', 'POINTS_MANAGER'));

CREATE TABLE points_policies (
    event_type        VARCHAR(16) PRIMARY KEY,
    min_points        INT NOT NULL,
    max_points        INT NOT NULL,
    max_validity_days INT NOT NULL
);
COMMENT ON TABLE points_policies IS
    'Global master points policy (#403 Phase B): per event type, the per-match reward bounds and max validity days.';

INSERT INTO points_policies (event_type, min_points, max_points, max_validity_days) VALUES
    ('OPEN_PLAY', 1, 10, 30),
    ('LEAGUE', 5, 50, 90),
    ('TOURNAMENT', 10, 500, 365);

CREATE TABLE club_point_budgets (
    club_id         UUID NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
    event_type      VARCHAR(16) NOT NULL,
    budgeted_points INT NOT NULL DEFAULT 0,
    updated_by      UUID REFERENCES users(id) ON DELETE SET NULL,
    updated_at      TIMESTAMP NOT NULL,
    PRIMARY KEY (club_id, event_type)
);
COMMENT ON TABLE club_point_budgets IS
    'Per-club per-event-type points budget allocation (#403 Phase B); allocated/free are derived (allocated=0 until Phase C/D).';
