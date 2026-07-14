#!/bin/bash
# SPDX-FileCopyrightText: 2026 Lange Pantoja
# SPDX-License-Identifier: AGPL-3.0-or-later

# Health/smoke check against a running Skopeo API. Doubles as the "restore verified" step after
# restore-prod-to-local.sh: point it at the app running on the restored copy to confirm it boots
# and serves the imported data.
#
# Usage:
#   ./scripts/health-check.sh [BASE_URL]        # default http://localhost:8080
#
# Optional data-sanity check (runs only if the local postgres container is up):
#   CONTAINER   local postgres container  (default: skopeo_db)
#   LOCAL_DB    restored db to sample     (default: skopeo_prodcopy)

set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
CONTAINER="${CONTAINER:-skopeo_db}"
LOCAL_DB="${LOCAL_DB:-skopeo_prodcopy}"

echo "🩺 Health-checking ${BASE_URL}"

# 1. /health must report UP.
if ! health="$(curl -fsS "${BASE_URL}/health")"; then
  echo "❌ /health unreachable — is the server running?" >&2
  exit 1
fi
echo "   /health → ${health}"
if ! grep -q '"status":"UP"' <<<"$health"; then
  echo "❌ /health did not report status UP" >&2
  exit 1
fi

# 2. Root must respond 200.
root_code="$(curl -s -o /dev/null -w '%{http_code}' "${BASE_URL}/")"
echo "   /       → ${root_code}"
if [[ "$root_code" != "200" ]]; then
  echo "❌ / returned ${root_code}" >&2
  exit 1
fi

# 3. Metrics is informational (may be gated) — report but don't fail the check.
metrics_code="$(curl -s -o /dev/null -w '%{http_code}' "${BASE_URL}/metrics")"
echo "   /metrics → ${metrics_code} (informational)"

# 4. Data-sanity: sample row counts from the restored db, if the local container is up.
if docker exec "$CONTAINER" pg_isready -U postgres >/dev/null 2>&1; then
  counts="$(docker exec "$CONTAINER" psql -U postgres -d "$LOCAL_DB" -tAc \
    "SELECT 'users='||count(*) FROM users
     UNION ALL SELECT 'ratings='||count(*) FROM user_ratings
     UNION ALL SELECT 'matches='||count(*) FROM matches;" 2>/dev/null || true)"
  if [[ -n "$counts" ]]; then
    echo "   data sanity (${LOCAL_DB}):"
    sed 's/^/     /' <<<"$counts"
  fi
fi

echo "✅ Health check passed."
