# User-Names API

How a user's names are managed over REST. It follows the same **append-only** model as
[contact-information](CONTACT_INFORMATION_API.md): a name's `value` is never edited — to
change a name you **disable** the current one and **add** a new one, so the table keeps the
full history of every name a profile has held.

Two things differ from contacts: names have **no verification**, and a user may hold **many**
names — including several of the same type (legal first/last, preferred, nicknames).

## The display name is a name type

There is no `is_primary` flag. The name shown in the UI is simply the single **active name of
type `DISPLAY`**. This keeps "which name to show" on the same axis as the name itself, and
gives a clean invariant: **every user has exactly one active `DISPLAY` name.**

- **Created at sign-up** from the manual input (`displayName` in the create-user request) or,
  failing that, the verified token's name (Google/Facebook). A user can't be provisioned
  without one.
- **Changed** by posting a new `DISPLAY` name: the service atomically disables the current
  display name and activates the new one (the old is kept as disabled history).
- **Can't be disabled directly** — you replace it. The `/state` endpoint refuses to disable a
  `DISPLAY` name (and re-enabling a former display name conflicts with the current one).

Enforcement of "exactly one": the **at-most-one** half is a partial unique index
(`uq_user_display_name WHERE name_type='DISPLAY' AND is_active`); the **at-least-one** half is
guaranteed by provisioning and protected by the API (you can't disable your only display name).

## Endpoints

All are nested under the owning user and require a verified Firebase token. Every operation is
**self-or-ADMINISTRATOR**.

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/v1/users/{userId}/names` | list incl. disabled history |
| `GET` | `/api/v1/users/{userId}/names/{id}` | one name |
| `POST` | `/api/v1/users/{userId}/names` | add `{type, value}`; a `DISPLAY` type replaces the display name |
| `PUT` | `/api/v1/users/{userId}/names/{id}/state` | `{ "isActive": false }` to disable / `true` to enable |

No `PATCH` (no editing) and no `DELETE` (disable preserves history).

### Status codes

`200` ok · `201` created · `400` bad body / malformed id / trying to disable the display name ·
`401` missing/invalid token · `403` forbidden · `404` no such user or name · `409` re-enabling
a former display name while the current one is active.

## Schema — Flyway `V4`

Adds `is_active` / `disabled_at` to `user_names`; **drops `is_primary`** and its index; adds a
`DISPLAY` value to the `name_type` CHECK; and adds `uq_user_display_name` (at most one active
`DISPLAY` per user).

## Layering

`routes/NameRoutes.kt` (thin) → `service/name/NameService.kt` (authz, display-name rules,
conflict translation) → `repository/NameRepository.kt` (Exposed, where adding a `DISPLAY` name
disables the previous one atomically). Reuses the shared `RouteSupport` error mapping and the
shared `ResourceNotFoundException` / `ConflictException` base types. Identity always comes from
the verified token, never the request body.

## Note on sign-up

`POST /api/v1/users` now takes a single `displayName` (string) instead of a list of names —
structured names (FIRST/LAST/nicknames) are added afterwards via this API. The display name is
the only name required to create an account.
