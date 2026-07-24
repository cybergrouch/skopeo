-- V25: circuits (#525).
--
-- A circuit is an administrator-defined grouping of tournaments (e.g. NORTH, SOUTH). Tournament
-- events will later reference a circuit (a separate migration adds events.circuit_id). Circuits are
-- created and managed by administrators; like clubs they are soft-deleted (is_active) rather than
-- removed, so history and any future references stay intact. Seeded with NORTH and SOUTH.

CREATE TABLE circuits (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(120) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Seed the two initial circuits. They are system-seeded, so created_by is left NULL.
INSERT INTO circuits (name) VALUES ('NORTH'), ('SOUTH');
