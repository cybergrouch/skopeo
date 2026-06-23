-- SPDX-FileCopyrightText: 2026 Lange Pantoja
-- SPDX-License-Identifier: AGPL-3.0-or-later

-- Replace "gender" with "sex" (biologically defined; only Male/Female).
-- Existing M/F map to Male/Female; the dropped 'Other' becomes NULL.
ALTER TABLE users DROP CONSTRAINT IF EXISTS chk_users_gender;
ALTER TABLE users RENAME COLUMN gender TO sex;

UPDATE users
SET sex = CASE sex
    WHEN 'M' THEN 'Male'
    WHEN 'F' THEN 'Female'
    ELSE NULL
END;

ALTER TABLE users ADD CONSTRAINT chk_users_sex CHECK (sex IN ('Male', 'Female'));
