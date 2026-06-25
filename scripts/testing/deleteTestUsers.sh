#!/bin/bash
#
# Batch-remove the test users defined in _testUserRoster.sh by delegating each one to
# deleteTestUser.sh (Firebase account + Skopeo DB row). Pairs with createTestUsers.sh.
#
# The Firebase Web API key is resolved by deleteTestUser.sh itself (web/.env.local or
# the WEB_API_KEY env var).
#
# Usage:
#   ./scripts/testing/deleteTestUsers.sh [db_service] [db_name] [db_user]
#
#   db_service   optional  docker compose service (default: postgres)
#   db_name      optional  database name          (default: SkopeoDb)
#   db_user      optional  database user          (default: postgres)

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  echo "Usage: ./scripts/testing/deleteTestUsers.sh [db_service] [db_name] [db_user]" >&2
  exit 0
fi

# shellcheck source=_testUserRoster.sh
source "$SCRIPT_DIR/_testUserRoster.sh"

DB_SERVICE="${1:-postgres}"
DB_NAME="${2:-SkopeoDb}"
DB_USER="${3:-postgres}"

removed=0
failed=0
for entry in "${TEST_USERS[@]}"; do
  IFS='|' read -r email password _name _sex _dob <<<"$entry"
  echo "=== Deleting <$email> ===" >&2
  if "$SCRIPT_DIR/deleteTestUser.sh" "$email" "$password" "$DB_SERVICE" "$DB_NAME" "$DB_USER"; then
    removed=$((removed + 1))
  else
    echo "    FAILED for <$email>" >&2
    failed=$((failed + 1))
  fi
  echo >&2
done

echo "Done. Processed: $removed, failed: $failed (of ${#TEST_USERS[@]})." >&2
[[ "$failed" -eq 0 ]]
