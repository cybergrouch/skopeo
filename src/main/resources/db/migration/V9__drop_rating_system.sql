-- SPDX-FileCopyrightText: 2026 Lange Pantoja
-- SPDX-License-Identifier: AGPL-3.0-or-later

-- NTRP-only: drop the rating-system dimension entirely. Dropping each column also drops the
-- CHECK constraints, the composite unique, and the indexes that referenced it.
ALTER TABLE user_ratings DROP COLUMN rating_system;
ALTER TABLE user_ratings ADD CONSTRAINT uq_user_rating UNIQUE (user_id);
ALTER TABLE user_ratings ADD CONSTRAINT chk_user_rating_range
    CHECK (current_rating >= 1.0 AND current_rating <= 7.0);

ALTER TABLE user_rating_history DROP COLUMN rating_system;
ALTER TABLE teams DROP COLUMN rating_system;
ALTER TABLE matches DROP COLUMN rating_system;
