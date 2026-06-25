#!/bin/bash
#
# Remove a test user from BOTH systems (so a re-test doesn't hit "email already
# registered"): the Firebase Auth account AND the Skopeo database row.
#   - Firebase: sign in to obtain an ID token, then accounts:delete.
#   - Database: DELETE FROM users by email (cascades to names/contacts/ratings/...),
#     via `docker compose exec` on the local Postgres container.
#
# The Firebase Web API key is read from web/.env.local (VITE_FIREBASE_API_KEY). If that
# file is missing, provide the key via the WEB_API_KEY environment variable. Pairs with
# createTestUser.sh.
#
# Usage:
#   ./scripts/testing/deleteTestUser.sh <email> <password> [db_service] [db_name] [db_user]
#   WEB_API_KEY=AIza... ./scripts/testing/deleteTestUser.sh <email> ...   # if web/.env.local is absent
#
#   email        required
#   password     required  (needed to sign in so the Firebase account can be deleted)
#   db_service   optional  docker compose service (default: postgres)
#   db_name      optional  database name          (default: SkopeoDb)
#   db_user      optional  database user          (default: postgres)
#
# Note: the email is interpolated into SQL — use it only with trusted test emails.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

usage() {
  cat >&2 <<'USAGE'
Usage:
  ./scripts/testing/deleteTestUser.sh <email> <password> [db_service] [db_name] [db_user]

  email        required
  password     required  (needed to sign in so the Firebase account can be deleted)
  db_service   optional  docker compose service (default: postgres)
  db_name      optional  database name          (default: SkopeoDb)
  db_user      optional  database user          (default: postgres)

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
DB_SERVICE="${3:-postgres}"
DB_NAME="${4:-SkopeoDb}"
DB_USER="${5:-postgres}"

if [[ -z "$EMAIL" || -z "$PASSWORD" ]]; then
  echo "Error: missing required arguments." >&2
  echo >&2
  usage
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
docker compose --project-directory "$REPO_ROOT" exec -T "$DB_SERVICE" psql -U "$DB_USER" -d "$DB_NAME" -c \
  "DELETE FROM users WHERE id IN (SELECT user_id FROM contact_information WHERE value = '${EMAIL}');"
