#!/bin/bash
#
# Remove a test user from BOTH systems (so a re-test doesn't hit "email already
# registered"): the Firebase Auth account AND the Skopeo database row.
#   - Firebase: sign in to obtain an ID token, then accounts:delete.
#   - Database: DELETE FROM users by email (cascades to names/contacts/ratings/...),
#     via `docker compose exec` on the local Postgres container.
#
# Run from the repo root (so docker compose finds the service). Pairs with createTestUser.sh.
#
# Usage:
#   ./scripts/firebase-integration/deleteTestUser.sh <WEB_API_KEY> [email] [password] \
#       [db_service] [db_name] [db_user]
#
#   WEB_API_KEY  Firebase Web API Key ("AIza...") — REQUIRED (kept out of source).
#   email        default: test@skopeo.dev
#   password     default: Test12345   (needed to sign in so the Firebase account can be deleted)
#   db_service   docker compose service (default: postgres)
#   db_name      database name          (default: SkopeoDb)
#   db_user      database user          (default: postgres)
#
# Note: the email is interpolated into SQL — use it only with trusted test emails.

set -uo pipefail

WEB_API_KEY="${1:-}"
EMAIL="${2:-test@skopeo.dev}"
PASSWORD="${3:-Test12345}"
DB_SERVICE="${4:-postgres}"
DB_NAME="${5:-SkopeoDb}"
DB_USER="${6:-postgres}"

if [[ -z "$WEB_API_KEY" ]]; then
  echo "Error: missing Firebase Web API Key." >&2
  echo "Usage: $0 <WEB_API_KEY> [email] [password] [db_service] [db_name] [db_user]" >&2
  exit 1
fi

IDENTITY="https://identitytoolkit.googleapis.com/v1"

echo "1/2 Deleting Firebase user '$EMAIL'..." >&2
SIGNIN="$(curl -s -X POST "$IDENTITY/accounts:signInWithPassword?key=${WEB_API_KEY}" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"${EMAIL}\",\"password\":\"${PASSWORD}\",\"returnSecureToken\":true}")"
TOKEN="$(echo "$SIGNIN" | jq -r '.idToken // empty')"

if [[ -z "$TOKEN" ]]; then
  echo "    could not sign in (user may not exist in Firebase / wrong password); skipping Firebase delete." >&2
else
  DEL="$(curl -s -X POST "$IDENTITY/accounts:delete?key=${WEB_API_KEY}" \
    -H 'Content-Type: application/json' \
    -d "{\"idToken\":\"${TOKEN}\"}")"
  if echo "$DEL" | jq -e '.error' >/dev/null 2>&1; then
    echo "    Firebase delete error: $(echo "$DEL" | jq -r '.error.message')" >&2
  else
    echo "    Firebase user deleted." >&2
  fi
fi

echo "2/2 Deleting DB profile for '$EMAIL' (cascades) ..." >&2
docker compose exec -T "$DB_SERVICE" psql -U "$DB_USER" -d "$DB_NAME" -c \
  "DELETE FROM users WHERE id IN (SELECT user_id FROM contact_information WHERE value = '${EMAIL}');"
