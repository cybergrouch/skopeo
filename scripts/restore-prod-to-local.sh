#!/bin/bash
# SPDX-FileCopyrightText: 2026 Lange Pantoja
# SPDX-License-Identifier: AGPL-3.0-or-later

# Restore a production logical backup into a THROWAWAY local database for debugging.
#
# ⚠️  Production backups contain REAL personal data (emails, dates of birth, Firebase UIDs).
#     The download is written to a temp file and deleted on exit; keep it local, never commit it,
#     and drop the restored database when you're done. Consider masking PII if the bug doesn't
#     need real identities.
#
# Usage:
#   BACKUP_BUCKET=gs://skopeo-prod-db-backups ./scripts/restore-prod-to-local.sh
#   ./scripts/restore-prod-to-local.sh gs://skopeo-prod-db-backups/skopeodb-20260714T....sql.gz
#
# Config via env:
#   BACKUP_BUCKET  gs:// bucket to pull the latest backup from (if no object arg is given)
#   LOCAL_DB       throwaway local db name   (default: skopeo_prodcopy)
#   CONTAINER      local postgres container  (default: skopeo_db)
#   PGUSER         local superuser           (default: postgres)

set -euo pipefail

OBJECT="${1:-}"
BACKUP_BUCKET="${BACKUP_BUCKET:-}"
LOCAL_DB="${LOCAL_DB:-skopeo_prodcopy}"
CONTAINER="${CONTAINER:-skopeo_db}"
PGUSER="${PGUSER:-postgres}"

# Guard: never clobber the normal dev database.
if [[ "$LOCAL_DB" == "SkopeoDb" ]]; then
  echo "❌ Refusing to overwrite the dev database 'SkopeoDb'. Use a throwaway LOCAL_DB." >&2
  exit 1
fi

# Resolve the object to restore: an explicit gs:// arg wins; otherwise default to the canonical
# scheduled backup (fixed name, kept current by the weekly Cloud Scheduler job). To restore a
# specific ad-hoc/timestamped dump from backup-db.sh, pass its gs:// path as the argument.
if [[ -z "$OBJECT" ]]; then
  if [[ -z "$BACKUP_BUCKET" ]]; then
    echo "❌ Pass a gs:// object, or set BACKUP_BUCKET to use the scheduled backup." >&2
    exit 1
  fi
  OBJECT="${BACKUP_BUCKET%/}/skopeodb-scheduled.sql.gz"
fi

echo "⚠️  This restores REAL production data (PII) into local db '${LOCAL_DB}'."
echo "    Source: ${OBJECT}"
read -r -p "Continue? [y/N] " reply
if [[ "$reply" != "y" && "$reply" != "Y" ]]; then
  echo "Aborted."
  exit 1
fi

TMP="$(mktemp -t skopeodb-restore-XXXXXX.sql.gz)"
trap 'rm -f "$TMP"' EXIT

echo "⬇️  Downloading backup…"
gcloud storage cp "$OBJECT" "$TMP"

echo "🗄️  Recreating '${LOCAL_DB}' in container '${CONTAINER}'…"
docker exec "$CONTAINER" psql -U "$PGUSER" -c "DROP DATABASE IF EXISTS ${LOCAL_DB};"
docker exec "$CONTAINER" psql -U "$PGUSER" -c "CREATE DATABASE ${LOCAL_DB};"

# Cloud SQL dumps reference cloud-managed roles (cloudsqladmin, cloudsqlsuperuser) and the app role
# (skopeo) as object owners; create them locally as NOLOGIN so ownership/GRANT statements apply
# cleanly instead of erroring.
for role in cloudsqladmin cloudsqlsuperuser skopeo; do
  docker exec "$CONTAINER" psql -U "$PGUSER" -d "$LOCAL_DB" -c \
    "DO \$\$ BEGIN CREATE ROLE ${role} NOLOGIN; EXCEPTION WHEN duplicate_object THEN NULL; END \$\$;" >/dev/null
done

echo "♻️  Restoring (this can take a minute)…"
gunzip -c "$TMP" | docker exec -i "$CONTAINER" psql -U "$PGUSER" -d "$LOCAL_DB" >/dev/null

echo "✅ Restored into '${LOCAL_DB}'."
echo
echo "Run the app against it:"
echo "   DATABASE_URL=jdbc:postgresql://localhost:5432/${LOCAL_DB} ./gradlew run"
echo "Then verify:"
echo "   ./scripts/health-check.sh"
echo
echo "Clean up when done:"
echo "   docker exec ${CONTAINER} psql -U ${PGUSER} -c \"DROP DATABASE ${LOCAL_DB};\""
