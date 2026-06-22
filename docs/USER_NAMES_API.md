# User-Names API

How a user's names are managed over REST. It follows the same **append-only** model as
[contact-information](CONTACT_INFORMATION_API.md), with two differences: names have **no
verification**, and a user may hold **many** names — one of which is the **primary (display)
name**.

## Why append-only

A name's `value` is never edited. To "change" a name you **disable** the current one and
**add a new** one, so the table keeps the full history of every name a profile has held (each
row keeps its `created_at`). This holds for users *and* administrators — nobody edits a value,
and nothing is ever hard-deleted.

Users legitimately carry several names: a legal first/last name, a preferred name, and one or
more nicknames (commonly used in the Philippines). So — unlike contacts — there is **no
one-per-type rule**: a user can have multiple active names, even of the same type.

## Primary (display) name

Because a user has many names, the UI needs to know which one to show. `isPrimary` marks that
single **display name**:

- The user's **first** active name becomes primary automatically.
- Adding a name with `isPrimary: true` promotes it and **demotes** the previous primary in one
  call (toggling the flag is fine under append-only — only the *value* is immutable).
- At most one **active** primary is allowed (`uq_user_primary_name WHERE is_primary AND
  is_active`); disabling the primary leaves none until another is promoted.

## Endpoints

All are nested under the owning user and require a verified Firebase token. Every operation is
**self-or-ADMINISTRATOR** (names have no admin-only action — there's no verification).

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/v1/users/{userId}/names` | list incl. disabled history |
| `GET` | `/api/v1/users/{userId}/names/{id}` | one name |
| `POST` | `/api/v1/users/{userId}/names` | add `{type, value, isPrimary?}` |
| `PUT` | `/api/v1/users/{userId}/names/{id}/state` | `{ "isActive": false }` to disable / `true` to enable |

No `PATCH` (no editing) and no `DELETE` (disable preserves history).

### Status codes

`200` ok · `201` created · `400` bad body / malformed id · `401` missing/invalid token ·
`403` forbidden · `404` no such user or name · `409` enabling a former primary while another
active primary exists.

## Schema — Flyway `V4`

Adds `is_active` / `disabled_at` to `user_names`, and scopes the single-primary uniqueness to
**active** names (`uq_user_primary_name WHERE is_primary AND is_active`).

## Layering

`routes/NameRoutes.kt` (thin) → `service/name/NameService.kt` (authz, primary/first-name rules,
conflict translation) → `repository/NameRepository.kt` (Exposed), reusing the shared
`RouteSupport` error mapping and the shared `ResourceNotFoundException` / `ConflictException`
base types. Identity always comes from the verified token, never the request body.

## Deferred

A dedicated "designate an existing name as primary" endpoint (`PUT .../names/{id}/primary`) is
a small follow-up; today you set primary at creation (which promotes/demotes as needed).
