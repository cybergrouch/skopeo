#!/bin/bash
# SPDX-FileCopyrightText: 2026 Lange Pantoja
# SPDX-License-Identifier: AGPL-3.0-or-later

# One-time setup: automate the Standings recompute+publish with Cloud Scheduler (#389).
#
# Standings are points-based (#146) and points EXPIRE, so a player's total changes with the passage
# of time and not only when a match is rated. An event-triggered rebuild is therefore not enough —
# the race needs a clock. This is that clock.
#
# It creates (idempotently) one Cloud Scheduler job that POSTs to the same endpoint the Admin UI's
# manual trigger uses. There is no second code path: the scheduler is just another caller, and it
# authenticates as an API client (#225) so the Activity Log attributes the run to it by name rather
# than leaving a machine run anonymous (#975).
#
# Usage:
#   API_KEY=skopeo_live_… \
#   ./scripts/schedule-standings.sh
#
# Config via env:
#   PROJECT    GCP project                        (default: skopeo-prod)
#   REGION     scheduler location                 (default: asia-southeast1)
#   BASE_URL   API origin, no trailing slash       (default: https://api.skopeo.co)
#   API_KEY    an API client key with POINTS_MANAGER scope    (required)
#   CRON       schedule, read in TIME_ZONE        (default: "0 1 * * 2" = Tuesdays, 01:00)
#   TIME_ZONE  IANA zone the cron is read in      (default: Asia/Manila)
#   DRY_RUN    "true" to schedule a preview that writes nothing   (default: false)
#
# On the key: it is never read from a file in this repo and never printed. Cloud Scheduler stores the
# header in the job config, which is the intended place for it — rotate by re-running this script with
# a new key, which updates the existing job in place.
#
# Cadence: weekly rather than the fortnightly ATP/WTA release. Points expire daily, so a fortnightly
# run leaves a table up to 13 days stale; weekly halves that for the same near-zero cost, and Tuesday
# 01:00 local puts it after the weekend's play has been entered and before anyone looks.

set -euo pipefail

PROJECT="${PROJECT:-skopeo-prod}"
REGION="${REGION:-asia-southeast1}"
BASE_URL="${BASE_URL:-https://api.skopeo.co}"
API_KEY="${API_KEY:-}"
CRON="${CRON:-0 1 * * 2}"
TIME_ZONE="${TIME_ZONE:-Asia/Manila}"
DRY_RUN="${DRY_RUN:-false}"

if [[ -z "$API_KEY" ]]; then
  echo "❌ Set API_KEY to an API client key with the POINTS_MANAGER scope." >&2
  echo "   Issue one under Admin → API clients; the plaintext is shown once." >&2
  exit 1
fi

JOB="skopeo-standings-recompute"
URI="${BASE_URL%/}/api/v1/standings/calculations"
BODY="{\"dryRun\":${DRY_RUN}}"

if gcloud scheduler jobs describe "$JOB" --project "$PROJECT" --location "$REGION" >/dev/null 2>&1; then
  ACTION=update
else
  ACTION=create
fi

echo "${ACTION^}ing Cloud Scheduler job '${JOB}' (cron: '${CRON}' ${TIME_ZONE}, dryRun=${DRY_RUN})…"
gcloud scheduler jobs "$ACTION" http "$JOB" \
  --project "$PROJECT" --location "$REGION" \
  --schedule "$CRON" --time-zone "$TIME_ZONE" \
  --uri "$URI" --http-method POST \
  --headers "Content-Type=application/json,X-Api-Key=${API_KEY}" \
  --message-body "$BODY"

echo "✅ Standings recompute scheduled: ${CRON} (${TIME_ZONE})"
echo "   Trigger once now to verify: gcloud scheduler jobs run ${JOB} --project ${PROJECT} --location ${REGION}"
echo "   Then check the Activity Log — the entry should carry the API client's name."
