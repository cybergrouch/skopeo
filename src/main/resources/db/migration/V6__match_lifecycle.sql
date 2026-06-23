-- SPDX-FileCopyrightText: 2026 Lange Pantoja
-- SPDX-License-Identifier: AGPL-3.0-or-later

-- Match fixtures & results: deliberate, append-only, calculated separately.
--
-- A fixture is created first (SCHEDULED, no winner); results are uploaded later (COMPLETED,
-- completed_at). Ratings are NOT computed on upload — a separate admin trigger processes
-- pending matches (see PR2b). Matches are append-only: corrections disable the original
-- (is_active=false, only while unrated) and add a new one.

-- A scheduled fixture has no winner yet; set when results are uploaded.
ALTER TABLE matches
    ALTER COLUMN winner_team_id DROP NOT NULL;

ALTER TABLE matches
    ADD COLUMN completed_at TIMESTAMP, -- when results were uploaded (calculation ordering key)
    ADD COLUMN rated_at TIMESTAMP, -- when the rating calculation finalized this match
    ADD COLUMN rated_by UUID,
    ADD COLUMN created_by UUID, -- who created the fixture
    ADD COLUMN recorded_by UUID, -- who uploaded the results
    ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN disabled_at TIMESTAMP;

ALTER TABLE matches
    ADD CONSTRAINT fk_matches_created_by FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_matches_recorded_by FOREIGN KEY (recorded_by) REFERENCES users(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_matches_rated_by FOREIGN KEY (rated_by) REFERENCES users(id) ON DELETE SET NULL;

-- Oversight queries: pending-calculation (completed, unrated) and awaiting-results
-- (scheduled past its match_date).
CREATE INDEX idx_matches_pending_calc ON matches (completed_at) WHERE is_active AND status = 'COMPLETED' AND rated_at IS NULL;
CREATE INDEX idx_matches_awaiting_results ON matches (match_date) WHERE is_active AND status = 'SCHEDULED';
