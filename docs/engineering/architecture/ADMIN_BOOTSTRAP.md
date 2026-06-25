# Administrator Bootstrap: verified-email allowlist

**Status:** Accepted (design) — implementation pending. Tracked by
[issue #45](https://github.com/cybergrouch/skopeo/issues/45).

**Date:** 2026-06-25

This is a decision record for how the **first** (and ongoing break-glass) administrator is
established in production. It captures the chosen approach and — importantly — the security
constraints that make it safe.

---

## Context

Authorization is capability-based (`PLAYER`, `HOST`, `CLUB_OWNER`, `ADMINISTRATOR`). New sign-ups
get only `PLAYER`, and the capability-grant API is **ADMINISTRATOR-only**
(`service/capability/CapabilityService.kt`). Nothing seeds an admin in the schema. That creates a
chicken-and-egg in a fresh production database: **no one can grant the first `ADMINISTRATOR`
through the app**, because granting requires an existing administrator.

Options were weighed in #45 (manual post-deploy SQL; env-configured startup promotion;
admin-provisioned placeholder + claim-on-login; one-time secured bootstrap endpoint).

## Decision

Promote a sign-up to `ADMINISTRATOR` when its **verified** email is on an **allowlist supplied via
the environment** (sourced from Secret Manager). Concretely:

> On provisioning (and on authenticated login), if `token.emailVerified == true` **and** the
> normalized email is in `ADMIN_EMAILS`, grant `ADMINISTRATOR` in addition to `PLAYER`, and record
> the grant in the capability audit trail.

This serves both the **bootstrap** ("how does the first admin exist") and a **break-glass** path
(re-add yourself if locked out), without hardcoding identities in the API source.

### Non-negotiable: only honor *verified* emails

This is the security crux. For **Google/Facebook**, Firebase supplies a provider-verified email
(`token.emailVerified == true`) — trustworthy. For **manual email/password**, the email is **not
verified at sign-up**. Promoting on an email match alone would let anyone register
`admin@ourcompany.com` (if unclaimed) and **self-promote to administrator** — a privilege-escalation
hole.

Therefore the allowlist is honored **only when `token.emailVerified` is true**. A manual sign-up
with an allowlisted address becomes admin only *after* verifying that email — the correct, safe
behavior. The codebase already distinguishes this signal (`token.emailVerified` drives
`VerificationStatus` in `service/user/TokenMapping.kt`), so the gate is a single condition, not a
redesign.

## Scope and rules

- **Bootstrap + safety net, not the primary admin-management path.** Day-to-day admin changes go
  through the existing **in-app role-grant UI** (audited via `granted_by`). The allowlist exists so
  the first admin can come into being and so operators have a recoverable break-glass path. Tying
  *all* authorization to an env list does not scale and loses per-grant auditability.
- **Grant-only — no auto-revoke.** Removing an email from `ADMIN_EMAILS` does **not** revoke an
  existing administrator. Auto-revoke would make a fat-fingered deploy a lock-out risk and fights
  the "cannot revoke the last administrator" guardrail. Revocation is done deliberately through the
  UI/API.
- **Evaluated on provision *and* on authenticated login.** Sign-up happens once; checking again on
  login/token resolution means adding an email to the allowlist later promotes an *existing* user on
  their next session. The grant is idempotent (only written when missing).
- **Audited.** The auto-grant is written to the capability audit trail with a clear synthetic source
  (e.g. `granted_by = null`, reason `"bootstrap allowlist"`) so any administrator's origin is
  traceable — preserving the project's audit-trail philosophy even though the trigger is env-driven.

### Why env (not in-code) here

The project deliberately keeps *semantic* config in source for build-traceability (e.g. the rating
K-factor). The admin allowlist is different: it's **operational, secret-ish identity** that must be
changeable without a code change/redeploy and must not live in the repo. Sourcing it from **Secret
Manager** (surfaced as an env var) is the appropriate choice, and the per-grant audit entry keeps it
accountable.

## Configuration

| Variable      | Meaning                                                                 |
|---------------|-------------------------------------------------------------------------|
| `ADMIN_EMAILS` | Comma-separated allowlist of admin emails. Empty/unset ⇒ **no** auto-admins. |

- Emails are **normalized** (lowercased, trimmed) on both sides before comparison.
- Empty/unset is the safe default so dev/test environments never accidentally mint administrators.
- Store the value in **Secret Manager** and inject it as an env var (Cloud Run), not inline in the
  deploy manifest.

## How it lands in the code (sketch)

- `service/user/TokenMapping.kt` → `buildProvisionCommand`: compute
  `isBootstrapAdmin = token.emailVerified && token.email?.normalized() in adminEmails`, and set
  `capabilities = if (isBootstrapAdmin) setOf(PLAYER, ADMINISTRATOR) else setOf(PLAYER)`
  (`ProvisionUserCommand.capabilities` already defaults to `{PLAYER}`).
- A login/`currentUser` hook (`service/user/UserService.kt`): if an already-provisioned user is now
  allowlisted (verified) but lacks `ADMINISTRATOR`, grant it idempotently — covering emails added to
  the list after the user first signed up.
- Both paths append a capability audit entry with the synthetic source.
- The allowlist is read once from config (`application.yaml` / `ADMIN_EMAILS`) and passed in, kept
  out of source.

## Consequences

- ✅ Solves the first-admin chicken-and-egg with no manual SQL against production.
- ✅ Recoverable break-glass path; no standing backdoor endpoint.
- ✅ Safe against the manual-signup escalation vector (verified-email gate).
- ✅ Auditable (grants recorded) despite the env-driven trigger.
- ⚠️ Authorization for bootstrap admins depends on an env value; mitigated by Secret Manager +
  audit logging + grant-only semantics.
- ⚠️ Not a scalable RBAC mechanism — intentionally limited to bootstrap/break-glass; everyday admin
  management stays in the audited in-app grant flow.

## Alternatives considered (from #45)

- **Manual post-deploy SQL** — zero code, but manual, error-prone, weak per-grant audit, requires
  direct prod DB access. Acceptable fallback only.
- **One-time secured bootstrap endpoint** — works only while zero admins exist; more code and must
  be provably inert afterward.
- **Admin-provisioned placeholder + claim-on-login** — needs a claim/link flow and care that the
  right person claims it.

The verified-email allowlist was chosen as the best balance of safety, auditability, operability,
and minimal code.
