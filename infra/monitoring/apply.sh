#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Lange Pantoja
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Apply the Cloud Monitoring config in this directory (#808).
#
# Every value defaults to this project's real setting, so the common case is:
#
#     ./infra/monitoring/apply.sh --dry-run     # show what would change
#     ./infra/monitoring/apply.sh               # apply
#
# Anything can be overridden per-run; see --help. Precedence is flag > environment > default, so
# existing `export GCP_PROJECT_ID=…` usage keeps working and a flag always wins.
#
# Idempotent: every resource is looked up by display name and updated in place. That matters more than
# usual here — a duplicated alert policy pages twice, which is exactly the noise #751's two-alert budget
# exists to prevent.
#
# Needs roles/monitoring.editor on the project.
set -euo pipefail

# --- Defaults: the real values for this project, so no exports are needed ------------------------
DEFAULT_PROJECT="skopeo-prod"
DEFAULT_REGION="asia-southeast1"
DEFAULT_SERVICE="skopeo"
# A team-managed group, never a personal address (#190) — an alerting path that depends on one person's
# inbox is a single point of failure by construction.
DEFAULT_ALERT_EMAIL="skopeo-alerts@googlegroups.com"

PROJECT="${GCP_PROJECT_ID:-$DEFAULT_PROJECT}"
REGION="${GCP_REGION:-$DEFAULT_REGION}"
SERVICE="${CLOUD_RUN_SERVICE:-$DEFAULT_SERVICE}"
ALERT_EMAIL="${ALERT_EMAIL:-$DEFAULT_ALERT_EMAIL}"
# Deliberately NOT defaulted to a hostname. There is no custom domain for the API today
# (api.skopeo.co has no DNS record; the web SPA calls the *.run.app URL directly), and the run.app
# hostname carries a per-service hash — deriving it from the deployed service cannot drift, whereas a
# hardcoded copy silently rots if the service is ever recreated.
API_HOST="${API_HOST:-}"
DRY_RUN="${DRY_RUN:-}"

usage() {
  cat <<USAGE
Apply the Skopeo Cloud Monitoring config: one uptime check and two paging alert policies.

Usage: $(basename "$0") [options]

Options:
  -p, --project ID        GCP project            (default: $DEFAULT_PROJECT)
  -r, --region REGION     Cloud Run region       (default: $DEFAULT_REGION)
  -s, --service NAME      Cloud Run service      (default: $DEFAULT_SERVICE)
  -e, --alert-email ADDR  Notification recipient (default: $DEFAULT_ALERT_EMAIL)
  -H, --api-host HOST     Hostname for the uptime check
                          (default: derived from the deployed service)
  -n, --dry-run           Print what would change; modify nothing
  -h, --help              This message

Notes:
  * --api-host matters once a custom domain is mapped. A check against *.run.app stays green through a
    DNS, TLS or domain-mapping failure, which is a full outage for every browser client. There is no
    custom domain today, so the derived default is currently the host users actually reach.
  * Cloud Monitoring does not verify an email channel on creation, and sends from
    alerting-noreply@google.com. Verify delivery after applying — see the runbook.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -p|--project)     PROJECT="$2"; shift 2 ;;
    -r|--region)      REGION="$2"; shift 2 ;;
    -s|--service)     SERVICE="$2"; shift 2 ;;
    -e|--alert-email) ALERT_EMAIL="$2"; shift 2 ;;
    -H|--api-host)    API_HOST="$2"; shift 2 ;;
    -n|--dry-run)     DRY_RUN=1; shift ;;
    -h|--help)        usage; exit 0 ;;
    *) echo "unknown option: $1" >&2; echo >&2; usage >&2; exit 2 ;;
  esac
done

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

run() {
  if [[ -n "$DRY_RUN" ]]; then
    echo "  [dry-run] $*"
  else
    "$@"
  fi
}

echo "project=$PROJECT region=$REGION service=$SERVICE alerts=$ALERT_EMAIL${DRY_RUN:+ (DRY RUN)}"

if [[ -z "$API_HOST" ]]; then
  echo "==> deriving the uptime-check host from the deployed service"
  API_URL="$(gcloud run services describe "$SERVICE" \
    --project "$PROJECT" --region "$REGION" --format='value(status.url)')"
  API_HOST="${API_URL#https://}"
fi
echo "    host: $API_HOST"

# --- Notification channel -----------------------------------------------------------------------
echo "==> notification channel for $ALERT_EMAIL"
CHANNEL="$(gcloud beta monitoring channels list \
  --project "$PROJECT" \
  --filter="type=email AND labels.email_address=$ALERT_EMAIL" \
  --format='value(name)' | head -1)"

if [[ -z "$CHANNEL" ]]; then
  if [[ -n "$DRY_RUN" ]]; then
    echo "  [dry-run] would create an email channel for $ALERT_EMAIL"
    CHANNEL="projects/$PROJECT/notificationChannels/DRY_RUN"
  else
    CHANNEL="$(gcloud beta monitoring channels create \
      --project "$PROJECT" \
      --display-name="Skopeo alerts (team group)" \
      --type=email \
      --channel-labels="email_address=$ALERT_EMAIL" \
      --format='value(name)')"
    echo "    created: $CHANNEL"
  fi
else
  echo "    reusing: $CHANNEL"
fi

# --- Uptime check -------------------------------------------------------------------------------
echo "==> uptime check 'skopeo-api-health'"
sed -e "s|PROJECT_ID_PLACEHOLDER|$PROJECT|g" \
    -e "s|API_HOST_PLACEHOLDER|$API_HOST|g" \
    "$HERE/uptime-check-health.json" > "$WORK/uptime.json"

EXISTING_CHECK="$(gcloud monitoring uptime list-configs \
  --project "$PROJECT" \
  --filter="displayName='skopeo-api-health'" \
  --format='value(name)' | head -1)"

if [[ -z "$EXISTING_CHECK" ]]; then
  run gcloud monitoring uptime create-config-from-file "$WORK/uptime.json" --project "$PROJECT"
else
  echo "    exists, updating: $EXISTING_CHECK"
  run gcloud monitoring uptime update-config-from-file "$EXISTING_CHECK" \
    --config-from-file "$WORK/uptime.json" --project "$PROJECT"
fi

# The uptime alert filters on the check's generated id, which only exists once the check does.
CHECK_ID="$(gcloud monitoring uptime list-configs \
  --project "$PROJECT" \
  --filter="displayName='skopeo-api-health'" \
  --format='value(name)' | head -1 | awk -F/ '{print $NF}')"
if [[ -z "$CHECK_ID" ]]; then
  if [[ -n "$DRY_RUN" ]]; then
    CHECK_ID="DRY_RUN_CHECK_ID"
  else
    echo "could not resolve the uptime check id after creating it" >&2
    exit 1
  fi
fi
echo "    check id: $CHECK_ID"

# --- Alert policies -----------------------------------------------------------------------------
for policy in alert-uptime-failure alert-sustained-5xx; do
  echo "==> alert policy '$policy'"
  sed -e "s|UPTIME_CHECK_ID_PLACEHOLDER|$CHECK_ID|g" \
      -e "s|CLOUD_RUN_SERVICE_PLACEHOLDER|$SERVICE|g" \
      "$HERE/$policy.json" > "$WORK/$policy.json"

  DISPLAY="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["displayName"])' "$WORK/$policy.json")"
  EXISTING="$(gcloud alpha monitoring policies list \
    --project "$PROJECT" \
    --filter="displayName='$DISPLAY'" \
    --format='value(name)' | head -1)"

  if [[ -z "$EXISTING" ]]; then
    run gcloud alpha monitoring policies create \
      --project "$PROJECT" \
      --policy-from-file "$WORK/$policy.json" \
      --notification-channels "$CHANNEL"
  else
    echo "    exists, updating: $EXISTING"
    run gcloud alpha monitoring policies update "$EXISTING" \
      --project "$PROJECT" \
      --policy-from-file "$WORK/$policy.json"
    run gcloud alpha monitoring policies update "$EXISTING" \
      --project "$PROJECT" \
      --set-notification-channels "$CHANNEL"
  fi
done

cat <<DONE

Done. Two paging alerts are configured, and nothing else interrupts (#751 decision 4).

NOW VERIFY DELIVERY — this is not optional. Cloud Monitoring does not verify an email channel when it is
created, and it sends from alerting-noreply@google.com. A group whose posting policy rejects non-members
shows a perfectly healthy channel and delivers nothing.

  1. Email $ALERT_EMAIL from an address that is NOT a member of the group. Confirm it arrives.
  2. Force a real alert and confirm it lands:
     docs/engineering/operations/DEPLOYMENT_RUNBOOK.md#testing-the-alerts
DONE
