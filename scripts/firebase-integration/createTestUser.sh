#!/bin/bash
#
# Create a test user end to end: a Firebase Email/Password account PLUS the Skopeo
# database profile (provisioned via POST /api/v1/users). Idempotent-ish — if the
# Firebase user already exists it signs in instead, then (re)provisions the profile
# (provisioning is idempotent). Pairs with deleteTestUser.sh.
#
# Usage:
#   ./scripts/firebase-integration/createTestUser.sh <WEB_API_KEY> [email] [password] \
#       [displayName] [sex] [dateOfBirth] [base_url]
#
#   WEB_API_KEY  Firebase Web API Key ("AIza...") — REQUIRED (kept out of source).
#                Firebase console -> Project settings -> General (Web API Key).
#   email        default: test@skopeo.dev
#   password     default: Test12345
#   displayName  default: "Test User"
#   sex          Male | Female (default: Male)
#   dateOfBirth  yyyy-MM-dd    (default: 2000-01-01)
#   base_url     API base URL  (default: http://localhost:8080)
#
# Prints the created Skopeo profile JSON (includes id and publicCode) to stdout.
#
# Note: Firebase signUp creates an UNVERIFIED email, so this user is NOT eligible for
# the ADMIN_EMAILS bootstrap (that requires a verified email — use Google sign-in for an admin).

set -uo pipefail

WEB_API_KEY="${1:-}"
EMAIL="${2:-test@skopeo.dev}"
PASSWORD="${3:-Test12345}"
DISPLAY_NAME="${4:-Test User}"
SEX="${5:-Male}"
DOB="${6:-2000-01-01}"
BASE_URL="${7:-http://localhost:8080}"

if [[ -z "$WEB_API_KEY" ]]; then
  echo "Error: missing Firebase Web API Key." >&2
  echo "Usage: $0 <WEB_API_KEY> [email] [password] [displayName] [sex] [dateOfBirth] [base_url]" >&2
  exit 1
fi

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
