#!/bin/bash
#
# Batch-create the test users defined in _testUserRoster.sh by delegating each one to
# createTestUser.sh (Firebase account + Skopeo DB profile). The roster includes several
# near-duplicate names on purpose, for testing name resolution / search.
#
# The Firebase Web API key is resolved by createTestUser.sh itself (web/.env.local or
# the WEB_API_KEY env var). Pairs with deleteTestUsers.sh.
#
# Usage:
#   ./scripts/testing/createTestUsers.sh [base_url]
#
#   base_url   optional  API base URL (default: http://localhost:8080)

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  echo "Usage: ./scripts/testing/createTestUsers.sh [base_url]   (default base_url: http://localhost:8080)" >&2
  exit 0
fi

# shellcheck source=_testUserRoster.sh
source "$SCRIPT_DIR/_testUserRoster.sh"

BASE_URL="${1:-http://localhost:8080}"

created=0
failed=0
for entry in "${TEST_USERS[@]}"; do
  IFS='|' read -r email password name sex dob <<<"$entry"
  echo "=== Creating '$name' <$email> ===" >&2
  if "$SCRIPT_DIR/createTestUser.sh" "$email" "$password" "$name" "$sex" "$dob" "$BASE_URL"; then
    created=$((created + 1))
  else
    echo "    FAILED for <$email>" >&2
    failed=$((failed + 1))
  fi
  echo >&2
done

echo "Done. Created/updated: $created, failed: $failed (of ${#TEST_USERS[@]})." >&2
[[ "$failed" -eq 0 ]]
