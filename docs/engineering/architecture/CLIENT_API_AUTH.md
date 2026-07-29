# Client / Application API Authentication (design)

How Skopeo will identify the **application** calling the API — distinct from the **end user** —
so the backend can safely open to third-party integrations. This is a **design-first** document
for [#225](https://github.com/cybergrouch/skopeo/issues/225); it records the agreed scheme and a
phased plan, not shipped behaviour. See [AUTHENTICATION.md](./AUTHENTICATION.md) for the existing
end-user (Firebase) model this layers on top of.

## TL;DR

- Today the API only knows **who the user is** (a Firebase ID token) and **what they can do**
  (the in-house `Capability` roles). It has **no notion of which client/application is calling**.
- That's fine while the only client is our own web UI. It becomes a problem the moment we let
  other systems call the API — we can't identify, scope, throttle, or revoke a third-party caller.
- **Agreed scheme:** add a partner **client-identity layer using hashed static API keys**, with
  per-key **scopes drawn from the existing `Capability` set** and **per-client rate limits**.
  Designed so **OAuth2 client-credentials** can layer on later; **mTLS** is out of scope.
- **The first-party web UI does not change** — it stays Firebase-ID-token + CORS. A key shipped
  to a browser is public and therefore not a credential; it would add plumbing but no security.
- The mental model becomes: **API key answers "which application, and is it allowed to call this
  endpoint?"; the Firebase token answers "which user, and may they touch this record?"** Where
  both are present, access is the **intersection** of the two.

## Why CORS is not the answer

`configureCORS()` (`Application.kt`, env `WEB_ORIGINS`) restricts which **browser** origins may
call the API. It is a browser-enforced convenience, **not a security boundary**: `curl`, servers,
and scripts ignore CORS entirely and can forge the `Origin` header. It cannot identify, scope, or
throttle a non-browser client. Keep it as hygiene for legitimate browsers; do not treat it as
access control.

## Decisions (and why)

| Decision | Choice | Why |
|---|---|---|
| Partner credential scheme | **Hashed static API keys** | Simplest revocable per-partner credential; what Stripe/GitHub/Google started with. Design so OAuth2 client-credentials can layer on later. |
| First-party SPA | **Unchanged (Firebase + CORS)** | A key shipped to the browser is public → identification only, never auth. No security gain for real plumbing cost. |
| Scope model | **Reuse the `Capability` enum** | Consistent with today's authorization; avoids a premature per-endpoint scope taxonomy. A key's scope must be ⊆ the capabilities it represents. |
| Call model | **Build for both M2M and delegated** | Support machine-to-machine (key alone) *and* acting-on-behalf-of-a-user (key + user token). Wire specific endpoints only as real partner use-cases arrive. |
| Rate limiting | **Per-client, Ktor `RateLimit` plugin** | Protect the backend and Cloud SQL from a noisy/abusive client; key the limit on the client id, not IP. |
| Transport | **HTTPS-only** | Already true — Cloud Run terminates TLS. Bearer-style keys require it. |

**Non-goals (for now):** OAuth2 client-credentials, mTLS, a per-endpoint scope taxonomy, and any
change to how end users authenticate.

## Data model

New tables (added via a **new Flyway migration**, never by editing an applied one):

- **`api_clients`** — a partner application: `id`, `name`, `status` (active/suspended), optional
  owning user, timestamps.
- **`api_keys`** — a credential for a client: `id`, `client_id` (FK), `key_prefix` (the
  non-secret leading segment, stored for display/identification), `key_hash` (**SHA-256**, unique
  index — lookups are by hash), `scopes` (a subset of `Capability`), `status` (active/revoked),
  `expires_at?`, `last_used_at?`, timestamps. **≥2 active keys per client** are allowed so a
  partner can rotate with overlap.

Key **format**: `skopeo_live_<high-entropy-random><checksum>` (`skopeo_test_…` outside prod). The
prefix identifies the product + environment; the checksum lets secret scanners validate offline.

Key **handling**: the raw secret is shown **exactly once** at creation; only the SHA-256 hash is
persisted. A fast hash is correct here — the security comes from the key's entropy, and a slow
adaptive hash (bcrypt/Argon2) would only add per-request latency at API scale. Keys are **never
logged**.

> Why SHA-256 and not bcrypt: bcrypt/Argon2 exist to make *low-entropy human passwords* expensive
> to brute-force. A 256-bit random key isn't brute-forceable regardless of hash speed, so the only
> effect of a slow hash would be latency on every authenticated request.

## Request flow

Client identity is resolved as a concern **separate from** the JWT auth provider — a small call
interceptor, not a competing Ktor `Authentication` provider (which would force awkward
"require both providers" composition):

```
Partner request ── "X-Api-Key: skopeo_live_…"  (+ optional "Authorization: Bearer <Firebase JWT>")
  1. Interceptor: SHA-256(key) → look up api_keys by hash
  2. Reject unknown / revoked / expired            → 401/403 (never log the key)
  3. Attach ClientPrincipal{ clientId, scopes } to the call
  4. (delegated calls) the existing Firebase auth still verifies the user token
  5. Authorize (see below); update last_used_at
```

- **Machine-to-machine endpoints** require a `ClientPrincipal` and no user token.
- **Delegated endpoints** require **both** a `ClientPrincipal` and a `JWTPrincipal`.

## Authorization

The effective permission is:

- **Delegated:** `intersection(client scopes, user capabilities)` — the app may only do what it is
  scoped for *and* what the acting user is allowed to do.
- **Machine-to-machine:** the client scopes alone.

This is the point of most friction with today's code: there is **no central authorization
middleware** — the role gate is a private `requireAdmin`/`requireCapability` re-implemented across
~16 services, and identity is 1:1 with a human `users` row. This design introduces:

- a **`ClientPrincipal`** principal type (a non-human caller has no `users` row), and
- a **shared authorization gate** that composes client-scope checks with the existing capability
  checks, rather than adding a scope check to every service by hand.

Per-object ownership checks (OWASP BOLA) are unchanged and still run on every record access —
scopes are function-level authorization only.

## Rate limiting

Install the Ktor `RateLimit` plugin. Define a named `"partner"` limiter (token bucket) keyed by
**client id** (`requestKey { clientId }` — independent buckets per client); return **429** with
`Retry-After`. Start with one sensible default tier; per-key overrides can come later. This
directly addresses OWASP API4 (unrestricted resource consumption).

## Lifecycle & administration

ADMINISTRATOR-only, mirroring existing admin patterns (an admin API + an Admin-tab surface):

- **Issue** a key for a client → returns the raw secret **once**, stores the hash.
- **List** keys (prefix + metadata + `last_used_at`; never the secret).
- **Rotate** — issue a second key, let the partner cut over, then revoke the old one.
- **Revoke** — flip status; subsequent calls with that key are rejected immediately.

## Audit

Add a first-class nullable **`actor_client_id`** column to `audit_log` (via migration) so "which
application acted" is queryable alongside the existing `actorUserId`. For delegated calls both are
recorded; for M2M only the client. Keys themselves are never written to the audit log.

## OpenAPI & docs

- Add an `apiKey` security scheme (header `X-Api-Key`) to `documentation.yaml`, applied to the
  endpoints that accept client auth; keep `OpenAPIIntegrationTest` green.
- Before external exposure, reconsider the currently-public infrastructure endpoints (`/metrics`,
  `/swagger`, `/openapi.yaml`).

## Phased plan

Tracked as sub-issues of [#225](https://github.com/cybergrouch/skopeo/issues/225):

1. **Client identity foundation** — `api_clients` + `api_keys` tables + migration, key
   format/hashing, the resolver interceptor + `ClientPrincipal`, admin issue/list/revoke, and
   rejection of unknown/revoked/expired keys. (Machine-to-machine *identity* works after this.)
2. **Scopes & authorization** — per-key scopes from `Capability`, the shared authorization gate,
   and the intersection rule for delegated calls.
3. **Per-client rate limiting** — the Ktor `RateLimit` plugin keyed by client id, 429 +
   `Retry-After`.
4. **Audit, docs & hardening** — `actor_client_id` in the audit log, OpenAPI security scheme +
   docs, and gating the public infra endpoints.

**Later (not in #225):** OAuth2 client-credentials — the scope names and per-partner identity from
this design carry over, so it's an additive migration when the number of partners justifies a
standards-based, short-lived-token flow.

## References

- [#225](https://github.com/cybergrouch/skopeo/issues/225) — the tracking issue.
- [AUTHENTICATION.md](./AUTHENTICATION.md) — the existing end-user (Firebase) model.
- OWASP API Security Top 10 (2023): API1 BOLA, API2 Broken Authentication, API4 Unrestricted
  Resource Consumption, API5 Broken Function-Level Authorization.
- RFC 6749 §2.1 (public clients can't hold secrets), RFC 6750 (bearer tokens require TLS),
  RFC 6749 §4.4 (client-credentials, for the later phase).
- Ktor: RateLimit, JWT, and authentication plugin docs.
- Prior art: Stripe restricted API keys, GitHub fine-grained tokens, Google API keys vs OAuth
  clients — all start with scoped, revocable, hashed keys and keep user auth separate.
