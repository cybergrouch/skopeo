-- V28: admin-configurable points schedules (#552/#553) — the scoped, global successor to the points
-- policy removed in V27 (#540).
--
-- Stores each configurable schedule as a JSON document under a stable key ('open_play',
-- 'tournament'): the open-play margin-bracket point table + validity, and the tournament placement
-- table (sanctioned/unsanctioned 1st..4th) + validity. Global + admin-writable; each write records
-- the admin (updated_by) and time (updated_at) for the audit surface. No rows are seeded — the
-- service returns behaviour-preserving code defaults when a key is absent, so a fresh install and an
-- unedited install behave identically until an admin saves an override.

CREATE TABLE points_config (
    key        VARCHAR(64) PRIMARY KEY,
    value      TEXT        NOT NULL,
    updated_by UUID REFERENCES users(id) ON DELETE SET NULL,
    updated_at TIMESTAMP   NOT NULL DEFAULT now()
);
