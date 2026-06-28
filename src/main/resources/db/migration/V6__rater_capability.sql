-- SPDX-FileCopyrightText: 2026 Lange Pantoja
-- SPDX-License-Identifier: AGPL-3.0-or-later

-- Issue #106: add the RATER capability so a non-admin can set initial ratings and triage rating work.
-- Extend the capability CHECK constraint to admit 'RATER'.

ALTER TABLE user_capabilities DROP CONSTRAINT chk_capability;

ALTER TABLE user_capabilities
    ADD CONSTRAINT chk_capability CHECK (capability IN ('PLAYER', 'HOST', 'CLUB_OWNER', 'ADMINISTRATOR', 'RATER'));
