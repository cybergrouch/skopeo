#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Lange Pantoja
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Apply the Skopeo Cloud Monitoring config (#808/#809): uptime check, two paging alert policies,
# two log-based per-endpoint metrics, and the dashboard.
#
#     ./infra/monitoring/apply.sh --dry-run     # show what would change; validates the dashboard schema
#     ./infra/monitoring/apply.sh               # apply
#
# Every value defaults to this project's real setting, so no exports are needed. Flags override;
# precedence is flag > .env.local > environment > default. See .env.local.example for the overrides.
#
# Idempotent: each resource is looked up and updated in place rather than duplicated. That matters here
# specifically — a duplicated alert policy pages twice, the exact noise #751's two-alert budget avoids.
#
# Needs roles/monitoring.editor and roles/logging.configWriter.
set -euo pipefail

# Never let gcloud stop to ask a question. Without this, a missing component triggers an interactive
# installer *inside a command substitution*, and its progress output gets captured as if it were the
# command's result — which is how an earlier version of this script ended up passing
# "/Applications/Xcode.app/Contents/Developer" as a notification channel id.
export CLOUDSDK_CORE_DISABLE_PROMPTS=1

# Optional local overrides, gitignored — a private place for anything you would rather not publish in
# what is a public repository. See .env.local.example. Sourced before the defaults are resolved, so a
# value set here is picked up by the ${VAR:-default} expansions below and can still be beaten by a flag.
if [[ -f "$(dirname "${BASH_SOURCE[0]}")/.env.local" ]]; then
  # shellcheck disable=SC1091  # path is resolved at runtime
  source "$(dirname "${BASH_SOURCE[0]}")/.env.local"
fi

DEFAULT_PROJECT="skopeo-prod"
DEFAULT_REGION="asia-southeast1"
DEFAULT_SERVICE="skopeo"
# A team-managed group, never a personal address (#190).
DEFAULT_ALERT_EMAIL="skopeo-alerts@googlegroups.com"

# Uptime check parameters. These live here rather than in a JSON file because `gcloud monitoring uptime
# create` is flag-based — unlike policies, dashboards and log metrics, it has no --config-from-file.
UPTIME_NAME="skopeo-api-health"
UPTIME_PATH="/health"
UPTIME_PERIOD="1"                                    # minutes; allowed: 1, 5, 10, 15
UPTIME_TIMEOUT="10"                                  # seconds
UPTIME_REGIONS="asia-pacific,europe,usa-oregon"      # >=3 required; asia-pacific is nearest the service

PROJECT="${GCP_PROJECT_ID:-$DEFAULT_PROJECT}"
REGION="${GCP_REGION:-$DEFAULT_REGION}"
SERVICE="${CLOUD_RUN_SERVICE:-$DEFAULT_SERVICE}"
ALERT_EMAIL="${ALERT_EMAIL:-$DEFAULT_ALERT_EMAIL}"
# Not defaulted to a hostname: there is no custom domain for the API today (api.skopeo.co has no DNS
# record; the SPA calls the *.run.app URL), and the run.app host carries a per-service hash, so deriving
# it cannot drift the way a hardcoded copy would.
API_HOST="${API_HOST:-}"
DRY_RUN="${DRY_RUN:-}"

usage() {
  cat <<USAGE
Apply the Skopeo Cloud Monitoring config: uptime check, two paging alert policies,
two log-based per-endpoint metrics, and the dashboard.

Usage: $(basename "$0") [options]

Options:
  -p, --project ID        GCP project            (default: $DEFAULT_PROJECT)
  -r, --region REGION     Cloud Run region       (default: $DEFAULT_REGION)
  -s, --service NAME      Cloud Run service      (default: $DEFAULT_SERVICE)
  -e, --alert-email ADDR  Notification recipient (default: $DEFAULT_ALERT_EMAIL)
  -H, --api-host HOST     Host for the uptime check
                          (default: derived from the deployed service)
  -n, --dry-run           Print what would change; modify nothing. Still performs read-only
                          lookups, and validates the dashboard schema server-side.
  -h, --help              This message

Notes:
  * --api-host matters once a custom domain is mapped: a check against *.run.app stays green through a
    DNS, TLS or domain-mapping failure, which is a full outage for every browser client.
  * Cloud Monitoring does not verify an email channel on creation and sends from
    alerting-noreply@google.com, so verify delivery afterwards — see the runbook.
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

# Keep only lines that actually look like a Monitoring resource name. Any other stdout — a component
# installer, a pip log, a warning — is discarded rather than used as an id.
only_resource_name() { grep -E '^projects/[A-Za-z0-9_.-]+/[A-Za-z]+/[A-Za-z0-9_.-]+$' | head -1 || true; }

# --- Warm up the command groups, OUTSIDE any command substitution --------------------------------
# `gcloud monitoring policies|uptime|dashboards` and `gcloud logging metrics` are GA; only
# `gcloud beta monitoring channels` needs a component that may be missing.
#
# The hazard is not the install itself — it is installing *inside* `$(...)`, where the installer's
# progress output is captured as if it were the command's result. So touch the group once here, where
# stdout goes to the terminal and any first-run noise is harmless and visible.
#
# Deliberately NOT a version/state check on `gcloud components list`: its `--format` output differs
# between gcloud releases, so parsing it produced a false negative that blocked a machine where the
# component was in fact installed.
gcloud beta monitoring channels list --project "${GCP_PROJECT_ID:-$DEFAULT_PROJECT}" --limit=1 \
  >/dev/null 2>&1 || true

echo "project=$PROJECT region=$REGION service=$SERVICE alerts=$ALERT_EMAIL${DRY_RUN:+ (DRY RUN)}"

if [[ -z "$API_HOST" ]]; then
  echo "==> deriving the uptime-check host from the deployed service"
  API_URL="$(gcloud run services describe "$SERVICE" \
    --project "$PROJECT" --region "$REGION" --format='value(status.url)' 2>/dev/null || true)"
  API_HOST="${API_URL#https://}"
  if [[ -z "$API_HOST" ]]; then
    echo "could not resolve the Cloud Run URL for '$SERVICE' in $REGION; pass --api-host" >&2
    exit 1
  fi
fi
echo "    host: $API_HOST"

# --- Notification channel -----------------------------------------------------------------------
# Matched locally from the full JSON rather than with a server-side --filter: the filter keys for
# channels are not what they look like (`type`/`labels.email_address` produced "filter keys were not
# present in any resource" warnings and matched nothing), and a silently-empty match here is what let
# garbage through before.
echo "==> notification channel for $ALERT_EMAIL"
CHANNEL="$(gcloud beta monitoring channels list --project "$PROJECT" --format=json 2>/dev/null \
  | python3 -c '
import json, sys
want = sys.argv[1]
try:
    channels = json.load(sys.stdin)
except Exception:
    sys.exit(0)
for c in channels if isinstance(channels, list) else []:
    labels = c.get("labels") or {}
    if labels.get("email_address") == want:
        print(c.get("name", ""))
        break
' "$ALERT_EMAIL" | only_resource_name)"

if [[ -z "$CHANNEL" ]]; then
  # Distinguish "no such channel yet" from "the command could not run at all" — otherwise a missing
  # beta component looks identical to a first-time apply, and the script would cheerfully continue.
  if ! gcloud beta monitoring channels list --project "$PROJECT" --limit=1 >/dev/null 2>&1; then
    cat >&2 <<MSG

Cannot list notification channels. \`gcloud beta monitoring channels\` did not run — most likely the
gcloud "beta" component is unavailable:

    gcloud components install beta

MSG
    exit 1
  fi
  if [[ -n "$DRY_RUN" ]]; then
    echo "  [dry-run] would create an email channel for $ALERT_EMAIL"
    CHANNEL="projects/$PROJECT/notificationChannels/DRY-RUN"
  else
    CHANNEL="$(gcloud beta monitoring channels create \
      --project "$PROJECT" \
      --display-name="Skopeo alerts (team group)" \
      --type=email \
      --channel-labels="email_address=$ALERT_EMAIL" \
      --format='value(name)' 2>/dev/null | only_resource_name)"
    [[ -n "$CHANNEL" ]] || { echo "failed to create the notification channel" >&2; exit 1; }
    echo "    created: $CHANNEL"
  fi
else
  echo "    reusing: $CHANNEL"
fi

# --- Uptime check -------------------------------------------------------------------------------
echo "==> uptime check '$UPTIME_NAME'"
UPTIME_FULL="$(gcloud monitoring uptime list-configs --project "$PROJECT" \
  --format='value(name)' --filter="displayName=$UPTIME_NAME" 2>/dev/null | only_resource_name)"

uptime_flags=(
  --project "$PROJECT"
  --resource-type=uptime-url
  --resource-labels="host=$API_HOST,project_id=$PROJECT"
  --protocol=https
  --port=443
  --path="$UPTIME_PATH"
  --request-method=get
  --status-classes=2xx
  --validate-ssl=true
  --period="$UPTIME_PERIOD"
  --timeout="$UPTIME_TIMEOUT"
  --regions="$UPTIME_REGIONS"
)

if [[ -z "$UPTIME_FULL" ]]; then
  run gcloud monitoring uptime create "$UPTIME_NAME" "${uptime_flags[@]}"
else
  echo "    exists, updating: $UPTIME_FULL"
  # `update` takes the check's id and rejects --resource-type/--resource-labels, which are immutable.
  run gcloud monitoring uptime update "$UPTIME_FULL" \
    --project "$PROJECT" \
    --path="$UPTIME_PATH" \
    --status-classes=2xx \
    --period="$UPTIME_PERIOD" \
    --timeout="$UPTIME_TIMEOUT" \
    --regions="$UPTIME_REGIONS"
fi

# The uptime alert filters on the check's generated id, which only exists once the check does.
CHECK_ID="$(gcloud monitoring uptime list-configs --project "$PROJECT" \
  --format='value(name)' --filter="displayName=$UPTIME_NAME" 2>/dev/null \
  | only_resource_name | awk -F/ '{print $NF}' | grep -E '^[A-Za-z0-9_-]+$' || true)"
if [[ -z "$CHECK_ID" ]]; then
  if [[ -n "$DRY_RUN" ]]; then
    CHECK_ID="dry-run-check-id"
  else
    echo "could not resolve the uptime check id after creating it" >&2
    exit 1
  fi
fi
echo "    check id: $CHECK_ID"

# --- Alert policies -----------------------------------------------------------------------------
# `gcloud monitoring policies` is GA; the alpha surface an earlier version used is unnecessary.
for policy in alert-uptime-failure alert-sustained-5xx; do
  echo "==> alert policy '$policy'"
  sed -e "s|UPTIME_CHECK_ID_PLACEHOLDER|$CHECK_ID|g" \
      -e "s|CLOUD_RUN_SERVICE_PLACEHOLDER|$SERVICE|g" \
      "$HERE/$policy.json" > "$WORK/$policy.json"

  DISPLAY="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["displayName"])' "$WORK/$policy.json")"
  EXISTING="$(gcloud monitoring policies list --project "$PROJECT" \
    --format='value(name)' --filter="displayName=\"$DISPLAY\"" 2>/dev/null | only_resource_name)"

  if [[ -z "$EXISTING" ]]; then
    run gcloud monitoring policies create --project "$PROJECT" \
      --policy-from-file "$WORK/$policy.json" --notification-channels "$CHANNEL"
  else
    echo "    exists, updating: $EXISTING"
    run gcloud monitoring policies update "$EXISTING" --project "$PROJECT" \
      --policy-from-file "$WORK/$policy.json"
    run gcloud monitoring policies update "$EXISTING" --project "$PROJECT" \
      --set-notification-channels "$CHANNEL"
  fi
done

# --- Log-based metrics --------------------------------------------------------------------------
# These are what make per-endpoint monitoring possible: Cloud Run's own request_count and
# request_latencies have NO path/route label, so natively you can see "the service returned 40 5xx" but
# never which endpoint. They extract the route pattern from the access line (#805).
#
# They do NOT backfill — a log-based metric counts from creation — so applying early is what gives the
# two-week alert-budget review (#751 decision 4) a baseline to judge against.
for metric in skopeo_requests skopeo_request_latency; do
  echo "==> log-based metric '$metric'"
  sed -e "s|CLOUD_RUN_SERVICE_PLACEHOLDER|$SERVICE|g" \
      "$HERE/log-metrics/$metric.json" > "$WORK/$metric.json"

  if gcloud logging metrics describe "$metric" --project "$PROJECT" >/dev/null 2>&1; then
    echo "    exists, updating"
    run gcloud logging metrics update "$metric" --project "$PROJECT" \
      --config-from-file "$WORK/$metric.json"
  else
    run gcloud logging metrics create "$metric" --project "$PROJECT" \
      --config-from-file "$WORK/$metric.json"
  fi
done

# --- Dashboard ----------------------------------------------------------------------------------
echo "==> dashboard 'Skopeo API'"
sed -e "s|CLOUD_RUN_SERVICE_PLACEHOLDER|$SERVICE|g" \
    -e "s|UPTIME_CHECK_ID_PLACEHOLDER|$CHECK_ID|g" \
    "$HERE/dashboard.json" > "$WORK/dashboard.json"

if [[ -n "$DRY_RUN" ]]; then
  # --validate-only is a real server-side schema check ("validate the dashboard but do not save it"),
  # so a dry run genuinely catches a malformed widget rather than only echoing the command.
  #
  # Its output is captured rather than shown, because gcloud prints "Created [<uuid>]" from the object
  # the API echoes back — which reads alarmingly like a dry run having mutated something. Nothing is
  # persisted; the id belongs to a response, not a saved dashboard.
  echo "  [dry-run] validating the dashboard schema server-side (validate-only: nothing is saved)"
  if validate_out="$(gcloud monitoring dashboards create --project "$PROJECT" \
      --config-from-file "$WORK/dashboard.json" --validate-only 2>&1)"; then
    echo "    schema OK — nothing saved"
  else
    echo "$validate_out" >&2
    echo "    schema INVALID: the dashboard would be rejected" >&2
    exit 1
  fi
else
  EXISTING_DASH="$(gcloud monitoring dashboards list --project "$PROJECT" \
    --format='value(name)' --filter="displayName='Skopeo API'" 2>/dev/null | only_resource_name)"
  if [[ -z "$EXISTING_DASH" ]]; then
    gcloud monitoring dashboards create --project "$PROJECT" --config-from-file "$WORK/dashboard.json"
  else
    echo "    exists, updating: $EXISTING_DASH"
    gcloud monitoring dashboards update "$EXISTING_DASH" --project "$PROJECT" \
      --config-from-file "$WORK/dashboard.json"
  fi
fi

cat <<DONE

Done. Two paging alerts and nothing else interrupts (#751 decision 4), plus the log-based per-endpoint
metrics and the dashboard.

Dashboard: https://console.cloud.google.com/monitoring/dashboards?project=$PROJECT

The per-endpoint panels stay EMPTY until traffic accumulates — log-based metrics do not backfill, so
they only count from the moment they were created.

NOW VERIFY DELIVERY — this is not optional. Cloud Monitoring does not verify an email channel when it is
created, and sends from alerting-noreply@google.com. A group whose posting policy rejects non-members
shows a perfectly healthy channel and delivers nothing.

  1. Email $ALERT_EMAIL from an address that is NOT a member of the group. Confirm it arrives.
  2. Force a real alert:
     docs/engineering/operations/DEPLOYMENT_RUNBOOK.md#testing-the-alerts
DONE
