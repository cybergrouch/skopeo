#!/bin/bash
# SPDX-FileCopyrightText: 2026 Lange Pantoja
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# End-to-end test + living documentation for third-party (partner) API-key access
# (#225/#596/#597/#598/#603, issue #695).
#
# It provisions a partner client + key (admin bootstrap), then exercises the partner
# endpoints and prints the exact METHOD / URL / HEADERS / BODY of every request so the
# output doubles as documentation. It proves two things in particular:
#   1. A third party authenticates with the `X-Api-Key` header ALONE — no Firebase bearer.
#   2. User-oriented endpoints (the various `/me`) are NOT reachable with an API key.
#
# ── The credential ─────────────────────────────────────────────────────────────────────
#   Header:   X-Api-Key: <plaintext>
#   Plaintext is self-identifying by prefix — `skopeo_test_…` (TEST) or `skopeo_live_…` (LIVE).
#   Resolves to a client identity + a set of Capability SCOPES (typically NOT PLAYER).
#   Auth outcomes:  missing/malformed/unknown → 401;  revoked/expired/suspended → 403.
#
# ── Endpoint groups ────────────────────────────────────────────────────────────────────
#   Management (ADMINISTRATOR Firebase token):  POST/GET /api/v1/api-clients,
#                                                POST/DELETE .../keys, PUT .../rate-limit
#   Machine-to-machine (API key only):          GET /api/v1/client/me, /api/v1/client/players
#   Delegated (API key + user Firebase token):  GET /api/v1/client/me/capabilities
#
# ── Usage ──────────────────────────────────────────────────────────────────────────────
#   ADMIN_TOKEN=<firebase-id-token-of-an-ADMINISTRATOR> ./scripts/test-partner-api.sh
#
#   The bootstrap (create client + issue key) is ADMINISTRATOR-only, so you must supply a
#   Firebase ID token for an admin user. Two ways:
#     • Paste one:  grab it from the browser (DevTools → Network → any /api/v1 request →
#                   Authorization: Bearer <jwt>) and export ADMIN_TOKEN=<jwt>.
#     • Mint one:   set WEB_API_KEY + ADMIN_EMAIL + ADMIN_PASSWORD and this script mints it
#                   via the Firebase REST API (same as createFirebaseToken.sh).
#   Make a local user an admin with:  ./scripts/grant-admin-local.sh
#
# ── Config (env vars) ──────────────────────────────────────────────────────────────────
#   BASE_URL        API base URL                         (default: http://localhost:8080)
#   ADMIN_TOKEN     Firebase ID token of an ADMINISTRATOR (required unless minting, below)
#   WEB_API_KEY     Firebase Web API Key ("AIza…")        — to mint tokens instead of pasting
#   ADMIN_EMAIL / ADMIN_PASSWORD   admin email/password login to mint ADMIN_TOKEN
#   USER_TOKEN      Firebase ID token of a normal user    — enables the delegated 200 test
#   USER_EMAIL / USER_PASSWORD     user email/password login to mint USER_TOKEN
#   KEEP=1          skip cleanup (leave the issued keys active)
#
# Requires: curl, jq, and a running server (./gradlew run  or  docker-compose up).

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ADMIN_TOKEN="${ADMIN_TOKEN:-}"
USER_TOKEN="${USER_TOKEN:-}"
WEB_API_KEY="${WEB_API_KEY:-}"
ADMIN_EMAIL="${ADMIN_EMAIL:-}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-}"
USER_EMAIL="${USER_EMAIL:-}"
USER_PASSWORD="${USER_PASSWORD:-}"
KEEP="${KEEP:-}"

PASS=0
FAIL=0
RESP_BODY="$(mktemp)"
RESP_STATUS=""
CLIENT_ID=""
API_KEY=""       # RESEARCHER-scoped key (plaintext)
KEY_ID=""
NOSCOPE_KEY=""   # key with no scopes (for the least-privilege 403 test)
NOSCOPE_KEY_ID=""
trap 'rm -f "$RESP_BODY"' EXIT

# ── Small helpers ────────────────────────────────────────────────────────────────────────

die() { echo "❌ $*" >&2; exit 1; }
section() { echo; echo "━━━ $* ━━━"; }
mask() { local s="$1"; if [[ ${#s} -le 18 ]]; then echo "$s"; else echo "${s:0:18}…"; fi; }

command -v curl >/dev/null 2>&1 || die "curl is required."
command -v jq   >/dev/null 2>&1 || die "jq is required (brew install jq)."

# Mint a Firebase ID token via the Email/Password REST API (see createFirebaseToken.sh).
mint_token() {
  local email="$1" password="$2" resp token
  [[ -n "$WEB_API_KEY" ]] || die "WEB_API_KEY is required to mint a token for $email."
  resp="$(curl -s -X POST \
    "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${WEB_API_KEY}" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"${email}\",\"password\":\"${password}\",\"returnSecureToken\":true}" || true)"
  token="$(echo "$resp" | jq -r '.idToken // empty')"
  [[ -n "$token" ]] || { echo "$resp" | jq . >&2 2>/dev/null || echo "$resp" >&2; die "Failed to mint a token for $email."; }
  echo "$token"
}

# http_req METHOD PATH [--key KEY] [--bearer TOKEN] [--data JSON]
# Prints a curl-equivalent (secrets masked), executes, sets RESP_STATUS and writes the body to RESP_BODY.
http_req() {
  local method="$1" path="$2"; shift 2
  local key="" bearer="" data=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --key)    key="$2";    shift 2 ;;
      --bearer) bearer="$2"; shift 2 ;;
      --data)   data="$2";   shift 2 ;;
      *) die "http_req: unknown arg '$1'" ;;
    esac
  done
  local url="${BASE_URL}${path}"
  local -a args=(-s -o "$RESP_BODY" -w '%{http_code}' -X "$method")
  local shown="curl -s -X $method"
  if [[ -n "$key" ]];    then args+=(-H "X-Api-Key: $key");                 shown+=" -H 'X-Api-Key: $(mask "$key")'"; fi
  if [[ -n "$bearer" ]]; then args+=(-H "Authorization: Bearer $bearer");   shown+=" -H 'Authorization: Bearer <token>'"; fi
  if [[ -n "$data" ]];   then args+=(-H "Content-Type: application/json" -d "$data"); shown+=" -H 'Content-Type: application/json' -d '$data'"; fi
  echo "   → $method $path"
  echo "     $shown '$url'"
  # `|| true`: on a transport error curl writes 000 and exits non-zero — let `expect` report it
  # rather than letting `set -e` abort the whole run.
  RESP_STATUS="$(curl "${args[@]}" "$url" || true)"
}

# expect EXPECTED_STATUS LABEL   — assert RESP_STATUS, track pass/fail, show body on mismatch.
expect() {
  local expected="$1" label="$2"
  if [[ "$RESP_STATUS" == "$expected" ]]; then
    echo "     ✅ $label — HTTP $RESP_STATUS"
    PASS=$((PASS + 1))
  else
    echo "     ❌ $label — HTTP $RESP_STATUS (expected $expected)"
    echo "        body: $(head -c 500 "$RESP_BODY")"
    FAIL=$((FAIL + 1))
  fi
}

# ── Preflight ────────────────────────────────────────────────────────────────────────────

echo "🎾 Skopeo partner API-key test  —  base URL: $BASE_URL"

if ! curl -s -o /dev/null -w '%{http_code}' "${BASE_URL}/health" | grep -q '^2'; then
  die "Server not reachable at ${BASE_URL}/health. Start it with ./gradlew run (or docker-compose up)."
fi

if [[ -z "$ADMIN_TOKEN" ]]; then
  [[ -n "$WEB_API_KEY" && -n "$ADMIN_EMAIL" && -n "$ADMIN_PASSWORD" ]] \
    || die "No ADMIN_TOKEN. Provide one, or set WEB_API_KEY + ADMIN_EMAIL + ADMIN_PASSWORD to mint it. (The bootstrap is ADMINISTRATOR-only.)"
  echo "   Minting ADMIN_TOKEN for $ADMIN_EMAIL…"
  ADMIN_TOKEN="$(mint_token "$ADMIN_EMAIL" "$ADMIN_PASSWORD")"
fi
if [[ -z "$USER_TOKEN" && -n "$WEB_API_KEY" && -n "$USER_EMAIL" && -n "$USER_PASSWORD" ]]; then
  echo "   Minting USER_TOKEN for $USER_EMAIL…"
  USER_TOKEN="$(mint_token "$USER_EMAIL" "$USER_PASSWORD")"
fi

# ── 0. Bootstrap: create a partner client + issue keys (ADMINISTRATOR, Firebase-authenticated) ──

section "0. Bootstrap (admin) — create client + issue keys"

CLIENT_NAME="partner-api-test-$(date +%Y%m%d-%H%M%S)"
http_req POST /api/v1/api-clients --bearer "$ADMIN_TOKEN" --data "{\"name\":\"${CLIENT_NAME}\"}"
expect 201 "create client '$CLIENT_NAME'"
CLIENT_ID="$(jq -r '.id // empty' "$RESP_BODY")"
[[ -n "$CLIENT_ID" ]] || die "Could not create a client — is your ADMIN_TOKEN an ADMINISTRATOR in this DB? (see grant-admin-local.sh). Response: $(cat "$RESP_BODY")"
echo "     client id: $CLIENT_ID"

# A RESEARCHER-scoped TEST key — the plaintext is returned ONCE, in `.apiKey`.
http_req POST "/api/v1/api-clients/${CLIENT_ID}/keys" --bearer "$ADMIN_TOKEN" \
  --data '{"scopes":["RESEARCHER"],"environment":"TEST","expiresInDays":30}'
expect 201 "issue RESEARCHER-scoped TEST key"
API_KEY="$(jq -r '.apiKey // empty' "$RESP_BODY")"
KEY_ID="$(jq -r '.key.id // empty' "$RESP_BODY")"
[[ -n "$API_KEY" ]] || die "No plaintext key returned. Response: $(cat "$RESP_BODY")"
echo "     issued key (prefix): $(mask "$API_KEY")   id: $KEY_ID"

# A second key with NO scopes — to prove least-privilege (RESEARCHER gate) below.
http_req POST "/api/v1/api-clients/${CLIENT_ID}/keys" --bearer "$ADMIN_TOKEN" \
  --data '{"scopes":[],"environment":"TEST"}'
expect 201 "issue unscoped TEST key"
NOSCOPE_KEY="$(jq -r '.apiKey // empty' "$RESP_BODY")"
NOSCOPE_KEY_ID="$(jq -r '.key.id // empty' "$RESP_BODY")"

# ── 1. Machine-to-machine — API key ALONE, no Firebase bearer ──

section "1. Machine-to-machine (API key only, NO bearer)"

http_req GET /api/v1/client/me --key "$API_KEY"
expect 200 "GET /client/me resolves the key (no Firebase token needed)"
echo "     identity: $(jq -c '{clientId, scopes}' "$RESP_BODY" 2>/dev/null || cat "$RESP_BODY")"

http_req GET /api/v1/client/players --key "$API_KEY"
expect 200 "GET /client/players with RESEARCHER scope"
echo "     players returned: $(jq 'length' "$RESP_BODY" 2>/dev/null || echo '?')"

# ── 2. Machine-to-machine negative cases ──

section "2. Negative auth cases (401 vs 403)"

http_req GET /api/v1/client/me
expect 401 "no X-Api-Key → 401 (Missing)"

http_req GET /api/v1/client/me --key "skopeo_test_deadbeefdeadbeefdeadbeef"
expect 401 "unknown/garbage key → 401 (Invalid)"

http_req GET /api/v1/client/players --key "$NOSCOPE_KEY"
expect 403 "key WITHOUT RESEARCHER scope → 403 (least-privilege gate)"

# ── 3. User-oriented `/me` endpoints must REJECT an API key ──
# These sit behind Firebase auth (authenticate(FIREBASE_AUTH)); an X-Api-Key is not a bearer,
# so a partner presenting only a key gets 401. This is the "as a 3rd party you are NOT a user" check.

section "3. User `/me` endpoints reject an API-key-only request (→ 401)"

for path in /api/v1/users/me /api/v1/standings/me /api/v1/rating-requests/me /api/v1/users/me/theme; do
  http_req GET "$path" --key "$API_KEY"
  expect 401 "$path with API key only → 401 (Firebase-only endpoint)"
done

# ── 4. Delegated — needs BOTH an API key AND a user Firebase token ──

section "4. Delegated /client/me/capabilities (API key + user token)"

http_req GET /api/v1/client/me/capabilities --key "$API_KEY"
expect 401 "delegated with API key only (no user token) → 401"

if [[ -n "$USER_TOKEN" ]]; then
  http_req GET /api/v1/client/me/capabilities --key "$API_KEY" --bearer "$USER_TOKEN"
  expect 200 "delegated with API key + user token → 200"
  echo "     effective capabilities (scopes ∩ user): $(jq -c '.capabilities' "$RESP_BODY" 2>/dev/null || cat "$RESP_BODY")"
else
  echo "     ⏭  skipped the 200 case — set USER_TOKEN (or USER_EMAIL/USER_PASSWORD + WEB_API_KEY) to run it."
fi

# ── 5. Revoked-key behavior ──

section "5. Revoked key → 403"

http_req DELETE "/api/v1/api-clients/${CLIENT_ID}/keys/${KEY_ID}" --bearer "$ADMIN_TOKEN"
expect 204 "revoke the RESEARCHER key (admin)"

http_req GET /api/v1/client/me --key "$API_KEY"
expect 403 "revoked key → 403 (Forbidden, not 401)"

# ── Cleanup ──

section "Cleanup"
if [[ -n "$KEEP" ]]; then
  echo "   KEEP set — leaving keys active. Client '$CLIENT_NAME' ($CLIENT_ID) and its keys remain."
else
  if [[ -n "$NOSCOPE_KEY_ID" ]]; then
    http_req DELETE "/api/v1/api-clients/${CLIENT_ID}/keys/${NOSCOPE_KEY_ID}" --bearer "$ADMIN_TOKEN"
    expect 204 "revoke the unscoped key (admin)"
  fi
  echo "   Note: both issued keys are revoked. The test client '$CLIENT_NAME' ($CLIENT_ID) remains"
  echo "   (there is no delete-client endpoint); it is harmless with no active keys."
fi

# ── Summary ──

section "Summary"
echo "   ✅ passed: $PASS    ❌ failed: $FAIL"
[[ "$FAIL" -eq 0 ]] || exit 1
echo "   All partner API-key checks passed."
