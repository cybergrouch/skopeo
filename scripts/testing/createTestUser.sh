#!/bin/bash
#
# Create a test user end to end: a Firebase Email/Password account PLUS the Skopeo
# database profile (provisioned via POST /api/v1/users). Idempotent-ish — if the
# Firebase user already exists it signs in instead, then (re)provisions the profile
# (provisioning is idempotent). Pairs with deleteTestUser.sh.
#
# The Firebase Web API key is read from web/.env.local (VITE_FIREBASE_API_KEY). If that
# file is missing, provide the key via the WEB_API_KEY environment variable.
#
# Usage:
#   ./scripts/testing/createTestUser.sh [email] [password] [displayName] [sex] [dateOfBirth] [base_url]
#   WEB_API_KEY=AIza... ./scripts/testing/createTestUser.sh [email] ...   # if web/.env.local is absent
#
#   email        default: test@skopeo.dev
#   password     default: Test12345
#   displayName  default: "Test User"
#   sex          Male | Female (default: Male)
#   dateOfBirth  yyyy-MM-dd    (default: 2000-01-01)
#   base_url     API base URL  (default: http://localhost:8080)
#
# Prints the created Skopeo profile JSON (includes id and publicCode) to stdout.
#
# Note: Firebase signUp creates an UNVERIFIED email, so this user is NOT eligible for the
# ADMIN_EMAILS bootstrap (that requires a verified email — use Google sign-in for an admin).

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# Resolve the Firebase Web API key: explicit WEB_API_KEY env var wins, else web/.env.local.
resolve_web_api_key() {
  if [[ -n "${WEB_API_KEY:-}" ]]; then
    printf '%s' "$WEB_API_KEY"
    return 0
  fi
  local env_file="$REPO_ROOT/web/.env.local"
  if [[ -f "$env_file" ]]; then
    awk -F= '/^VITE_FIREBASE_API_KEY=/{sub(/^VITE_FIREBASE_API_KEY=/, ""); gsub(/["'"'"' \r]/, ""); print; exit}' "$env_file"
    return 0
  fi
  return 1
}

WEB_API_KEY="$(resolve_web_api_key)"
if [[ -z "${WEB_API_KEY:-}" ]]; then
  echo "Error: no Firebase Web API key found." >&2
  echo "  Put it in web/.env.local (VITE_FIREBASE_API_KEY=AIza...), or run with:" >&2
  echo "    WEB_API_KEY=AIza... $0 [email] [password] ..." >&2
  exit 1
fi

EMAIL="${1:-test@skopeo.dev}"
PASSWORD="${2:-Test12345}"
DISPLAY_NAME="${3:-Test User}"
SEX="${4:-Male}"
DOB="${5:-2000-01-01}"
BASE_URL="${6:-http://localhost:8080}"

IDENTITY="https://identitytoolkit.googleapis.com/v1"

echo "1/2 Creating Firebase user '$EMAIL'..." >&2
RESP="$(curl -s -X POST "$IDENTITY/accounts:signUp?key=${WEB_API_KEY}" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"${EMAIL}\",\"password\":\"${PASSWORD}\",\"returnSecureToken\":true}")"
TOKEN="$(echo "$RESP" | jq -r '.idToken // empty')"

if [[ -z "$TOKEN" && "$(echo "$RESP" | jq -r '.error.message // empty')" == "EMAIL_EXISTS" ]]; then
  echo "    user already exists in Firebase; signing in instead." >&2
  RESP="$(curl -s -X POST "$IDENTITY/accounts:signInWithPassword?key=${WEB_API_KEY}" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"${EMAIL}\",\"password\":\"${PASSWORD}\",\"returnSecureToken\":true}")"
  TOKEN="$(echo "$RESP" | jq -r '.idToken // empty')"
fi

if [[ -z "$TOKEN" ]]; then
  echo "Error: could not create or sign in the Firebase user. Response:" >&2
  echo "$RESP" | jq . >&2 2>/dev/null || echo "$RESP" >&2
  exit 1
fi

echo "2/2 Provisioning Skopeo profile via POST $BASE_URL/api/v1/users ..." >&2
PROFILE="$(curl -s -X POST "$BASE_URL/api/v1/users" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"displayName\":\"${DISPLAY_NAME}\",\"sex\":\"${SEX}\",\"dateOfBirth\":\"${DOB}\"}")"

echo "$PROFILE" | jq . 2>/dev/null || echo "$PROFILE"
