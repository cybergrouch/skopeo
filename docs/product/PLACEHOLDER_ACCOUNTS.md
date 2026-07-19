# Placeholder (Dummy) Player Accounts + Claim/Adopt

**Status:** 🟢 Design-of-record for [#496](https://github.com/cybergrouch/skopeo/issues/496). This document is the single source of truth for placeholder accounts and the claim/adopt process.

A **placeholder** is a login-less ("dummy") player so a backlog of already-played events can be recorded against attendees who don't yet have a Skopeo account. It is a first-class player for everything except authentication — it can be added to fixtures and accrues ratings, points, and standings normally — and it can later be *claimed* by the real person, transferring its history onto their account.

---

## 1. What a placeholder is

A placeholder is an ordinary `users` row, with these distinctions:

| Field | Placeholder | Notes |
|---|---|---|
| `firebase_uid` | `NULL` | Already nullable + unique since V1. No credential → can never log in. |
| `placeholder` | `true` | The flag (V23). |
| `public_code` | auto-generated | Same visible code every player has; used for public pages/QR. |
| **name** | DISPLAY name (required) | Supplied at creation. |
| **capability** | `PLAYER` **only** | Never HOST / CLUB_OWNER / ADMINISTRATOR / RATER / RESEARCHER. |

Because it is a real `users` row, a placeholder participates in fixtures, rating calculations, points awards, and standings exactly like a signed-up player. It differs from a normal player in exactly two ways: **it has no login**, and **it can hold only the `PLAYER` capability** (it can never be granted a management/staff capability, because there is nobody to act as).

---

## 2. Creation

**Who:** any user with a match-management capability — `HOST`, `CLUB_OWNER`, or `ADMINISTRATOR`. There is **no invite gate**: creating a placeholder is not the same as inviting a person to sign up, so it does not consume or require an invite.

**Required fields:** display **name** and **sex** (Male/Female). Sex is required because standings and points are split by `(band, sex)` — a placeholder must land in the correct race.

**Optional:** birthdate.

**Endpoints:**

| Method | Path | Who | Purpose |
|---|---|---|---|
| `POST` | `/api/v1/users/placeholders` | HOST / CLUB_OWNER / ADMINISTRATOR | Create a placeholder. |
| `GET` | `/api/v1/users/placeholders` | HOST / CLUB_OWNER / ADMINISTRATOR | Management/list view of placeholders. |

**Dedup (soft):** the create UI searches existing players by name first and surfaces likely matches, so a host doesn't create a duplicate for someone who already has an account. This is a **soft** guard, not a hard constraint — people share names, so the host can always proceed.

Creation is audited as `AuditAction.PLACEHOLDER_CREATED`.

---

## 3. The claim code (the credential)

The claim code is the secret that lets the real person adopt a placeholder. It is the trust anchor of the whole flow, so its handling is deliberately strict.

- **Admin-driven, backend-generated.** Only an `ADMINISTRATOR` can issue a code, and the **backend generates it** — the admin never supplies or chooses it. This keeps the admin (who has verified the person out-of-band) as the trust anchor while removing any chance of a weak or guessable code.
- **Cryptographically random.** 20-character Crockford base32 (~100 bits of entropy).
- **Stored hashed.** Only the **SHA-256** hash is persisted (`placeholder_claim_codes.code_hash`); the plaintext is **never** stored.
- **Distinct from `public_code`.** The visible `public_code` identifies the profile publicly; the claim code is a secret credential. They are unrelated.
- **One-time use.** Consumed on a successful claim.
- **Mandatory 7-day expiry.** Every code expires (`expires_at`), matching the invite TTL cadence.
- **Rotation.** Re-issuing supersedes the prior **ACTIVE** code by marking it `CONSUMED`; a placeholder has at most one ACTIVE code at a time.
- **Shown once.** The plaintext is returned **exactly once**, from the generation call, for the admin to hand to the verified person out-of-band (message, in person, etc.).

**Endpoint:**

| Method | Path | Who | Returns |
|---|---|---|---|
| `POST` | `/api/v1/users/{id}/claim-code` | ADMINISTRATOR | The plaintext code, once. |

Generating a code for a **non-placeholder** user is a **400**; generating one for an **already-claimed** placeholder is a **409**.

---

## 4. Claim / merge-into-empty

The claim is a **separate, post-sign-up step**. There is no sign-up interception and no email matching — the placeholder and the real account are linked only by the person presenting the secret code.

**Flow:**

1. The person **signs up normally** (any provider) and gets an empty account.
2. Logged in, they visit the **"Claim a placeholder account"** page.
3. They paste the code they were given.
4. `POST /api/v1/users/claim`, body `{ "code": "..." }`.

**On success:**

- The placeholder's history — matches, ratings, and points — is **re-pointed** onto the claimant's account (see [§7](#7-merge-inventory)).
- The placeholder is **retired** using the existing `canonical_user_id` duplicate-merge pattern: `is_active = false`, `canonical_user_id = <claimant>`, plus `claimed_at` and `claimed_by`. The placeholder's public page then shows the standard "merged" card linking to the real profile.
- The code is **consumed** (`status = CONSUMED`, `consumed_at`, `consumed_by`).
- The claim is audited as `AuditAction.PLACEHOLDER_CLAIMED`, with the **claiming user** as the target (creation, by contrast, is `AuditAction.PLACEHOLDER_CREATED`).

**What transfers:** *only history.* The claimant keeps their **own** name, sex, and contacts; those are never overwritten by the placeholder's.

---

## 5. The "empty account" requirement (and the deferred two-history case)

v1 supports **merge-into-empty only**.

An account is **empty** iff it has **no rating-history rows AND no match participation**. If the caller's account already carries rating or match history, the claim is **rejected with a 409**.

**Why the restriction:** merging *two* accounts that both already have divergent rating histories cannot be done by averaging — the ratings are path-dependent. The correct fix is to **discard both stored ratings and re-run the rating calculation over the union of both accounts' matches, chronologically**; points, being additive, are **summed and de-duplicated**. That two-active-histories merge is **deferred to an admin-only follow-up** and is out of scope for v1.

---

## 6. Rejection paths

Every rejection surfaces a clear `ServiceError` mapped to an HTTP status:

**Claim (`POST /api/v1/users/claim`):**

| Condition | HTTP |
|---|---|
| Bad/unknown code | 404 |
| Expired code | 400 |
| Already-consumed code (no longer ACTIVE) | 404 |
| Caller's account is not empty (has rating/match history) | 409 |
| Caller is itself a placeholder | 409 |
| Caller has not signed up | 403 |

**Code generation (`POST /api/v1/users/{id}/claim-code`):**

| Condition | HTTP |
|---|---|
| Target is not a placeholder | 400 |
| Target placeholder is already claimed | 409 |

---

## 7. Merge inventory

Claiming re-points the placeholder's `user_id` foreign keys onto the claimant and leaves provenance FKs alone (a placeholder is never an actor). Conflict handling respects each table's uniqueness constraint.

| Table | Action | Conflict handling |
|---|---|---|
| `user_rating_history` | update `user_id` → claimant | none |
| `ranking_point_awards` | update `user_id` → claimant | none |
| `user_ratings` | transfer to claimant | target is empty in v1, so no conflict |
| `team_users` | update `user_id` → claimant | dedupe on `UNIQUE(team_id, user_id, joined_at)` |
| `event_participants` | update `user_id` → claimant | dedupe on `UNIQUE(event_id, user_id)` |
| `player_list_members` | update `user_id` → claimant | dedupe on `UNIQUE(list_id, user_id)` |
| `club_owners` | update `user_id` → claimant | dedupe on `UNIQUE(club_id, user_id)` |
| `seeding_entries` | re-point to claimant | next rebuild reflects the real user |
| `standings_entries` | none | immutable snapshot; next rebuild reflects the real account |
| **Provenance FKs** — `matches.created_by` / `recorded_by` / `rated_by`, `events.created_by`, `audit_log.actor_user_id`, etc. | left as-is | placeholders are never actors, so these never reference a placeholder |

---

## 8. Visibility / unclaimed indicator

A placeholder's **public profile is viewable**. It carries an **"unclaimed/placeholder" indicator** — `isPlaceholder: true` on `PublicPlayerResponse` — and a **"Claim this account"** entry point so the real person (or an admin guiding them) can start the flow. After a claim, the placeholder's public page shows the merged card linking to the real profile (§4).

---

## 9. Constraints recap

- **Login-less** — `firebase_uid = NULL`, can never authenticate.
- **PLAYER-only** — can never hold a management/staff capability.
- **Reject-if-non-empty** — v1 claims only merge into an empty account (§5).
- **Separate post-sign-up step** — no sign-up interception, no email matching; the claim is an explicit action by the logged-in claimant.
- **The admin issuing the code is the trust anchor** — the backend generates the secret, but an administrator who has verified the person out-of-band is the one who releases it.

---

## 10. Migration

Added by **`V23__placeholder_accounts.sql`**:

- `users.placeholder` (`BOOLEAN NOT NULL DEFAULT FALSE`), `users.claimed_at` (`TIMESTAMP`), `users.claimed_by` (`UUID` → `users(id)`), plus a partial index on unclaimed placeholders.
- `placeholder_claim_codes` — stores **hashed** codes (`code_hash`, SHA-256 hex), `expires_at`, `status` (`ACTIVE` / `CONSUMED`), and creation/consumption provenance. The plaintext is never stored.
