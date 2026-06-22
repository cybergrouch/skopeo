# Contact-Information API

How email and phone contacts are managed over REST. Two principles drive the design:

1. **Append-only** — a contact's `value` is never edited. To change a contact you **disable**
   the current one and **add a new** one. Every email/phone a profile has ever held stays in
   the table (with its `created_at`), giving a full history and a hook to flag values reused
   across profiles.
2. **Verification is privileged** — marking a contact `VERIFIED` is an **ADMINISTRATOR-only**
   action (the manual stand-in while automated OTP is deferred).

## Why append-only

Each user has at most one **active** email and one **active** phone. Instead of mutating a
value in place (which would lose history and let a verified record silently change), we keep
every contact row and toggle `is_active`:

- A user "changes" their email by disabling the old one and adding a new one.
- This holds for administrators too — **nobody edits a value**; everyone disables + adds.
- Because the history is retained, we can later detect and **flag** when a value is reused by
  another profile (a fraud/dedup signal). That flagging is **not built yet** — but disabling a
  contact already *releases* its value (see constraints below) so the semantics are ready.

## The security model

| Capability | Who | What |
|---|---|---|
| **Add / enable / disable** a contact | the user themselves **or** an ADMINISTRATOR | `POST`, `PUT .../state` |
| **Set the verification state** (`status`, `method`, `verifiedAt`, `verifiedBy`) | **ADMINISTRATOR only** | `PUT .../verification` |

- A non-admin attempting verification gets **403**.
- Only an **active** contact can be verified.
- There is **no edit and no hard delete** — disabling is the only way to retire a contact, so
  the audit history is never lost.

## Endpoints

All are nested under the owning user and require a verified Firebase token. "Self-or-admin"
means the caller is the user named in `{userId}` or holds `ADMINISTRATOR`.

| Method | Path | Access | Notes |
|---|---|---|---|
| `GET` | `/api/v1/users/{userId}/contacts` | self-or-admin | full list incl. disabled history |
| `GET` | `/api/v1/users/{userId}/contacts/{id}` | self-or-admin | one contact |
| `POST` | `/api/v1/users/{userId}/contacts` | self-or-admin | add `EMAIL`/`PHONE` (MANUAL, PENDING, active); **409** if an active one of that type exists |
| `PUT` | `/api/v1/users/{userId}/contacts/{id}/state` | self-or-admin | `{ "isActive": false }` to disable / `true` to enable; **409** if enabling collides with another active |
| `PUT` | `/api/v1/users/{userId}/contacts/{id}/verification` | **admin only** | `{ "status": "VERIFIED" }` — verify or revoke |

### Changing a contact (the core flow)

```
PUT  /api/v1/users/{userId}/contacts/{oldId}/state   { "isActive": false }
POST /api/v1/users/{userId}/contacts                 { "type": "EMAIL", "value": "new@example.com" }
```

The old row remains as disabled history; the new row is the active contact.

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

`200` ok · `201` created · `400` bad body / malformed id / verifying a disabled contact ·
`401` missing/invalid token · `403` forbidden (incl. non-admin verification) ·
`404` no such user or contact · `409` conflict (see below).

### Conflicts (`409`)

Both are real database constraints (partial unique indexes):

- **One *active* contact per type** (`uq_contact_active_per_type`) — `POST`ing a second active
  email/phone, or enabling one when another of that type is already active.
- **An *active* verified value is globally unique** (`uq_contact_verified_value`) — verifying a
  value that is actively verified on another account. Disabling that other account's contact
  releases the value.

## Schema (Flyway `V2` + `V3`)

- **`V2`** — adds the `ADMIN_OVERRIDE` verification method and a `verified_by` column (records
  *which* admin verified a contact; audit-trail seed).
- **`V3`** — adds `is_active` / `disabled_at`; changes "one contact per type" to "one **active**
  contact per type"; and scopes the global verified-value uniqueness to **active** contacts so
  disabling releases a value.

## Layering

`routes/ContactRoutes.kt` (thin) → `service/contact/ContactService.kt` (authz, the
active/verify rules, conflict translation) → `repository/ContactRepository.kt` (Exposed). All
mutations funnel through the service so a fuller DB audit trail can be layered in later.
Identity always comes from the verified token, never the request body.

## Deferred

- **Reuse flagging** — surfacing that a value already appears on another profile. The data
  model supports it (history is retained, disabling releases the value); the flag itself and an
  admin "who else uses this value" lookup are future work.
- **Automated OTP verification** (`SMS_OTP`, `WHATSAPP_OTP`, `VIBER_OTP`, `EMAIL_LINK` via a
  CPaaS). When added, those flows set the same `verification_status` through the service; admin
  override remains the manual fallback.
