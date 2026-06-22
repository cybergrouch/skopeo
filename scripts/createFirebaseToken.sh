#!/bin/bash
#
# Mint a Firebase ID token via the Email/Password sign-in REST API, for testing
# authenticated endpoints (e.g. `GET /api/v1/users/me`). Prints the raw idToken.
#
# Usage: ./scripts/createFirebaseToken.sh <WEB_API_KEY> [email] [password]
#   WEB_API_KEY   Firebase Web API Key (starts with "AIza...") — REQUIRED.
#                 Firebase console -> Project settings -> General (Web API Key),
#                 or the apiKey field in your Web app config.
#   email         Sign-in email    (default: test@skopeo.dev)
#   password      Sign-in password (default: Test12345)
#
# Example:
#   TOKEN="$(./scripts/createFirebaseToken.sh AIzaSyD...XYZ)"
#   curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/users/me | jq

set -euo pipefail

WEB_API_KEY="${1:-}"
EMAIL="${2:-test@skopeo.dev}"
PASSWORD="${3:-Test12345}"

if [[ -z "$WEB_API_KEY" ]]; then
  echo "Error: missing Firebase Web API Key." >&2
  echo "Usage: $0 <WEB_API_KEY> [email] [password]" >&2
  echo "  Get the key from Firebase console -> Project settings -> General (Web API Key)," >&2
  echo "  or the apiKey field in your Web app config (starts with 'AIza...')." >&2
  exit 1
fi

RESPONSE="$(curl -s -X POST \
  "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${WEB_API_KEY}" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"${EMAIL}\",\"password\":\"${PASSWORD}\",\"returnSecureToken\":true}")"

ID_TOKEN="$(echo "$RESPONSE" | jq -r '.idToken // empty')"

if [[ -z "$ID_TOKEN" ]]; then
  echo "Error: failed to obtain a token. Firebase responded:" >&2
  echo "$RESPONSE" | jq . >&2 2>/dev/null || echo "$RESPONSE" >&2
  exit 1
fi

echo "$ID_TOKEN"
