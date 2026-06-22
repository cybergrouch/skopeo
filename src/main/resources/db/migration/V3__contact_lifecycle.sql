-- SPDX-FileCopyrightText: 2026 Lange Pantoja
-- SPDX-License-Identifier: AGPL-3.0-or-later

-- Append-only contact lifecycle.
--
-- Contact values are immutable: instead of editing, a contact is disabled and a new one
-- is added. This preserves a full history of every email/phone a profile has ever held
-- (each row keeps its created_at), and lets us later flag values reused across profiles.
--
--   1. is_active / disabled_at track whether a contact is current.
--   2. "one contact per type" becomes "one ACTIVE contact per type" — disabled rows
--      accumulate as history.
--   3. The global verified-value uniqueness now applies only to ACTIVE contacts, so
--      disabling a contact releases its value (a reuse we can surface for review later).

ALTER TABLE contact_information
    ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE contact_information
    ADD COLUMN disabled_at TIMESTAMP;

DROP INDEX uq_contact_one_per_type;
CREATE UNIQUE INDEX uq_contact_active_per_type
    ON contact_information (user_id, contact_type)
    WHERE is_active;

DROP INDEX uq_contact_verified_value;
CREATE UNIQUE INDEX uq_contact_verified_value
    ON contact_information (contact_type, value)
    WHERE is_active AND verification_status = 'VERIFIED';
