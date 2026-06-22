#!/bin/bash
#
# Step 5: confirm the auth gate on `GET /api/v1/users/me` rejects missing/invalid
# tokens (each should return HTTP 401). Needs no token — it tests the negative paths.
#
# Usage: ./scripts/checkAuthRejects.sh [base_url]   (default: http://localhost:8080)

set -uo pipefail

BASE_URL="${1:-http://localhost:8080}"
ENDPOINT="${BASE_URL}/api/v1/users/me"
FAILURES=0

# check <description> <expected_status> [extra curl args...]
check() {
  local description="$1"
  local expected="$2"
  shift 2
  local status
  status="$(curl -s -o /dev/null -w "%{http_code}" "$@" "$ENDPOINT")"
  if [[ "$status" == "$expected" ]]; then
    echo "PASS: $description -> HTTP $status"
  elif [[ "$status" == "000" ]]; then
    echo "FAIL: $description -> no response (is the API running at $BASE_URL?)"
    FAILURES=$((FAILURES + 1))
  else
    echo "FAIL: $description -> expected HTTP $expected, got $status"
    FAILURES=$((FAILURES + 1))
  fi
}

echo "Checking auth gate at $ENDPOINT"
check "no Authorization header" 401
check "malformed bearer token"  401 -H "Authorization: Bearer not-a-real-jwt"
check "wrong auth scheme"       401 -H "Authorization: Basic dXNlcjpwYXNz"

if [[ "$FAILURES" -gt 0 ]]; then
  echo "$FAILURES check(s) failed." >&2
  exit 1
fi
echo "All auth-rejection checks passed."
