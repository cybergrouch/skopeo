#!/bin/bash
#
# One-step auth smoke test: mint a Firebase ID token and call the authenticated
# `GET /api/v1/users/me` probe with it — chaining createFirebaseToken.sh and
# callUsersMe.sh. A HTTP 200 with your identity proves the auth path end to end.
#
# Usage: ./scripts/firebase-integration/smokeTestAuth.sh <WEB_API_KEY> [email] [password] [base_url]
#   WEB_API_KEY   Firebase Web API Key (starts with "AIza...") — REQUIRED.
#   email         Sign-in email    (default: test@skopeo.dev)
#   password      Sign-in password (default: Test12345)
#   base_url      API base URL     (default: http://localhost:8080)
#
# Example:
#   ./scripts/firebase-integration/smokeTestAuth.sh AIzaSyD...XYZ

set -uo pipefail

WEB_API_KEY="${1:-}"
EMAIL="${2:-test@skopeo.dev}"
PASSWORD="${3:-Test12345}"
BASE_URL="${4:-http://localhost:8080}"

if [[ -z "$WEB_API_KEY" ]]; then
  echo "Error: missing Firebase Web API Key." >&2
  echo "Usage: $0 <WEB_API_KEY> [email] [password] [base_url]" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "1/2 Minting Firebase ID token (user: $EMAIL)..."
TOKEN="$("$SCRIPT_DIR/createFirebaseToken.sh" "$WEB_API_KEY" "$EMAIL" "$PASSWORD")" || {
  echo "Failed to mint a token (see error above)." >&2
  exit 1
}
echo "    got a token (${#TOKEN} chars)"

echo "2/2 Calling $BASE_URL/api/v1/users/me ..."
"$SCRIPT_DIR/callUsersMe.sh" "$TOKEN" "$BASE_URL"
