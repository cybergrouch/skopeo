# Capabilities (Roles) API

How users' roles are managed over REST. Roles are coarse for now — `PLAYER`, `HOST`,
`CLUB_OWNER`, `ADMINISTRATOR` — and a user may hold several (they overlap). They can be
devolved into fine-grained permissions later.

## Admin-only, end to end

The **entire** capability API requires the caller to be an `ADMINISTRATOR` — including reads.
A user never elevates themselves. A user sees their own roles in their profile
(`GET /api/v1/users/me` includes `capabilities`); the dedicated API below is an admin tool.

## Append-only with a full audit trail

Grants are append-only, like contacts and names:

- A **grant** is an active row stamped with `granted_by` (the acting admin) and `granted_at`.
- A **revoke** flips the active row inactive, stamping `revoked_by` / `revoked_at`.
- **Re-granting** after a revoke inserts a fresh active row — so the table holds the complete
  history of every privilege change (who granted/revoked which role, and when).

A user holds at most one **active** grant per capability (`uq_user_capability_active`).

## Endpoints

Nested under the user; a capability is addressed by its **name** (a fixed enum), not a UUID.

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/v1/users/{userId}/capabilities` | list a user's grants (incl. revoked history) |
| `POST` | `/api/v1/users/{userId}/capabilities` | grant `{capability}`; idempotent (201 new / 200 already-held) |
| `DELETE` | `/api/v1/users/{userId}/capabilities/{capability}` | revoke |

All require an `ADMINISTRATOR` token.

## Guardrails

- **`PLAYER` is non-revocable** — every user keeps the baseline role.
- **The last `ADMINISTRATOR` can't be revoked** — prevents a system-wide lockout. (Dropping
  to zero admins is only reachable by revoking the sole — necessarily one's own — admin grant,
  so this check precedes the self-check.)
- **No self-revoke of your own admin** — an `ADMINISTRATOR` can't drop their own admin role
  while others remain (avoids accidental self-demotion).

## Status codes

`200` already-held / `201` granted / `204` revoked · `400` invalid capability · `401`
missing/invalid token · `403` caller isn't an admin, or self-revoke of own admin · `404`
no such user, or the capability isn't currently held · `409` revoking `PLAYER` or the last
`ADMINISTRATOR`.

## Bootstrapping the first administrator

Because granting is admin-only, the **first** `ADMINISTRATOR` can't be created through the API.
Seed it once, out-of-band, against the target user's id — e.g.:

```sql
INSERT INTO user_capabilities (user_id, capability, granted_by)
VALUES ('<user-uuid>', 'ADMINISTRATOR', '<user-uuid>');
```

After that, administrators grant the role to each other through the API.

## Schema — Flyway `V5`

Adds `is_active` / `revoked_at` / `revoked_by` to `user_capabilities`, and replaces the
`unique(user_id, capability)` constraint with a partial unique index over **active** grants
(`uq_user_capability_active`). `granted_by` / `granted_at` already existed.

## Layering

`routes/CapabilityRoutes.kt` (thin) → `service/capability/CapabilityService.kt` (admin-only
authz, guardrails, idempotent grant) → `repository/CapabilityRepository.kt` (Exposed,
append-only). `User.capabilities` (used for authz across the app) now reflects only **active**
grants.
