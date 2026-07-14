#!/bin/bash
# SPDX-FileCopyrightText: 2026 Lange Pantoja
# SPDX-License-Identifier: AGPL-3.0-or-later

# Portable logical backup of the production database to GCS (Cloud SQL export).
#
# Cloud SQL already takes managed daily backups + PITR (in-place disaster recovery). This produces
# the *portable* pg_dump-style artifact used for (a) restoring prod into a local db for debugging
# (see restore-prod-to-local.sh) and (b) a future migration to another database/provider.
#
# Managed backups can only restore back into Cloud SQL; this logical dump is engine-restorable.
#
# Usage:
#   BACKUP_BUCKET=gs://skopeo-backups ./scripts/backup-db.sh
#
# Config via env (defaults match the pilot GCP project):
#   PROJECT        GCP project           (default: skopeo-prod)
#   INSTANCE       Cloud SQL instance    (default: skopeo-db)
#   DATABASE       database name         (default: SkopeoDb)
#   BACKUP_BUCKET  gs:// bucket          (required)
#
# One-time prerequisite: the Cloud SQL instance's service account needs write on the bucket:
#   SA=$(gcloud sql instances describe "$INSTANCE" --project "$PROJECT" \
#          --format='value(serviceAccountEmailAddress)')
#   gcloud storage buckets add-iam-policy-binding "$BACKUP_BUCKET" \
#          --member="serviceAccount:$SA" --role=roles/storage.objectAdmin

set -euo pipefail

PROJECT="${PROJECT:-skopeo-prod}"
INSTANCE="${INSTANCE:-skopeo-db}"
DATABASE="${DATABASE:-SkopeoDb}"
BACKUP_BUCKET="${BACKUP_BUCKET:-}"

if [[ -z "$BACKUP_BUCKET" ]]; then
  echo "❌ Set BACKUP_BUCKET, e.g. BACKUP_BUCKET=gs://skopeo-backups ./scripts/backup-db.sh" >&2
  exit 1
fi

TS="$(date -u +%Y%m%dT%H%M%SZ)"
OBJECT="${BACKUP_BUCKET%/}/skopeodb-${TS}.sql.gz"

echo "📦 Exporting ${PROJECT}:${INSTANCE}/${DATABASE}"
echo "   → ${OBJECT}"
# --offload runs the export on a temporary instance so production isn't loaded (Enterprise edition).
gcloud sql export sql "$INSTANCE" "$OBJECT" \
  --project "$PROJECT" \
  --database "$DATABASE" \
  --offload

echo "✅ Backup complete: ${OBJECT}"
