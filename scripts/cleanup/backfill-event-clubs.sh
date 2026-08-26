#!/bin/bash
# SPDX-FileCopyrightText: 2026 Lange Pantoja
# SPDX-License-Identifier: AGPL-3.0-or-later

# ONE-OFF (#794): find events with no club and re-file them under an archive club, so migration V44
# (`ALTER TABLE events ALTER COLUMN club_id SET NOT NULL`) can apply.
#
# Flyway runs on app startup and DatabaseConfig rethrows on failure, so one clubless row fails the
# migration, fails the boot, and the Cloud Run revision never listens on $PORT. That is what broke the
# v2.0.8 deploy:  ERROR: column "club_id" of relation "events" contains null values  (SQLSTATE 23502).
#
# Defaults to a DRY RUN (report only). Pass --apply to re-file. Connection-agnostic: point it at a
# restored local copy first (dry run + apply), then at prod.
#
# Soft-deleted events are included on purpose — NOT NULL does not care that we consider a row deleted,
# and they are the likely reason the earlier manual pass came up short (the UI hides them).
#
# ⚠️  This writes real production data. BACK UP FIRST (scripts/backup-db.sh) and dry-run on a restored
#     copy (scripts/restore-prod-to-local.sh) before applying to prod.
#
# ⚠️  RACE: the currently-deployed API (2.0.7) still accepts a null clubId, so new clubless events can
#     appear after you clean up. Re-run this script's dry run immediately before re-triggering the
#     "Deploy API" workflow, and confirm it reports 0.
#
# Usage:
#   # Dry run (report only) against the local restored copy (default connection):
#   ./scripts/cleanup/backfill-event-clubs.sh
#
#   # Dry run against an explicit connection (libpq URL or conninfo):
#   ./scripts/cleanup/backfill-event-clubs.sh "postgresql://postgres@localhost:5432/skopeo_prodcopy"
#
#   # Apply (re-file) — prompts to confirm; add --yes to skip the prompt:
#   ./scripts/cleanup/backfill-event-clubs.sh --apply "postgresql://user@localhost:5432/dbname"
#
#   Against prod: Cloud SQL is private-IP only, so open a path first — cloud-sql-proxy from inside the
#   VPC, or Cloud SQL Studio in the console — and pass the resulting psql connection string.
#
# Config:
#   DB_URL             default connection when none is passed (default: local skopeo_prodcopy copy)
#   ARCHIVE_CLUB_CODE  public_code of the destination club (default: XCBXNV, "(Old) Archived")

set -euo pipefail

DB_URL_DEFAULT="${DB_URL:-postgresql://postgres@localhost:5432/skopeo_prodcopy}"
ARCHIVE_CLUB_CODE="${ARCHIVE_CLUB_CODE:-XCBXNV}"
APPLY=false
ASSUME_YES=false
CONN=""

for arg in "$@"; do
  case "$arg" in
    --apply) APPLY=true ;;
    --yes|-y) ASSUME_YES=true ;;
    -*) echo "Unknown flag: $arg" >&2; exit 2 ;;
    *) CONN="$arg" ;;
  esac
done
CONN="${CONN:-$DB_URL_DEFAULT}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PSQL=(psql "$CONN" -v ON_ERROR_STOP=1 -v "archive_code=$ARCHIVE_CLUB_CODE" -X -q)

# Redacted connection for logging: drop any password between ':' and '@'.
SAFE_CONN="$(printf '%s' "$CONN" | sed -E 's#(://[^:/@]+):[^@]*@#\1:***@#')"
echo "🎯 Target: events with club_id IS NULL (soft-deleted included)"
echo "   Destination club: public_code=${ARCHIVE_CLUB_CODE}"
echo "   Connection: ${SAFE_CONN}"
echo

# --- Resolve the destination club, and refuse to continue if it isn't there -------------------------
# public_code is uniquely indexed (uq_clubs_public_code, V10), so this is at most one row.
CLUB_DESC="$("${PSQL[@]}" -A -t <<'SQL'
SELECT name || '  (active=' || is_active || ')' FROM clubs WHERE public_code = :'archive_code';
SQL
)"

if [[ -z "$CLUB_DESC" ]]; then
  echo "❌ No club with public_code '${ARCHIVE_CLUB_CODE}' in this database." >&2
  echo "   Create it first, or set ARCHIVE_CLUB_CODE to an existing club's code." >&2
  exit 1
fi
echo "🏛  Destination club: ${CLUB_DESC}"
if [[ "$CLUB_DESC" == *"active=false"* ]]; then
  echo "⚠️  That club is soft-deleted (is_active = false). V44 will still pass, but the events will be"
  echo "   filed under a disabled club. Consider re-activating it first."
fi
echo

# --- Report ----------------------------------------------------------------------------------------
echo "🔎 Clubless events — summary:"
"${PSQL[@]}" <<'SQL'
SELECT
    count(*)                                         AS clubless_total,
    count(*) FILTER (WHERE is_active)                AS active,
    count(*) FILTER (WHERE NOT is_active)            AS soft_deleted,
    count(*) FILTER (WHERE finalized_at IS NOT NULL) AS finalized
FROM events
WHERE club_id IS NULL;
SQL
echo

echo "🔎 Clubless events — detail (up to 200, newest first):"
"${PSQL[@]}" <<'SQL'
SELECT
    e.public_code,
    left(e.name, 40)                                                  AS name,
    e.start_date,
    e.end_date,
    e.is_active                                                       AS active,
    (e.finalized_at IS NOT NULL)                                      AS finalized,
    (SELECT count(*) FROM matches m WHERE m.event_id = e.id)          AS matches,
    (SELECT count(*) FROM event_participants p WHERE p.event_id = e.id) AS players,
    COALESCE(u.public_code, '—')                                      AS creator
FROM events e
LEFT JOIN users u ON u.id = e.created_by
WHERE e.club_id IS NULL
ORDER BY e.start_date DESC, e.public_code
LIMIT 200;
SQL
echo

REMAINING_BEFORE="$("${PSQL[@]}" -A -t -c "SELECT count(*) FROM events WHERE club_id IS NULL;")"
if [[ "$REMAINING_BEFORE" == "0" ]]; then
  echo "✅ Nothing to do — no clubless events. V44 will apply cleanly."
  exit 0
fi

if [[ "$APPLY" != true ]]; then
  echo "ℹ️  Dry run only — no changes made. Re-run with --apply to re-file the ${REMAINING_BEFORE} event(s) above."
  exit 0
fi

if [[ "$ASSUME_YES" != true ]]; then
  echo "⚠️  About to re-file ${REMAINING_BEFORE} event(s) under '${ARCHIVE_CLUB_CODE}' on '${SAFE_CONN}'."
  read -r -p "Type 'refile' to proceed: " reply
  if [[ "$reply" != "refile" ]]; then
    echo "Aborted."
    exit 1
  fi
fi

echo "🧹 Applying…"
"${PSQL[@]}" -f "${HERE}/backfill-event-clubs.sql"

echo
echo "✅ Done. Remaining clubless events (expect 0):"
"${PSQL[@]}" <<'SQL'
SELECT count(*) AS remaining_clubless FROM events WHERE club_id IS NULL;
SQL

REMAINING_AFTER="$("${PSQL[@]}" -A -t -c "SELECT count(*) FROM events WHERE club_id IS NULL;")"
if [[ "$REMAINING_AFTER" != "0" ]]; then
  echo "❌ Still ${REMAINING_AFTER} clubless event(s) — V44 would fail again. Investigate before deploying." >&2
  exit 1
fi

echo
echo "Next: re-run the 'Deploy API' workflow against the v2.0.8 tag. Re-run this dry run first if any"
echo "time has passed — 2.0.7 still accepts a null clubId, so fresh clubless events can appear."
