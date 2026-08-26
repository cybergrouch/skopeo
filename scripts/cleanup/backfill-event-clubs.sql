-- SPDX-FileCopyrightText: 2026 Lange Pantoja
-- SPDX-License-Identifier: AGPL-3.0-or-later

-- ONE-OFF (#794): re-file every clubless event under an archive club, so migration V44 can make
-- events.club_id NOT NULL.
--
-- Why this exists: V44 runs `ALTER TABLE events ALTER COLUMN club_id SET NOT NULL`, and Flyway runs on
-- startup (config/DatabaseConfig.kt, which rethrows on failure). So a single clubless row fails the
-- migration, fails the boot, and the Cloud Run revision never listens on $PORT. That is exactly what
-- happened to the v2.0.8 deploy:
--
--     ERROR: column "club_id" of relation "events" contains null values   (SQLSTATE 23502)
--
-- Target rows: events.club_id IS NULL, **including soft-deleted ones**. NOT NULL does not care that we
-- consider a row deleted, and soft-deleted events are the likely reason the earlier manual re-filing
-- pass came up short — they are hidden from the UI that pass was made through.
--
-- Destination: the club whose public_code is :archive_code. clubs.public_code is uniquely indexed
-- (uq_clubs_public_code, V10), so that resolves to at most one club. The UPDATE ... FROM form below
-- therefore touches zero rows if the code resolves to nothing, rather than silently writing NULL back
-- again; the runner's post-check turns that into a loud failure.
--
-- Access is not widened by this. Event authorization (#789) reads club_owners, so re-filing under a
-- club with no named owners grants nobody new access: the original creator keeps theirs through the
-- grandfathered created_by clause, and administrators keep theirs.
--
-- Idempotent: a second run matches no rows.
--
-- ⚠️  Run behind a backup and dry-run first — see backfill-event-clubs.sh (this file is the apply step).

BEGIN;

UPDATE events e
SET club_id = c.id
FROM clubs c
WHERE c.public_code = :'archive_code'
  AND e.club_id IS NULL;

COMMIT;
