-- SPDX-FileCopyrightText: 2026 Lange Pantoja
-- SPDX-License-Identifier: AGPL-3.0-or-later

-- Contact-information verification management (admin-driven).
--
-- Automated OTP verification is deferred; for now an ADMINISTRATOR marks an email or
-- phone verified via the API. This migration:
--   1. Records ADMIN_OVERRIDE as a verification method (a manual admin action).
--   2. Adds verified_by so we capture WHICH administrator performed the verification
--      (the beginning of an audit trail for privileged contact changes).

ALTER TABLE contact_information
    DROP CONSTRAINT chk_contact_method;

ALTER TABLE contact_information
    ADD CONSTRAINT chk_contact_method CHECK (
        verification_method IS NULL OR
        verification_method IN (
            'OAUTH_PROVIDER', 'EMAIL_LINK', 'SMS_OTP', 'WHATSAPP_OTP', 'VIBER_OTP', 'ADMIN_OVERRIDE'
        )
    );

ALTER TABLE contact_information
    ADD COLUMN verified_by UUID;

ALTER TABLE contact_information
    ADD CONSTRAINT fk_contact_verified_by
        FOREIGN KEY (verified_by) REFERENCES users(id) ON DELETE SET NULL;
