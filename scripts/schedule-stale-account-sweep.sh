#!/bin/bash
# SPDX-FileCopyrightText: 2026 Lange Pantoja
# SPDX-License-Identifier: AGPL-3.0-or-later

# One-time setup: run the stale-account sweep daily with Cloud Scheduler (#1122).
#
# The sweep soft-deletes self-sign-ups that are still unrated, with no match or event history, more than
# `stale_account_days` (Admin → Stale-account cleanup, default 30) after joining. Every removal is
# restorable from Deleted Accounts, and the Ratings tab shows each pending player's removal date first.
#
# This creates or updates ONE Cloud Scheduler job that POSTs to the same endpoint an administrator can
# call: POST /api/v1/users/stale-sweeps. It authenticates as an API client, so the Activity Log
# attributes each run to the client by name.
#
# The key must carry ONLY the ACCOUNT_SWEEPER scope (Admin → API Clients). That role can run this sweep
# and nothing else; a key with ADMINISTRATOR would work but defeats the point of the narrow role.
#
# Usage (run it yourself, in your own terminal):
#   ./scripts/schedule-stale-account-sweep.sh              # prompts for the key, hidden
#   API_KEY=skopeo_live_… ./scripts/schedule-stale-account-sweep.sh
#
# DRY_RUN DEFAULTS TO true: the job previews (Activity Log: "Previewed the stale-account sweep: N
# accounts would be deleted") and deletes nothing. When the previews look right, switch it on:
#   DRY_RUN=false ./scripts/schedule-stale-account-sweep.sh
#
# Config via env:
#   PROJECT    GCP project                          (default: skopeo-prod)
#   REGION     scheduler location                   (default: asia-southeast1)
#   BASE_URL   API origin, no trailing slash         (default: the prod Cloud Run URL below)
#   API_KEY    a key with the ACCOUNT_SWEEPER scope  (prompted for if unset)
#   CRON       schedule, read in TIME_ZONE           (default: "0 2 * * *" = daily, 02:00)
#   TIME_ZONE  IANA zone the cron is read in        (default: Asia/Manila)
#   DRY_RUN    "false" to actually delete            (default: true, a preview)
#
# On the key: it is never read from a file in this repo and never printed. Cloud Scheduler keeps it in
# the job's headers. Rotate by re-running with a new key, which updates the job in place.
#
# Plain bash 3.2 on purpose (macOS's /bin/bash): no ${var^} and similar bash-4 syntax.

set -euo pipefail

PROJECT="${PROJECT:-skopeo-prod}"
REGION="${REGION:-asia-southeast1}"
# The Cloud Run service URL, the same origin the deployed SPA and the standings job call. Read the current
# one with: gcloud run services describe skopeo --project skopeo-prod --region asia-southeast1 --format='value(status.url)'
BASE_URL="${BASE_URL:-https://skopeo-lljnrq2m2q-as.a.run.app}"
API_KEY="${API_KEY:-}"
CRON="${CRON:-0 2 * * *}"
TIME_ZONE="${TIME_ZONE:-Asia/Manila}"
DRY_RUN="${DRY_RUN:-true}"

case "$DRY_RUN" in
  true | false) ;;
  *)
    echo "❌ DRY_RUN must be true or false, not '$DRY_RUN'." >&2
    exit 1
    ;;
esac

if [[ -z "$API_KEY" ]]; then
  if [[ -t 0 ]]; then
    # Prompted, not typed on the command line, so the key stays out of shell history.
    read -r -s -p "API key (ACCOUNT_SWEEPER scope only, input hidden): " API_KEY
    echo
  fi
  if [[ -z "$API_KEY" ]]; then
    echo "❌ No API key. Issue one under Admin → API Clients with ONLY the ACCOUNT_SWEEPER scope." >&2
    exit 1
  fi
fi

# A unique name: re-running updates THIS job, and it can never collide with standings-recompute.
JOB="stale-account-sweep"
URI="${BASE_URL%/}/api/v1/users/stale-sweeps"
BODY="{\"dryRun\":${DRY_RUN}}"
HEADERS="Content-Type=application/json,X-Api-Key=${API_KEY}"

# `create` and `update` name the headers flag differently (--headers vs --update-headers).
if gcloud scheduler jobs describe "$JOB" --project "$PROJECT" --location "$REGION" >/dev/null 2>&1; then
  echo "Updating Cloud Scheduler job '${JOB}' (cron: '${CRON}' ${TIME_ZONE}, dryRun=${DRY_RUN})…"
  gcloud scheduler jobs update http "$JOB" \
    --project "$PROJECT" --location "$REGION" \
    --schedule "$CRON" --time-zone "$TIME_ZONE" \
    --uri "$URI" --http-method POST \
    --update-headers "$HEADERS" \
    --message-body "$BODY" >/dev/null
else
  echo "Creating Cloud Scheduler job '${JOB}' (cron: '${CRON}' ${TIME_ZONE}, dryRun=${DRY_RUN})…"
  gcloud scheduler jobs create http "$JOB" \
    --project "$PROJECT" --location "$REGION" \
    --schedule "$CRON" --time-zone "$TIME_ZONE" \
    --uri "$URI" --http-method POST \
    --headers "$HEADERS" \
    --message-body "$BODY" >/dev/null
fi

if [[ "$DRY_RUN" == "true" ]]; then
  echo "✅ Stale-account sweep scheduled as a PREVIEW: ${CRON} (${TIME_ZONE}). Nothing will be deleted."
  echo "   Check the Activity Log for \"Previewed the stale-account sweep\" entries; switch on with DRY_RUN=false."
else
  echo "✅ Stale-account sweep scheduled LIVE: ${CRON} (${TIME_ZONE}). It soft-deletes; Deleted Accounts restores."
fi
echo "   Trigger once now to verify: gcloud scheduler jobs run ${JOB} --project ${PROJECT} --location ${REGION}"
