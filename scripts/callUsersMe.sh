#!/bin/bash
#
# Call the authenticated `GET /api/v1/users/me` probe with a Firebase ID token,
# to smoke-test that the API verifies the token and returns the identity.
#
# Usage: ./scripts/callUsersMe.sh <FIREBASE_ID_TOKEN> [base_url]
#   FIREBASE_ID_TOKEN  A Firebase ID token — REQUIRED.
#                      Mint one with: ./scripts/createFirebaseToken.sh <WEB_API_KEY>
#   base_url           API base URL (default: http://localhost:8080)
#
# Example (pipe the two scripts together):
#   ./scripts/callUsersMe.sh "$(./scripts/createFirebaseToken.sh AIzaSyD...XYZ)"

set -euo pipefail

TOKEN="${1:-}"
BASE_URL="${2:-http://localhost:8080}"

if [[ -z "$TOKEN" ]]; then
  echo "Error: missing Firebase ID token." >&2
  echo "Usage: $0 <FIREBASE_ID_TOKEN> [base_url]" >&2
  echo "  Mint a token first: ./scripts/createFirebaseToken.sh <WEB_API_KEY>" >&2
  exit 1
fi

# Capture body + HTTP status separately so we can report failures clearly.
HTTP_BODY="$(mktemp)"
trap 'rm -f "$HTTP_BODY"' EXIT

STATUS="$(curl -s -o "$HTTP_BODY" -w "%{http_code}" \
  -H "Authorization: Bearer ${TOKEN}" \
  "${BASE_URL}/api/v1/users/me")"

echo "HTTP $STATUS"
jq . "$HTTP_BODY" 2>/dev/null || cat "$HTTP_BODY"

case "$STATUS" in
  200)
    echo "OK: token verified, identity returned above." >&2
    ;;
  401)
    echo "Unauthorized: token rejected. If the token is valid, the API's FIREBASE_PROJECT_ID" >&2
    echo "likely doesn't match the token's project (issuer/audience mismatch)." >&2
    exit 1
    ;;
  000)
    echo "No response: is the API running at ${BASE_URL}? (./gradlew run)" >&2
    exit 1
    ;;
  *)
    echo "Unexpected status $STATUS." >&2
    exit 1
    ;;
esac
