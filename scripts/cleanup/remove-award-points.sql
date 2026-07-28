-- SPDX-FileCopyrightText: 2026 Lange Pantoja
-- SPDX-License-Identifier: AGPL-3.0-or-later

-- ONE-OFF (#576): remove the ranking-point awards created by finalizing events during the testing
-- phase, when awarding should not yet have counted. Hard-deletes the finalize-generated award rows
-- across ALL events; manual admin adjustments (#469) are left untouched.
--
-- Target rows: source_type = 'INTERNAL' AND event_id IS NOT NULL
--   - finalize awards (EventFinalizeAwarder) are INTERNAL and carry an event_id (+ match_id).
--   - manual adjustments (#469) are sourceType EXTERNAL / point_class EXTERNAL and event-less → excluded.
--
-- Self-FK safety: ranking_point_awards.revokes_award_id references the table itself. There should be
-- NO revoke markers for these events (they were finalized, never un-finalized), but we delete any
-- marker pointing at a target row first so the self-FK can never block the main delete. Idempotent:
-- a second run deletes zero rows.
--
-- ⚠️  Run behind a backup and dry-run first — see remove-award-points.sh (this file is the apply step).
--     After running on prod, recompute standings so the race tables reflect the removal.

BEGIN;

-- 1) Delete revoke markers that reference any finalize award (harmless no-op when none exist).
DELETE FROM ranking_point_awards
WHERE revokes_award_id IN (
    SELECT id FROM ranking_point_awards
    WHERE source_type = 'INTERNAL' AND event_id IS NOT NULL
);

-- 2) Delete the finalize-generated awards themselves.
DELETE FROM ranking_point_awards
WHERE source_type = 'INTERNAL' AND event_id IS NOT NULL;

COMMIT;
