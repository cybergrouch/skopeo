-- SPDX-FileCopyrightText: 2026 Lange Pantoja
-- SPDX-License-Identifier: AGPL-3.0-or-later

-- Issue #107: add the RESEARCHER capability that gates the player-research feature (monetization-ready).
-- Extend the capability CHECK constraint to admit 'RESEARCHER'.

ALTER TABLE user_capabilities DROP CONSTRAINT chk_capability;

ALTER TABLE user_capabilities
    ADD CONSTRAINT chk_capability CHECK (capability IN ('PLAYER', 'HOST', 'CLUB_OWNER', 'ADMINISTRATOR', 'RATER', 'RESEARCHER'));
