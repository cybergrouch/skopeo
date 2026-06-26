#!/bin/bash
#
# Create a test user end to end: a Firebase Email/Password account PLUS the Skopeo
# database profile (provisioned via POST /api/v1/users). Idempotent-ish — if the
# Firebase user already exists it signs in instead, then (re)provisions the profile
# (provisioning is idempotent). Pairs with deleteTestUser.sh.
#
# Manual sign-ups are invite-only (issue #74), so this seeds an open invite for the
# email directly in the local DB (via docker compose) before provisioning. Needs the
# Postgres container running (docker compose up).
#
# The Firebase Web API key is read from web/.env.local (VITE_FIREBASE_API_KEY). If that
# file is missing, provide the key via the WEB_API_KEY environment variable.
#
# Usage:
#   ./scripts/testing/createTestUser.sh <email> <password> <displayName> <sex> <dateOfBirth> [base_url] [db_service] [db_name] [db_user]
#   WEB_API_KEY=AIza... ./scripts/testing/createTestUser.sh <email> ...   # if web/.env.local is absent
#
#   email        required
#   password     required
#   displayName  required
#   sex          required  Male | Female
#   dateOfBirth  required  yyyy-MM-dd
#   base_url     optional  API base URL              (default: http://localhost:8080)
#   db_service   optional  docker compose service    (default: postgres)
#   db_name      optional  database name             (default: SkopeoDb)
#   db_user      optional  database user             (default: postgres)
#
# Prints the created Skopeo profile JSON (includes id and publicCode) to stdout.
#
# Note: Firebase signUp creates an UNVERIFIED email, so this user is NOT eligible for the
# ADMIN_EMAILS bootstrap (that requires a verified email — use Google sign-in for an admin).

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

usage() {
  cat >&2 <<'USAGE'
Usage:
  ./scripts/testing/createTestUser.sh <email> <password> <displayName> <sex> <dateOfBirth> [base_url] [db_service] [db_name] [db_user]

  email        required
  password     required
  displayName  required
  sex          required  Male | Female
  dateOfBirth  required  yyyy-MM-dd
  base_url     optional  API base URL           (default: http://localhost:8080)
  db_service   optional  docker compose service (default: postgres)
  db_name      optional  database name          (default: SkopeoDb)
  db_user      optional  database user          (default: postgres)

Manual sign-ups are invite-only (#74); this seeds an open invite in the local DB
(docker compose) before provisioning, so the Postgres container must be running.

The Firebase Web API key is read from web/.env.local (VITE_FIREBASE_API_KEY),
or pass it via the WEB_API_KEY environment variable.
USAGE
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

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

EMAIL="${1:-}"
PASSWORD="${2:-}"
DISPLAY_NAME="${3:-}"
SEX="${4:-}"
DOB="${5:-}"
BASE_URL="${6:-http://localhost:8080}"
DB_SERVICE="${7:-postgres}"
DB_NAME="${8:-SkopeoDb}"
DB_USER="${9:-postgres}"

if [[ -z "$EMAIL" || -z "$PASSWORD" || -z "$DISPLAY_NAME" || -z "$SEX" || -z "$DOB" ]]; then
  echo "Error: missing required arguments." >&2
  echo >&2
  usage
  exit 1
fi

IDENTITY="https://identitytoolkit.googleapis.com/v1"

echo "1/3 Creating Firebase user '$EMAIL'..." >&2
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

# Manual (email/password) sign-ups are invite-only (issue #74). Seed an open invite for this email
# directly in the DB so provisioning is admitted — the script's user can't mint an admin token to use
# the invites API. Idempotent enough for testing: an extra PENDING row is harmless.
echo "2/3 Seeding an invite for '$EMAIL' (docker compose db) ..." >&2
docker compose --project-directory "$REPO_ROOT" exec -T "$DB_SERVICE" psql -U "$DB_USER" -d "$DB_NAME" -c \
  "INSERT INTO invites (email, status, expires_at) VALUES (lower('${EMAIL}'), 'PENDING', now() + interval '7 days');" >&2

echo "3/3 Provisioning Skopeo profile via POST $BASE_URL/api/v1/users ..." >&2
PROFILE="$(curl -s -X POST "$BASE_URL/api/v1/users" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"displayName\":\"${DISPLAY_NAME}\",\"sex\":\"${SEX}\",\"dateOfBirth\":\"${DOB}\"}")"

echo "$PROFILE" | jq . 2>/dev/null || echo "$PROFILE"
