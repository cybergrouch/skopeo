#!/bin/bash
# SPDX-FileCopyrightText: 2026 Lange Pantoja
# SPDX-License-Identifier: AGPL-3.0-or-later

# ONE-OFF (#576): remove the ranking-point awards created by finalizing events during the testing
# phase. Awarding should not yet have counted, so this hard-deletes the finalize-generated award rows
# (source_type = 'INTERNAL' AND event_id IS NOT NULL) across ALL events. Manual admin adjustments
# (#469 — EXTERNAL, event-less) are left untouched.
#
# Defaults to a DRY RUN (preview only). Pass --apply to actually delete. Connection-agnostic: point it
# at a restored local copy first (dry run + apply), then at prod.
#
# ⚠️  This deletes real production data. BACK UP FIRST (scripts/backup-db.sh) and dry-run on a restored
#     copy (scripts/restore-prod-to-local.sh) before applying to prod. After applying on prod, recompute
#     standings so the race tables reflect the removal.
#
# Usage:
#   # Dry run (preview counts, no changes) against the local restored copy (default connection):
#   ./scripts/cleanup/remove-award-points.sh
#
#   # Dry run against an explicit connection (libpq URL or conninfo):
#   ./scripts/cleanup/remove-award-points.sh "postgresql://postgres@localhost:5432/skopeo_prodcopy"
#
#   # Apply (delete) — prompts to confirm; add --yes to skip the prompt:
#   ./scripts/cleanup/remove-award-points.sh --apply "postgresql://user@localhost:5432/dbname"
#
#   Against prod: start your Cloud SQL connection (cloud-sql-proxy or `gcloud sql connect`) and pass the
#   resulting psql connection string as the argument.
#
# Config:
#   DB_URL   default connection when none is passed (default: local skopeo_prodcopy copy)

set -euo pipefail

DB_URL_DEFAULT="${DB_URL:-postgresql://postgres@localhost:5432/skopeo_prodcopy}"
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
PSQL=(psql "$CONN" -v ON_ERROR_STOP=1 -X -q)

# Redacted connection for logging: drop any password between ':' and '@'.
SAFE_CONN="$(printf '%s' "$CONN" | sed -E 's#(://[^:/@]+):[^@]*@#\1:***@#')"
echo "🎯 Target: finalize-generated awards (source_type='INTERNAL' AND event_id IS NOT NULL)"
echo "   Connection: ${SAFE_CONN}"
echo

echo "🔎 Preview — rows that would be removed:"
"${PSQL[@]}" <<'SQL'
SELECT
    count(*)                       AS award_rows,
    count(DISTINCT event_id)       AS events,
    count(DISTINCT user_id)        AS players,
    COALESCE(sum(points), 0)       AS total_points
FROM ranking_point_awards
WHERE source_type = 'INTERNAL' AND event_id IS NOT NULL;
SQL
echo

if [[ "$APPLY" != true ]]; then
  echo "ℹ️  Dry run only — no changes made. Re-run with --apply to delete."
  exit 0
fi

if [[ "$ASSUME_YES" != true ]]; then
  echo "⚠️  About to DELETE the rows above from '${SAFE_CONN}'. This is irreversible without a backup."
  read -r -p "Type 'delete' to proceed: " reply
  if [[ "$reply" != "delete" ]]; then
    echo "Aborted."
    exit 1
  fi
fi

echo "🧹 Applying…"
"${PSQL[@]}" -f "${HERE}/remove-award-points.sql"

echo
echo "✅ Done. Remaining finalize-generated award rows (expect 0):"
"${PSQL[@]}" <<'SQL'
SELECT count(*) AS remaining
FROM ranking_point_awards
WHERE source_type = 'INTERNAL' AND event_id IS NOT NULL;
SQL
echo
echo "Next: recompute standings so the per-band race tables reflect the removal."
