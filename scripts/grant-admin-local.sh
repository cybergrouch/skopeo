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
#   # Grant ADMINISTRATOR — pass EITHER your Google provider_uid OR your user UUID:
#   ./scripts/grant-admin-local.sh 101769105285479018175
#   ./scripts/grant-admin-local.sh 4cf0530e-9704-40ce-bb40-3365d1741eec
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

# Resolve the identifier (a Google provider_uid or a user UUID) to a single user id.
USER_ID="$(psql -tA -c "
  SELECT u.id FROM users u
  LEFT JOIN user_identities i ON i.user_id = u.id
  WHERE u.id::text = '${IDENTIFIER}' OR i.provider_uid = '${IDENTIFIER}'
  LIMIT 1;
")"

if [[ -z "$USER_ID" ]]; then
  echo "❌ No user found for '${IDENTIFIER}' in '${LOCAL_DB}'. Run with no argument to list users." >&2
  exit 1
fi

echo "🔑 Granting ADMINISTRATOR to user ${USER_ID} in '${LOCAL_DB}'…"
# Idempotent: the partial unique index (user_id, capability) WHERE is_active makes a re-grant a no-op.
psql -c "
  INSERT INTO user_capabilities (user_id, capability, is_active)
  VALUES ('${USER_ID}', 'ADMINISTRATOR', true)
  ON CONFLICT (user_id, capability) WHERE is_active DO NOTHING;
"

echo "✅ Done. Current active capabilities:"
psql -tA -c "SELECT capability FROM user_capabilities WHERE user_id = '${USER_ID}' AND is_active ORDER BY capability;"
echo "The grant is effective on your next API call (capabilities are read per-request)."
