#!/bin/bash
# SPDX-FileCopyrightText: 2026 Lange Pantoja
# SPDX-License-Identifier: AGPL-3.0-or-later

# Portable backup of Firebase Auth users to GCS.
#
# Users in the database are keyed by firebase_uid, so a DB dump (backup-db.sh) is only half a
# restorable dataset — a full DR/migration backup must also capture the Firebase Auth users. This
# exports them and uploads to the same backup bucket (under firebase-auth/), fixed object name so
# bucket versioning keeps history (matches the scheduled DB export).
#
# ⚠️  The export contains password hashes/salts and PII. Keep it only in the access-controlled
#     backup bucket; never commit it.
#
# Auth:
#   - Local: run after `firebase login` (or with GOOGLE_APPLICATION_CREDENTIALS set) and
#     `gcloud auth login`.
#   - CI: set FIREBASE_SERVICE_ACCOUNT (SA JSON) for firebase-tools; gcloud is authenticated
#     separately (e.g. Workload Identity Federation) so `gcloud storage` can write the bucket.
#
# Usage:
#   FIREBASE_PROJECT=<firebase-project-id> BACKUP_BUCKET=gs://skopeo-prod-db-backups \
#     ./scripts/backup-firebase-auth.sh

set -euo pipefail

FIREBASE_PROJECT="${FIREBASE_PROJECT:-}"
BACKUP_BUCKET="${BACKUP_BUCKET:-}"

if [[ -z "$FIREBASE_PROJECT" || -z "$BACKUP_BUCKET" ]]; then
  echo "❌ Set FIREBASE_PROJECT and BACKUP_BUCKET." >&2
  exit 1
fi

OUT="$(mktemp -t fb-auth-XXXXXX.json)"
CREDS=""
cleanup() {
  rm -f "$OUT"
  [[ -n "$CREDS" ]] && rm -f "$CREDS"
}
trap cleanup EXIT

OBJECT="${BACKUP_BUCKET%/}/firebase-auth/users.json.gz"

echo "📦 Exporting Firebase Auth users for ${FIREBASE_PROJECT}…"
# Pass service-account creds only to firebase-tools (scoped to this command) so we don't clobber the
# ambient gcloud credentials used for the upload below.
if [[ -n "${FIREBASE_SERVICE_ACCOUNT:-}" ]]; then
  CREDS="$(mktemp -t fb-sa-XXXXXX.json)"
  printf '%s' "$FIREBASE_SERVICE_ACCOUNT" >"$CREDS"
  GOOGLE_APPLICATION_CREDENTIALS="$CREDS" \
    npx --yes firebase-tools@latest auth:export "$OUT" --format=json --project "$FIREBASE_PROJECT"
else
  npx --yes firebase-tools@latest auth:export "$OUT" --format=json --project "$FIREBASE_PROJECT"
fi

echo "⬆️  Uploading → ${OBJECT}"
gzip -c "$OUT" | gcloud storage cp - "$OBJECT"

echo "✅ Firebase Auth backup complete: ${OBJECT}"
