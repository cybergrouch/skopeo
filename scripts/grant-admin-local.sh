#!/bin/bash
# SPDX-FileCopyrightText: 2026 Lange Pantoja
# SPDX-License-Identifier: AGPL-3.0-or-later

# Grant ADMINISTRATOR to a user in a LOCAL database — for debugging against a restored production
# copy (see restore-prod-to-local.sh). When you sign in locally against `skopeo_prodcopy`, the backend
# resolves you by your Google identity and checks capabilities in THAT database; if your identity isn't
# an admin there, every admin action (rename/delete a club, etc.) returns 403. This grants it so you
# can exercise admin flows locally.
#
# ⚠️  LOCAL ONLY. This escalates privileges — never point it at a real/shared database. It refuses to
#     touch the normal dev database ('SkopeoDb') by default; the intended target is the throwaway copy.
#
# Usage:
#   # List users (Google provider_uid, id, display name, current capabilities) to find yourself:
#   ./scripts/grant-admin-local.sh
#
#   # Grant ADMINISTRATOR — pass EITHER a Google provider_uid OR a user UUID:
#   ./scripts/grant-admin-local.sh 101769105285479018175
#   ./scripts/grant-admin-local.sh 4cf0530e-9704-40ce-bb40-3365d1741eec
#
#   # ADOPT an existing account as YOUR local login: point its firebase_uid at your local token's uid
#   # and grant ADMINISTRATOR — so signing in locally resolves to that account. Needed because a prod
#   # copy stores prod Firebase uids, which won't match your local (dev-project) login.
#   #   <local-uid>: your local Firebase uid — from the browser: DevTools → Network → any /api/v1
#   #   request → Authorization: Bearer <jwt> → decode at jwt.io → the "user_id"/"sub" claim.
#   ./scripts/grant-admin-local.sh --adopt <local-uid> <target-provider_uid-or-id>
#
# Config via env:
#   LOCAL_DB    target database   (default: skopeo_prodcopy)
#   CONTAINER   postgres container (default: skopeo_db)
#   PGUSER      superuser          (default: postgres)
#
# Capabilities are read per-request, so the grant takes effect on your next API call — no restart.

set -euo pipefail

IDENTIFIER="${1:-}"
LOCAL_DB="${LOCAL_DB:-skopeo_prodcopy}"
CONTAINER="${CONTAINER:-skopeo_db}"
PGUSER="${PGUSER:-postgres}"

# Guard: never escalate privileges in the normal dev database (or anything but a throwaway copy).
if [[ "$LOCAL_DB" == "SkopeoDb" ]]; then
  echo "❌ Refusing to grant admin in the dev database 'SkopeoDb'. Set LOCAL_DB to the restored copy." >&2
  exit 1
fi

psql() { docker exec -i "$CONTAINER" psql -U "$PGUSER" -d "$LOCAL_DB" "$@"; }

# Resolve a Google provider_uid or a user UUID to a single user id (empty when none matches).
resolve_user_id() {
  psql -tA -c "
    SELECT u.id FROM users u
    LEFT JOIN user_identities i ON i.user_id = u.id
    WHERE u.id::text = '$1' OR i.provider_uid = '$1'
    LIMIT 1;
  "
}

# Idempotent grant: the partial unique index (user_id, capability) WHERE is_active makes a re-grant a no-op.
grant_admin() {
  psql -c "
    INSERT INTO user_capabilities (user_id, capability, is_active)
    VALUES ('$1', 'ADMINISTRATOR', true)
    ON CONFLICT (user_id, capability) WHERE is_active DO NOTHING;
  "
}

# --adopt <local-uid> <target>: repoint an existing account's firebase_uid at your local login and grant
# ADMINISTRATOR, so signing in locally resolves to that account (a prod copy holds prod Firebase uids
# that won't match your local dev-project login).
if [[ "$IDENTIFIER" == "--adopt" ]]; then
  LOCAL_UID="${2:-}"
  TARGET="${3:-}"
  if [[ -z "$LOCAL_UID" || -z "$TARGET" ]]; then
    echo "❌ Usage: $0 --adopt <local-firebase-uid> <target-provider_uid-or-id>" >&2
    exit 1
  fi
  USER_ID="$(resolve_user_id "$TARGET")"
  if [[ -z "$USER_ID" ]]; then
    echo "❌ No user found for '${TARGET}' in '${LOCAL_DB}'. Run with no argument to list users." >&2
    exit 1
  fi
  echo "🪄 Repointing ${USER_ID} to your local login (firebase_uid=${LOCAL_UID}) and granting ADMINISTRATOR…"
  psql -c "UPDATE users SET firebase_uid = '${LOCAL_UID}' WHERE id = '${USER_ID}';"
  grant_admin "$USER_ID"
  echo "✅ Done. Sign in locally as that identity and you'll resolve to ${USER_ID} as an admin."
  exit 0
fi

# No identifier → list users so you can find your own Google provider_uid / id.
if [[ -z "$IDENTIFIER" ]]; then
  echo "Users in '${LOCAL_DB}' (pass a provider_uid or id to grant ADMINISTRATOR):"
  psql -c "
    SELECT i.provider_uid,
           u.id,
           n.value AS display_name,
           COALESCE(string_agg(c.capability, ',' ORDER BY c.capability), '') AS capabilities
    FROM users u
    LEFT JOIN user_identities i ON i.user_id = u.id
    LEFT JOIN user_names n ON n.user_id = u.id AND n.name_type = 'DISPLAY' AND n.is_active
    LEFT JOIN user_capabilities c ON c.user_id = u.id AND c.is_active
    GROUP BY i.provider_uid, u.id, n.value
    ORDER BY display_name NULLS LAST;
  "
  exit 0
fi

USER_ID="$(resolve_user_id "$IDENTIFIER")"
if [[ -z "$USER_ID" ]]; then
  echo "❌ No user found for '${IDENTIFIER}' in '${LOCAL_DB}'. Run with no argument to list users." >&2
  exit 1
fi

echo "🔑 Granting ADMINISTRATOR to user ${USER_ID} in '${LOCAL_DB}'…"
grant_admin "$USER_ID"

echo "✅ Done. Current active capabilities:"
psql -tA -c "SELECT capability FROM user_capabilities WHERE user_id = '${USER_ID}' AND is_active ORDER BY capability;"
echo "The grant is effective on your next API call (capabilities are read per-request)."
