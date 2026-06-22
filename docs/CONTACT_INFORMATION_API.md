# Contact-Information API

How email and phone contacts are managed over REST, and how an **administrator marks
them verified** — the manual stand-in while automated OTP verification is deferred.

## Why this exists

Each user has at most one email and one phone (see [`database-schema.md`](database-schema.md)).
A contact carries a `verification_status` (`PENDING` / `VERIFIED` / `FAILED`). Automated
verification (SMS/WhatsApp/Viber OTP, email links) is **not built yet**; until it is, an
**ADMINISTRATOR** marks a contact verified through the API. Contacts created via OAuth
sign-in (Google/Facebook) are already `VERIFIED` at provisioning time; everything added
later starts `PENDING`.

## The security model (the rule that shapes the design)

A contact's verification state must **never** be settable by the owning user — otherwise
anyone could mark their own phone "verified". So the surface is split in two:

| Capability | Who | What |
|---|---|---|
| **Edit the address** (`value`, `isPrimary`) | the user themselves **or** an ADMINISTRATOR | create / update / delete a contact |
| **Set the verification state** (`status`, `method`, `verifiedAt`, `verifiedBy`) | **ADMINISTRATOR only** | the dedicated `/verification` endpoint |

Two consequences enforced by the service:

- **Editing the address resets verification.** Changing a contact's `value` sets it back
  to `PENDING` and clears `method` / `verifiedAt` / `verifiedBy` — a previously verified
  address can't stay verified once it's changed.
- The generic update endpoint **cannot** touch verification fields; only the
  `/verification` endpoint can, and only for admins.

## Endpoints

All are nested under the owning user and require a verified Firebase token
(`authenticate(FIREBASE_AUTH)`). "Self-or-admin" means the caller is the user named in
`{userId}` or holds the `ADMINISTRATOR` capability.

| Method | Path | Access | Notes |
|---|---|---|---|
| `GET` | `/api/v1/users/{userId}/contacts` | self-or-admin | list the user's contacts |
| `GET` | `/api/v1/users/{userId}/contacts/{id}` | self-or-admin | one contact |
| `POST` | `/api/v1/users/{userId}/contacts` | self-or-admin | add `EMAIL` or `PHONE`; **409** if that type already exists |
| `PATCH` | `/api/v1/users/{userId}/contacts/{id}` | self-or-admin | change `value`/`isPrimary`; **resets status → PENDING** |
| `DELETE` | `/api/v1/users/{userId}/contacts/{id}` | self-or-admin | remove |
| `PUT` | `/api/v1/users/{userId}/contacts/{id}/verification` | **admin only** | set `{status, method}` — verify or revoke |

### Verifying a contact

```
PUT /api/v1/users/{userId}/contacts/{id}/verification
Authorization: Bearer <admin Firebase token>

{ "status": "VERIFIED" }          # method defaults to ADMIN_OVERRIDE
```

`status` accepts `VERIFIED`, `PENDING`, or `FAILED`. On `VERIFIED` the service stamps
`verifiedAt`, the verifying admin's id in `verifiedBy`, and `method` (default
`ADMIN_OVERRIDE`); any other status clears those audit fields.

### Status codes

`200` ok · `201` created · `204` deleted · `400` bad body / malformed id ·
`401` missing/invalid token · `403` forbidden (incl. a non-admin attempting verification) ·
`404` no such user or contact · `409` conflict (see below).

### Conflicts (`409`)

Both are real database constraints:

- **One contact per type** (`uq_contact_one_per_type`) — `POST`ing a second email or
  second phone for the same user.
- **A verified value is globally unique** (`uq_contact_verified_value`) — verifying a
  value that is already verified on another account.

## Schema change — Flyway `V2`

`V2__contact_verification.sql`:

1. Adds `ADMIN_OVERRIDE` to the `chk_contact_method` CHECK constraint and to the
   `VerificationMethod` enum, recording that a verification was a manual admin action.
2. Adds `verified_by UUID REFERENCES users(id)` so we capture **which administrator**
   verified a contact — the start of an audit trail for privileged contact changes.

## Layering

`routes/ContactRoutes.kt` (thin) → `service/contact/ContactService.kt` (authz, status-reset
rule, conflict translation) → `repository/ContactRepository.kt` (Exposed). All mutations
funnel through the service so a fuller DB audit trail can be layered in later without
touching the transport layer. Identity always comes from the verified token, never the
request body.

## Deferred

Automated OTP verification (`SMS_OTP`, `WHATSAPP_OTP`, `VIBER_OTP`, `EMAIL_LINK` flows via
a CPaaS provider) is intentionally out of scope here. When added, those flows will set the
same `verification_status` through the service — admin override remains as the manual
fallback.
