# Authentication Architecture

How Skopeo authenticates users across all three sign-up methods (Google, Facebook,
email/password), and why the backend stores **no passwords**.

## TL;DR

- **Firebase Authentication is the identity provider** for every method. The web UI talks to
  Firebase directly to sign up / log in; it never sends a password to the Skopeo API.
- Firebase returns a signed **ID token (JWT)**. The web UI attaches that token to every Skopeo
  API call; the API verifies it with Firebase's public keys.
- Email/password credentials live **in Firebase**, never in `SkopeoDb`. There is **no passwords
  table** and there should never be one.
- The mental model: **Firebase answers "who are you?"; the Skopeo API answers "what can you do,
  and what's your tennis data?"**
- **Authorization is in-house** (the `user_capabilities` roles), enforced in the API — not
  delegated to Firebase. See [Authorization](#authorization--and-why-it-is-not-outsourced).

## The two backends, and who calls which

There are effectively two backends. The browser calls **Firebase** for authentication and the
**Skopeo API** for application data — they are separate.

```
AUTHENTICATION  (Firebase only — the Skopeo API is NOT involved)
  Browser ──signInWithEmailAndPassword / signInWithPopup──► Firebase Auth
           (Google's identitytoolkit.googleapis.com; the firebaseConfig.apiKey
            identifies the skopeo-prod project)
  Browser ◄──────────────── signed ID token (JWT) ─────────► Firebase Auth

APPLICATION DATA  (now the Skopeo API gets involved)
  Browser ──/api/v1/... + "Authorization: Bearer <JWT>"──► Skopeo API (:8080)
  Skopeo API ──verify JWT signature against Firebase's public JWKS──► trusts the caller
```

The only "auth API" the web UI calls is Firebase's. The Skopeo API is stateless with respect
to authentication: it issues no sessions, stores no passwords, and runs no OAuth — it only
**verifies** tokens.

## Sign-up vs. login

All three providers go through Firebase's JS SDK (`web/src/auth/AuthProvider.tsx`):

| Action | Firebase SDK call | Status |
|---|---|---|
| Manual sign-up | `createUserWithEmailAndPassword` | ✅ implemented — creates the credential in Firebase **and** signs the user in immediately |
| Google sign-up/login | `signInWithPopup(googleProvider)` | ✅ implemented — OAuth handled entirely by Firebase |
| Manual login (returning user) | `signInWithEmailAndPassword` | ✅ implemented — validates against Firebase's stored credential |
| Facebook sign-up/login | `signInWithPopup(facebookProvider)` | ✅ implemented (client) — requires the Facebook provider enabled in the Firebase console |

> ℹ️ **Facebook is wired in the web UI** (`facebookProvider` in `web/src/lib/firebase.ts`,
> `signInWithFacebook` in `AuthProvider`, and a "Continue with Facebook" button on the sign-up
> and login pages). For it to work at runtime, an operator must **enable the Facebook provider
> in the Firebase console** — that needs a Facebook app (App ID + secret) from
> developers.facebook.com and the OAuth redirect URI registered there. The application code
> requires no further changes; the token flow is identical to Google.

**Routing** (`web/src/App.tsx`): `/signup` → `SignUpPage`, `/login` → `LoginPage`, `/dashboard`
is gated by `RequireAuth`.

- **After sign-up there is no separate login step** — Firebase auto-signs-in on account
  creation, so `SignUpPage` goes straight to provisioning the profile and then `/dashboard`
  (`web/src/routes/SignUpPage.tsx`).
- **`/login` is for returning users** (a fresh browser session). `RequireAuth`
  (`web/src/auth/RequireAuth.tsx`) redirects an unauthenticated visit to `/login`, preserving
  the intended destination so login can send them back.

## How the backend gets a `VerifiedFirebaseToken`

1. The web UI requests the current user's ID token: `auth.currentUser.getIdToken()`. This is
   done in the axios request interceptor (`web/src/api/axios.ts`), which sets
   `Authorization: Bearer <JWT>` on every generated API call.
2. The Skopeo API verifies the JWT (`src/main/kotlin/org/skopeo/Security.kt`):
   - Signature is checked against Firebase's **public** JWKS
     (`https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com`)
     — RS256, no service-account key needed.
   - **Issuer** must be `https://securetoken.google.com/<FIREBASE_PROJECT_ID>` and **audience**
     must equal `<FIREBASE_PROJECT_ID>` (default `skopeo-prod`, overridable via the
     `FIREBASE_PROJECT_ID` env var).
3. The validated claims become a `VerifiedFirebaseToken` (`uid`, `email`, `emailVerified`,
   `signInProvider`, …) that routes and services use to identify the caller.

Because verification is identical regardless of provider, email/password users follow the exact
same backend path as Google/Facebook users.

## What lives where

| Data | Firebase | `SkopeoDb` |
|---|---|---|
| Email + password (manual sign-up) | ✅ the actual credential (scrypt-hashed) | ❌ never |
| Google / Facebook identity | ✅ | ❌ |
| `uid`, email, sign-in provider | ✅ source of truth (baked into the token) | a linkage copy in `users` + `user_identities` (`provider = 'PASSWORD' | 'GOOGLE' | 'FACEBOOK'`, **no secret**) |
| Profile: display name, sex, date of birth | ❌ | ✅ |
| Ratings, rating history, matches, capabilities | ❌ | ✅ |

On first sign-in the profile is provisioned (`POST /api/v1/users`). The token's identity is
mapped into the domain by `src/main/kotlin/org/skopeo/service/user/TokenMapping.kt`, which writes
the `user_identities` row recording **which Firebase provider** the user authenticates with — a
pointer, not a password.

## Why there is no passwords table

Storing passwords in `SkopeoDb` would:

1. **Duplicate Firebase**, which already stores the email/password credential and handles login,
   reset, email verification, lockout, and abuse protection.
2. **Create a serious security liability** — a password store is the highest-value breach target,
   and we would own hashing, salting, rotation, and reset flows we currently get for free.
3. **Break the unified model** — the API trusts a Firebase ID token regardless of provider. If
   passwords lived in our DB, email/password users would need a different auth path than
   Google/Facebook users.

Email/password is simply Firebase's `PASSWORD` provider, on equal footing with the OAuth ones.

## Authorization — and why it is *not* outsourced

Authentication and authorization are different kinds of problems, which is why Skopeo outsources
one and keeps the other in-house:

- **Authentication** ("is this person who they say they are?") is a commodity — passwords,
  OAuth, token issuance, breach protection are the same for every app. Ideal to hand to a
  specialist (Firebase).
- **Authorization** ("can this user host a match / set a rating / calculate rankings?") is
  **domain logic**. Skopeo's capabilities map to tennis-domain actions, must be enforced right
  next to the data they protect (the API + PostgreSQL), and need an **audit trail**. That is why
  they live in our own system.

### Skopeo's model (capabilities)

Role-based access control, enforced in the service layer:

- **Roles:** `PLAYER`, `HOST`, `CLUB_OWNER`, `ADMINISTRATOR` (`model/Capability`).
- **Storage:** the `user_capabilities` table is **append-only** — a grant is an active row
  (`granted_by`/`granted_at`), a revoke flips it inactive (`revoked_by`/`revoked_at`), and a
  user holds at most one active row per capability. This preserves a full grant/revoke history.
- **Granting:** `POST/DELETE` via `routes/CapabilityRoutes.kt` → `service/capability/CapabilityService.kt`,
  which is **ADMINISTRATOR-only** (and refuses to revoke the last administrator).
- **Enforcement:** checks live in the services (e.g. `UserService.requireStaff` / `requireAdmin`,
  the rating/match services), so authorization is applied where the domain data is.

### Can Firebase (or a SaaS) do authorization?

Firebase *can* do a limited form, but neither mechanism fits Skopeo:

| Firebase feature | What it does | Why it doesn't replace the capabilities table |
|---|---|---|
| **Custom claims** | Small key/values on a user (`role: "admin"`) set via the **Admin SDK**, carried in the ID token | Coarse (roles/flags only, **1000-byte** token limit); requires a **service-account key** (Skopeo is deliberately keyless — JWKS-only); claims propagate slowly (token refresh, up to ~1h); no relational grants, no grant/revoke audit |
| **Security Rules** | Declarative access rules for **Firestore / Realtime DB / Cloud Storage** | Only govern Firebase's own data stores. Skopeo uses its **own Ktor API + PostgreSQL**, so Security Rules never run on our requests |

> Note: GCP IAM authorizes access to **GCP resources**, not your application's end users — also
> not applicable here.

There is a whole category of dedicated **authorization** services (distinct from authn
providers), worth knowing if authorization ever outgrows a simple role model:

- **Policy engines (RBAC/ABAC):** Cerbos, Oso / Oso Cloud, Permit.io, Amazon Verified
  Permissions (Cedar).
- **Relationship-based (Google Zanzibar–style, fine-grained "X can edit Y because…"):**
  OpenFGA (CNCF), SpiceDB/AuthZed, Auth0 FGA.

### Recommendation

**Keep the current in-house model.** A DB-backed capability model with an audit trail is the
right fit for a small, coarse, global role set — and since each request already loads the user,
the authorization check is nearly free. An external authz engine would add a network hop, a
vendor, and policy-sync complexity that isn't justified yet.

Reconsider only when:

- **You want the web UI to gate tabs without an API round-trip** → optionally *mirror* coarse
  roles into Firebase custom claims as a convenience cache, but keep the **DB as source of
  truth** (claims can be stale and require the Admin SDK).
- **Authorization becomes relational/fine-grained** (e.g. "a HOST may edit only matches for
  clubs they own", per-tournament roles, delegation) → evaluate OpenFGA / Cerbos.

## Firebase-native gaps (not backend/database work)

These are optional enhancements, all implemented as Firebase **client** calls — none touch the
database:

- **Email verification** — password sign-ups return `emailVerified = false` (so `TokenMapping.kt`
  stores the email contact as `PENDING`). Call `sendEmailVerification(user)` after sign-up to
  verify it.
- **Password reset** — wire `sendPasswordResetEmail(auth, email)` to a "Forgot password?" link on
  `LoginPage`. Firebase sends and handles the reset entirely.
- **Facebook (runtime enablement)** — the client is wired (`facebookProvider`,
  `signInWithFacebook`, buttons). The remaining step is operational: enable the Facebook provider
  in the Firebase console (requires a Facebook app's App ID + secret). No code change needed.

## Key files

| Concern | File |
|---|---|
| Firebase client init (project config, providers) | `web/src/lib/firebase.ts` |
| Sign-up / login / sign-out SDK calls | `web/src/auth/AuthProvider.tsx` |
| Route protection / redirect to `/login` | `web/src/auth/RequireAuth.tsx` |
| Attaching the ID token to API calls | `web/src/api/axios.ts` |
| Routing (`/signup`, `/login`, `/dashboard`) | `web/src/App.tsx` |
| Sign-up page (provision profile) | `web/src/routes/SignUpPage.tsx` |
| Login page (returning users) | `web/src/routes/LoginPage.tsx` |
| Backend token verification (JWKS, issuer/audience) | `src/main/kotlin/org/skopeo/Security.kt` |
| Token → domain identity mapping (`user_identities`) | `src/main/kotlin/org/skopeo/service/user/TokenMapping.kt` |
| Firebase project ID config | `src/main/resources/application.yaml` (`firebase.projectId`) |
