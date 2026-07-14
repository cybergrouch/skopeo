#!/bin/bash
# SPDX-FileCopyrightText: 2026 Lange Pantoja
# SPDX-License-Identifier: AGPL-3.0-or-later

# One-time setup: automate the portable logical backup (see backup-db.sh) with Cloud Scheduler.
#
# It creates (idempotently):
#   1. a versioned GCS bucket with a lifecycle rule to prune old versions,
#   2. an IAM grant so the Cloud SQL instance can write exports to the bucket, and
#   3. a Cloud Scheduler job that calls the Cloud SQL export API on a cron.
#
# Managed Cloud SQL backups already cover DAILY disaster recovery; this logical export is the
# PORTABLE snapshot (restorable off-GCP / into a local db), so it defaults to WEEKLY.
#
# Because the scheduled export writes a fixed object name, bucket versioning retains history.
#
# Usage:
#   BACKUP_BUCKET=gs://skopeo-backups \
#   SCHEDULER_SA=<sa>@skopeo-prod.iam.gserviceaccount.com \
#   ./scripts/schedule-backup.sh
#
# Config via env:
#   PROJECT         GCP project                       (default: skopeo-prod)
#   INSTANCE        Cloud SQL instance                (default: skopeo-db)
#   DATABASE        database name                     (default: SkopeoDb)
#   REGION          bucket + scheduler location       (default: asia-southeast1)
#   BACKUP_BUCKET   gs:// bucket                       (required)
#   SCHEDULER_SA    service account for the job's OAuth token; needs roles/cloudsql.editor
#                   (or a custom role with cloudsql.instances.export)   (required)
#   CRON            schedule (UTC)                     (default: "0 18 * * 0"  = Sun 02:00 PHT)
#   KEEP_DAYS       days to keep noncurrent versions   (default: 90)

set -euo pipefail

PROJECT="${PROJECT:-skopeo-prod}"
INSTANCE="${INSTANCE:-skopeo-db}"
DATABASE="${DATABASE:-SkopeoDb}"
REGION="${REGION:-asia-southeast1}"
BACKUP_BUCKET="${BACKUP_BUCKET:-}"
SCHEDULER_SA="${SCHEDULER_SA:-}"
CRON="${CRON:-0 18 * * 0}"
KEEP_DAYS="${KEEP_DAYS:-90}"

if [[ -z "$BACKUP_BUCKET" || -z "$SCHEDULER_SA" ]]; then
  echo "❌ Set BACKUP_BUCKET and SCHEDULER_SA. See the header for details." >&2
  exit 1
fi

echo "1️⃣  Ensuring bucket ${BACKUP_BUCKET} (versioned, ${REGION})…"
if ! gcloud storage buckets describe "$BACKUP_BUCKET" --project "$PROJECT" >/dev/null 2>&1; then
  gcloud storage buckets create "$BACKUP_BUCKET" \
    --project "$PROJECT" --location "$REGION" --uniform-bucket-level-access
fi
gcloud storage buckets update "$BACKUP_BUCKET" --versioning

LIFECYCLE="$(mktemp -t lifecycle-XXXXXX.json)"
trap 'rm -f "$LIFECYCLE"' EXIT
cat >"$LIFECYCLE" <<JSON
{"rule":[{"action":{"type":"Delete"},"condition":{"daysSinceNoncurrentTime":${KEEP_DAYS},"isLive":false}}]}
JSON
gcloud storage buckets update "$BACKUP_BUCKET" --lifecycle-file="$LIFECYCLE"

echo "2️⃣  Granting the Cloud SQL service account write on the bucket…"
SQL_SA="$(gcloud sql instances describe "$INSTANCE" --project "$PROJECT" \
  --format='value(serviceAccountEmailAddress)')"
gcloud storage buckets add-iam-policy-binding "$BACKUP_BUCKET" \
  --member="serviceAccount:${SQL_SA}" --role=roles/storage.objectAdmin >/dev/null

echo "3️⃣  Creating/updating the Cloud Scheduler job (cron: '${CRON}' UTC)…"
URI="https://sqladmin.googleapis.com/v1/projects/${PROJECT}/instances/${INSTANCE}/export"
BODY="$(cat <<JSON
{"exportContext":{"kind":"sql#exportContext","fileType":"SQL","uri":"${BACKUP_BUCKET%/}/skopeodb-scheduled.sql.gz","databases":["${DATABASE}"],"offload":true}}
JSON
)"

JOB="skopeo-db-backup"
if gcloud scheduler jobs describe "$JOB" --project "$PROJECT" --location "$REGION" >/dev/null 2>&1; then
  ACTION=update
else
  ACTION=create
fi
gcloud scheduler jobs "$ACTION" http "$JOB" \
  --project "$PROJECT" --location "$REGION" \
  --schedule "$CRON" --time-zone "Etc/UTC" \
  --uri "$URI" --http-method POST \
  --oauth-service-account-email "$SCHEDULER_SA" \
  --headers "Content-Type=application/json" \
  --message-body "$BODY"

echo "✅ Scheduled weekly logical backups → ${BACKUP_BUCKET%/}/skopeodb-scheduled.sql.gz"
echo "   Trigger once now to verify: gcloud scheduler jobs run ${JOB} --project ${PROJECT} --location ${REGION}"
