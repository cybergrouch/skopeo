# Skopeo — Manual End-to-End Testing Guide

A printable runbook for testing the full stack (PostgreSQL + Ktor API + React web UI).
**Part 1** runs everything locally with Docker and covers auth (**Google, email/password,
and Facebook** sign-up + login) and the per-capability dashboard. **Part 1d** is the new
**feature walkthrough** — the rating pipeline (set ratings → fixtures → results →
calculation) plus every dashboard tab: Profile (rating speed meter, history with per-set
breakdown), Research, Standings, Seeding, Ratings, and the Admin tools (manage player /
roles, duplicate detection, invites, activity log). **Part 2** covers deploying the three
components to GCP. **Appendix A** has Firebase project-setup steps for reference (you do
**not** need them — the project already exists).

> Tip: to make a PDF, open this file in your editor/browser and **Print → Save as PDF**
> (the committed `MANUAL_TESTING_GUIDE.pdf` is generated this way).

---

## Before you start — what to know

1. **You supply your own Firebase project config.** Use an existing Firebase project (ask a
   maintainer for access) or create one ([Appendix A](#appendix-a-firebase-project-setup-reference)).
   Its web config (apiKey, authDomain, projectId, appId) is **per-environment and must not be
   committed** — put it only in `web/.env.local` (which is git-ignored). This guide uses
   placeholders; fill in your project's real values locally.
2. **Keep the project IDs in sync.** The API reads `firebase.projectId` from `application.yaml`
   (override with the `FIREBASE_PROJECT_ID` env var). The web's `VITE_FIREBASE_PROJECT_ID` must
   match the project the API verifies tokens against — if they differ, every login returns 401.
3. **Facebook requires one operator step.** Facebook sign-up/login is implemented in the web UI,
   but for it to work at runtime the **Facebook provider must be enabled in the Firebase console**
   (needs a Facebook app's App ID + secret from developers.facebook.com). Until then, Google and
   email/password work; the Facebook button will error.
4. **The web UI is not containerized.** Locally it runs via the Vite dev server; in production it
   is **Firebase Hosting** (a static SPA) — *not* Docker and *not* a server/VM. So there is nothing
   Docker-shaped to simulate for the frontend.

### How the local pieces connect

```
Browser (http://localhost:5173, Vite dev server)
   │  Firebase JS SDK ───────► Firebase Auth (Google)   ← returns signed ID token
   │  /api/* requests ──(Vite proxy)──► http://localhost:8080
   ▼
API container (:8080)  ───────►  PostgreSQL container (:5432)
   verifies the ID token against Google's public JWKS
```

---

## Prerequisites checklist

- [ ] Docker Desktop (or Docker Engine) running — `docker --version`
- [ ] Docker Compose v2 — `docker compose version`
- [ ] Node.js 20+ and npm — `node --version`
- [ ] A Google account (for the Google sign-in test)
- [ ] *(For Test 3)* the Facebook provider enabled in the Firebase console
- [ ] Repo cloned and you're at its root — `cd /path/to/skopeo`
- [ ] Ports free: **5173** (web), **8080** (API), **5432** (Postgres)

---

# Part 1 — Local setup (Docker DB + API, web dev server)

## Step 1 — Firebase config (your project)

Get your project's web config from the Firebase console (**Project settings ⚙ → General → Your
apps → Web app**). Keep these values out of git — they go only in `web/.env.local` (Step 3).

- [ ] Project ID: `<your-project-id>`
- [ ] Auth providers enabled (Build → Authentication → Sign-in method):
  - [ ] **Email/Password**
  - [ ] **Google**
  - [ ] **Facebook** — required only for Test 3 (see note 3 above)
- [ ] Web config (from the console; do **not** commit):
  - `apiKey`: `<your-api-key>`
  - `authDomain`: `<your-project-id>.firebaseapp.com`
  - `projectId`: `<your-project-id>`
  - `appId`: `<your-app-id>`

> `localhost` is an authorized domain by default, so the Google/Facebook popups work locally with
> no extra configuration.

## Step 2 — Run PostgreSQL + API in Docker

`docker-compose.yml` builds and runs both the database (`postgres`, :5432) and the API
(`skopeo`, :8080). The API reads its Firebase project from `application.yaml`; if that built-in
default already matches your project you can skip the override below.

- [ ] 2.1 Build and start:

```bash
docker compose up -d --build
```

- [ ] 2.2 Watch the API come up (look for "Flyway migrations complete" and
      "Responding at 0.0.0.0:8080"):

```bash
docker compose logs -f skopeo
```

- [ ] 2.3 Health check (expect HTTP 200):

```bash
curl -i http://localhost:8080/health
```

- [ ] 2.4 *(Optional)* Database GUI:

```bash
docker compose --profile tools up -d pgadmin
# http://localhost:5050  —  login admin@skopeo.com / admin
```

> *(Only if the API's default project doesn't match yours)* create `docker-compose.override.yml`
> at the repo root with `services: { skopeo: { environment: { FIREBASE_PROJECT_ID: "skopeo-prod" } } }`.

## Step 3 — Run the web UI (dev server)

The web UI runs as the Vite dev server, which proxies `/api` to the API container. (Production is
Firebase Hosting, not Docker — see note 4 and Part 2.)

- [ ] 3.1 Install deps and create the env file:

```bash
cd web
npm install
cp .env.example .env.local
```

- [ ] 3.2 Edit `web/.env.local` with the Firebase config from Step 1. **Leave `VITE_API_BASE_URL`
      empty** so it uses the dev proxy to `:8080`:

```
VITE_FIREBASE_API_KEY=<your-api-key>
VITE_FIREBASE_AUTH_DOMAIN=<your-project-id>.firebaseapp.com
VITE_FIREBASE_PROJECT_ID=<your-project-id>
VITE_FIREBASE_APP_ID=<your-app-id>
VITE_API_BASE_URL=
```

> The app only reads these four `VITE_FIREBASE_*` keys plus `VITE_API_BASE_URL`; the
> `storageBucket`, `messagingSenderId`, and `measurementId` fields from the Firebase config
> snippet are not used and can be omitted.

- [ ] 3.3 Start the dev server:

```bash
npm run dev      # serves http://localhost:5173
```

- [ ] 3.4 Open <http://localhost:5173> — you should see the sign-up / login screen.

> **Optional — mirror the production static build.** Production serves the built bundle from
> Firebase Hosting, not the dev server. To exercise the built artifact locally:
> `npm run build && npm run preview`. The preview server has **no `/api` proxy**, so set
> `VITE_API_BASE_URL=http://localhost:8080` before building and add that origin to the API's CORS
> allow-list (`Application.configureCORS()`). For ordinary functional testing the dev server
> (3.3) is simpler and recommended.

---

# Part 1b — Sign-up tests

For each test, sign up, then observe the dashboard. A brand-new account gets the `PLAYER` and
`RESEARCHER` capabilities, so the dashboard shows **Profile, Research, and Standings** tabs, with a
"Pending assessment" rating on the Profile tab — that is the expected result. (Matches, Seeding,
Ratings, and Admin tabs require additional capabilities — see **Part 1d**.) After sign-up you are
auto-signed-in; the `/login` page is exercised separately in **Part 1c**.

## Test 1 — Sign up with Google

- [ ] Open <http://localhost:5173> → **Sign up**.
- [ ] Fill **Sex** and **Date of birth** (both required).
- [ ] Click **Continue with Google** → choose a Google account in the popup → land on the dashboard.

**Expected:** Profile / Research / Standings tabs; name + email; `PLAYER` and `RESEARCHER` badges;
"Pending assessment" rating card on the Profile tab.

Result: ☐ Pass ☐ Fail — notes: __________________________________________

## Test 2 — Sign up manually (email + password)

- [ ] Sign out (if signed in) → **Sign up** → enter email, password, **Sex**, **Date of birth** → submit.

**Expected:** identical dashboard to Test 1. The stored email shows as unverified/PENDING for
password accounts — fine for this test.

Result: ☐ Pass ☐ Fail — notes: __________________________________________

## Test 3 — Sign up with Facebook

> Prerequisite: the **Facebook provider must be enabled** in the Firebase console (note 3).

- [ ] Sign out → **Sign up** → fill **Sex** and **Date of birth** (required before the popup).
- [ ] Click **Continue with Facebook** → authenticate in the Facebook popup → land on the dashboard.

**Expected:** identical dashboard to Tests 1 & 2; the profile's identity provider is Facebook.
If you reuse an email already registered via Google, expect the friendly error *"An account already
exists with this email. Sign in with the original method."*

Result: ☐ Pass ☐ Fail ☐ Blocked (Facebook provider not enabled) — notes: ______________________

---

# Part 1c — Login tests (returning users)

After sign-up you're already authenticated, so login is for a **fresh session**. For each method:

- [ ] Sign out, then visit <http://localhost:5173/login> (or let `RequireAuth` redirect you there).
- [ ] **Google:** click **Continue with Google** → back to the dashboard.
- [ ] **Email/password:** enter the email + password from Test 2 → **Sign in** → dashboard.
- [ ] **Facebook:** click **Continue with Facebook** → dashboard *(requires the provider enabled)*.

**Expected:** each returns you to your existing profile (same data, no duplicate account — sign-in
is idempotent on the Firebase `uid`).

Result: ☐ Google ☐ Manual ☐ Facebook — notes: __________________________________________

---

## Bootstrap your first administrator

Granting roles requires an existing admin, so you need to create the **first** one. Two ways:

**Preferred — the `ADMIN_EMAILS` allowlist (no SQL).** Any sign-in with a *verified* email on the
allowlist is auto-promoted to `ADMINISTRATOR`. Add your Google email to the API's `ADMIN_EMAILS`
(comma-separated) and restart the API, then sign in with that Google account:

```bash
# docker-compose.override.yml at the repo root:
#   services: { skopeo: { environment: { ADMIN_EMAILS: "you@gmail.com" } } }
docker compose up -d --build skopeo     # restart the API with the env var
```

> Auto-promotion requires `email_verified` — it works for **Google** sign-in but **not** an
> unverified email/password account. For those, use the SQL fallback. See
> `docs/engineering/architecture/ADMIN_BOOTSTRAP.md`.

**Fallback — direct SQL** (works for any account; target a specific user by email):

```bash
docker compose exec postgres psql -U postgres -d SkopeoDb -c \
"INSERT INTO user_capabilities (user_id, capability)
 SELECT user_id, 'ADMINISTRATOR' FROM contact_information WHERE value = 'you@example.com';"
```

- [ ] Hard-refresh the dashboard → **all tabs** (Profile, Research, Standings, Matches, Seeding,
      Ratings, Admin) now appear.
- [ ] From here, grant every other role through **Admin → Manage player → Roles** — no more SQL.

The rest of the rating pipeline and each tab are exercised in **Part 1d** below.

## Capability display tests (per-capability tabs)

Authorization is capability-based, and the dashboard shows different tabs per capability.
Capabilities are **additive** — a user keeps `PLAYER`/`RESEARCHER` and gains others. The full set is
`PLAYER`, `RESEARCHER`, `HOST`, `CLUB_OWNER`, `RATER`, `ADMINISTRATOR`. `ADMINISTRATOR` implicitly
unlocks every tab (it counts as `RATER` and `RESEARCHER`, and satisfies match-management).

Once you have an administrator (bootstrapped below), **grant/revoke roles through the UI**:
**Admin → Manage player → Roles** (covered in Part 1d). The SQL grant below is only needed once, to
create that **first** administrator.

After any role change, **hard-refresh the browser** (the dashboard refetches `GET /api/v1/users/me`).

Expected dashboard per capability:

| Capability      | How it's set                | Tabs unlocked                            | Notes                                             |
|-----------------|-----------------------------|------------------------------------------|---------------------------------------------------|
| `PLAYER`        | automatic at sign-up        | Profile, Standings                       | base role; cannot be revoked                      |
| `RESEARCHER`    | automatic at sign-up (#107) | Research                                 | gates the Research tab; later monetizable         |
| `HOST`          | grant via Admin UI          | Matches, Seeding                         | match management + seeding (hosts are players)    |
| `CLUB_OWNER`    | grant via Admin UI          | Matches, Seeding                         | same as host for now; evolves later               |
| `RATER`         | grant via Admin UI (#106)   | Ratings                                  | set initial ratings / triage pending assessment   |
| `ADMINISTRATOR` | bootstrap once, then UI      | all (incl. Admin, Ratings, Research)     | full access                                       |

- [ ] **Player + Researcher** — a fresh account (Tests 1–3) shows **Profile, Research, Standings**;
      **no Matches, Seeding, Ratings, or Admin** tab.
- [ ] **Host** — grant `HOST`, refresh → **Matches** and **Seeding** tabs appear; still no Ratings/Admin.
- [ ] **Club owner** — grant `CLUB_OWNER` (revoke HOST first to isolate), refresh → **same as a host**
      (Matches, Seeding). Revoke when done.
- [ ] **Rater** — grant `RATER`, refresh → the **Ratings** tab appears.
- [ ] **Administrator** — grant `ADMINISTRATOR`, refresh → **all tabs** including **Admin** (and
      Ratings/Research even without those explicit roles).
- [ ] **Additive** — with `HOST` + `ADMINISTRATOR`, all tabs show; revoke `ADMINISTRATOR` and refresh
      → Admin/Ratings disappear while Matches/Seeding/Research/Standings/Profile remain (HOST present).

---

# Part 1d — Feature walkthrough (the rating pipeline + every tab)

This part needs the administrator you bootstrapped above, plus a few test players (sign up 3–4
accounts). Roles are granted through the Admin UI. The first six tests are the **core rating
pipeline** in order — rate players → schedule a fixture → record the result → run the calculation →
read the ratings & history; the rest cover each remaining tab. Capabilities in brackets are what's
required to *see* the relevant tab.

## Test 4 — Admin: Manage player — profile, rating override, roles `[ADMINISTRATOR]`

- [ ] **Admin → Manage player** → search a member by name/code → select. Header shows
      "Name · code" with a **Change** button.
- [ ] **Profile** block — change **Sex** and/or **Date of birth** → **Save profile** → "Saved".
      (Untouched fields are left unchanged.)
- [ ] **Rating** block — enter an NTRP value (e.g. `4.0`) → **Override rating** → "Saved". This
      writes an audited rating-history entry.
- [ ] **Roles** block — each of `HOST`, `CLUB_OWNER`, `RATER`, `RESEARCHER` shows a **Grant**/
      **Revoke** toggle (`ADMINISTRATOR` is not toggleable here). Grant **HOST** to one test user
      (your "host") and **RATER** to another (or just use the admin, who already has both).

Result: ☐ Pass ☐ Fail — notes: __________________________________________

## Test 5 — Rater: set initial ratings (Ratings tab) `[RATER]`

- [ ] As a `RATER` (or admin) open the **Ratings** tab → **Pending assessment** lists players with
      no rating. A self-reported rating (from onboarding) prefills the input.
- [ ] Enter an NTRP value → **Set rating** ("Setting…" while saving) → the player drops off the list.
- [ ] Rate **at least two** players — a fixture needs two rated players.

**Expected:** rated players leave the pending list; the empty state reads "No players are pending
assessment." Setting the same value again is idempotent.

Result: ☐ Pass ☐ Fail — notes: __________________________________________

## Test 6 — Host: schedule a fixture (Matches tab) `[HOST]`

- [ ] As `HOST` (or admin) open **Matches → Schedule a fixture**. Pick **Player 1** and **Player 2**
      (the picker excludes the already-chosen player). Both must be **rated** or you'll get
      "Both players need a rating."
- [ ] Choose a **Match type** (Open play / League play / Tournament — initial round / League
      playoffs / Tournament playoffs) and a **Date** → **Schedule fixture** → "Fixture scheduled."

> Recording a result later does **not** move ratings — that's the admin calculation step (Test 8).

Result: ☐ Pass ☐ Fail — notes: __________________________________________

## Test 7 — Host: record a result `[HOST]`

- [ ] **Matches → Awaiting results** → find the fixture (badge: Overdue / Today / Upcoming).
- [ ] Enter set scores (e.g. Set 1 `6`–`4`, Set 2 `3`–`6`, Set 3 `6`–`2`). **Add set** (up to 5) /
      **Remove**. Each set needs a clear winner — equal games are rejected.
- [ ] **Record result** ("Recording…"). The fixture leaves "Awaiting results" and becomes **pending
      calculation**. The winner is derived server-side.

Result: ☐ Pass ☐ Fail — notes: __________________________________________

## Test 8 — Admin: run the rating calculation — dry-run preview → commit `[ADMINISTRATOR]`

This is the **per-set v2 calculator** (#110): each set is scored sequentially, carrying the rating
forward, with smoothing applied once at the end.

- [ ] **Admin → Pending calculation** shows "**N** matches pending calculation."
- [ ] Click **Preview** — a **dry-run**: nothing is saved. Status: "Preview ready — N matches, no
      changes saved yet."
- [ ] **Expand a match** → per-player projection like `Alice: 3.50 → 3.66 (+0.16)` and a **per-set
      breakdown**, one line per set:
      `Set 1 (6–4): dominance … · scale … · gap …/… · upset|expected · K 0.16 · Δ … → 3.58`.
- [ ] Verify the **carry-forward**: each set's `→ ratingAfter` feeds the next set's gap; the net
      change is the sum of the per-set Δ's (then smoothed once if the match used smoothing).
- [ ] **Commit** → "Committed ratings for N matches." (writes ratings + history + `rated_at`), or
      **Discard** to drop the preview without writing.

**Expected:** preview writes nothing; after commit the matches disappear from the pending list and
the players' ratings/history update.

Result: ☐ Pass ☐ Fail — notes: __________________________________________

## Test 9 — Rating history & per-set breakdown (Profile tab) `[any]`

- [ ] On a rated player's **Profile → Rating history**, entries are newest-first; a band change shows
      a **`Band 3.5 → 4.0`** badge and a highlighted row.
- [ ] Click a **match-driven** entry → it expands to the match scores + winner and the **same per-set
      breakdown** as the preview (persisted at commit time — never recomputed, so it stays faithful
      even if the algorithm constants change later).
- [ ] An **initial assessment** (no match) is a plain row — not expandable.

Result: ☐ Pass ☐ Fail — notes: __________________________________________

## Test 10 — Rating-band speed meter (Profile tab, #114) `[any]`

- [ ] On your **own** Profile, a rated account shows a semicircular **speed meter** — the needle
      marks your position **within** your NTRP band (0 = band floor, 1 = band ceiling). It animates
      on mount and **does not print the exact rating number** (privacy). It respects
      `prefers-reduced-motion`.
- [ ] A **pending-assessment** account shows **no meter** — just the "Pending assessment" message.
- [ ] On a **public** profile (`/players/<code>`, e.g. as an admin viewing another player), the NTRP
      **band** shows but the **speed meter does not**.

Result: ☐ Pass ☐ Fail — notes: __________________________________________

## Test 11 — Standings tab (#113) `[any]`

- [ ] Open **Standings** (visible to everyone). One card **per NTRP band, strongest first**; each
      lists players by **rank** (rating-ordered) with name + `sex · age`.
- [ ] The **rating value is intentionally hidden** — only rank and metadata show.
- [ ] **Your own row is highlighted** with a "You" label; empty bands read "No players yet."

Result: ☐ Pass ☐ Fail — notes: __________________________________________

## Test 12 — Research tab (#107) `[RESEARCHER]`

- [ ] Open **Research** → filter by any combination of **Name**, **Sex**, **Age from/to**, **Rating
      from/to** → **Search** (disabled until at least one filter is set).
- [ ] Results list each match (name + `sex · age` + NTRP band); clicking one opens that player's
      public profile. Invalid ranges → "Invalid filters. Check the age/rating ranges."

Result: ☐ Pass ☐ Fail — notes: __________________________________________

## Test 13 — Seeding tab (#111) `[HOST]`

- [ ] Open **Seeding** → **New list** → name it → **Create**, then select it.
- [ ] **Add players**: optionally set sex/age/rating filters, then use **Find a player** (excludes
      members already added). **Remove** drops a member.
- [ ] **Generate seeding** → a table (**Seed, Name, Code, NTRP, Rating, Sex, Age**). **Regenerate**
      refreshes it; **Download CSV** exports it; **Delete list** removes the list (destructive, no
      confirm).

Result: ☐ Pass ☐ Fail — notes: __________________________________________

## Test 14 — Admin: duplicate detection (#126) & resolution (#124) `[ADMINISTRATOR]`

- [ ] **Auto-detect by phone:** add the **same** phone contact to two different active users → a
      candidate appears in **Admin → Duplicate candidates** as "UserA & UserB — Shared phone …".
- [ ] **Manual flag:** Duplicate candidates → **Flag a pair manually** → pick two users + an optional
      reason → **Flag as candidate**.
- [ ] **Resolve a candidate:** **Keep UserA** / **Keep UserB** confirms one as canonical and disables
      the other; **Dismiss** clears a false positive.
- [ ] **Direct resolution:** **Admin → Duplicate profiles** → choose the canonical account → add one
      or more duplicates → **Mark as duplicates**. Duplicates are **disabled (not deleted)**, drop out
      of search/standings, and their public profile links to the canonical. **Ratings are never
      merged.** **Restore** re-enables a duplicate (reversible).

Result: ☐ Pass ☐ Fail — notes: __________________________________________

## Test 15 — Admin: invites & activity log `[ADMINISTRATOR]`

- [ ] **Invites:** Admin → **Invites** → enter an email → **Send invite** (manual sign-up is
      invite-only). Filter **Actionable / Accepted / Revoked / All**; **Resend** or **Revoke** a
      pending invite.
- [ ] **Activity log:** Admin → **Activity log** → a newest-first table (**When / Who / Action /
      Target / Note**) with a category filter. Add a free-text **Note** to a row → **Save**.

Result: ☐ Pass ☐ Fail — notes: __________________________________________

## Test 16 — Player profile deep links & QR codes `[any]`

Every player has a short shareable code (shown on their **Profile** tab as **Player ID**). It powers
an auth-gated deep link to that player's profile and a QR code for scanning from another device.

### Deep link

- [ ] On any account's **Profile** tab, note the **Player ID** (e.g. `K7Q2MX`).
- [ ] In the browser, open `http://localhost:5173/players/<PLAYER_ID>` → you land on that player's
      profile (display name, avatar, and NTRP rating if they have one). Codes are case-insensitive.
- [ ] **Auth gate:** sign out (or use a private window) and open the same link → you're redirected to
      **login**; after signing in you land back on that player's profile. It is never visible to
      logged-out users.
- [ ] **Unknown code:** open `/players/ZZZZZZ` → "We couldn't find or load this player."
- [ ] **Privacy:** the page shows only display name, avatar, and rating — no email, contacts, or date
      of birth.

### QR code

- [ ] On your own **Profile** tab, the **Share your profile** card shows a **QR code** and a **Copy
      link** button.
- [ ] Click **Copy link**, paste it in a new tab → it opens your own player profile.
- [ ] **Scan from a phone:** point a phone camera at the QR → it opens the deep link.

> **Local-dev caveat for phone scanning.** Locally the QR/link encodes `http://localhost:5173/…`,
> which a phone on another device can't reach. To test scanning, either (a) run the web on your
> machine's LAN IP and use that origin, or (b) test on the deployed site, where the QR encodes the
> Firebase Hosting URL. On the same machine, the Copy-link → paste check above verifies the URL is
> correct regardless.

---

## Teardown / reset

```bash
# stop the web dev server: Ctrl-C in its terminal
docker compose down            # stop API + DB (keeps data volume)
docker compose down -v         # stop AND wipe the database (fresh start next time)
```

### Deleting a single test account — remove it from *both* systems

A user exists in two places: the **app database** *and* **Firebase Authentication**. Deleting only
one leaves an orphan. In particular, if you delete only the database row, re-signing-up with that
email fails with *"This email is already registered"* because the Firebase auth account still exists.

1. **App database** — cascades to names, contacts, ratings, etc. Run via pgAdmin, or:

   ```bash
   docker compose exec postgres psql -U postgres -d SkopeoDb -c \
   "DELETE FROM users WHERE id IN (SELECT user_id FROM contact_information WHERE value = 'you@example.com');"
   ```

2. **Firebase Authentication** — delete the matching user in **Firebase Console → Authentication →
   Users** (or via the Admin SDK). Skipping this is the usual cause of a confusing "already
   registered" error on re-test.

---

# Part 2 — Deploying the three components to GCP

Target architecture (see `DEPLOYMENT_GCP.md` for full detail):

| Component   | GCP service        | Container? | Notes                                              |
|-------------|--------------------|-----------|----------------------------------------------------|
| PostgreSQL  | Cloud SQL          | managed    | `db-f1-micro`, DB `SkopeoDb`, password in Secret Manager |
| Ktor API    | Cloud Run          | ✅ Docker  | built from the repo `Dockerfile`, scales to zero   |
| React web   | Firebase Hosting   | ❌ static  | SPA from `web/dist`, deployed by `deploy-web.yml`  |

**The web UI is a static SPA on Firebase Hosting — there is no web container or server in
production.** So no Docker simulation is needed for the frontend; the only prod-vs-local difference
is that the deployed web reaches the API via `VITE_API_BASE_URL` (the Cloud Run URL) rather than the
Vite dev proxy.

## Deployment sequence

- [ ] **2.1 Project & APIs** — `gcloud projects create …` (or use your existing project), enable
      run/sqladmin/secretmanager/cloudbuild/artifactregistry.
- [ ] **2.2 Database (Cloud SQL)** — create the instance + `SkopeoDb` + user; store the password in
      Secret Manager. Note the private IP.
- [ ] **2.3 API (Cloud Run)** — deploy from source, pointing at the DB and Firebase project:

```bash
gcloud run deploy skopeo \
  --source . --allow-unauthenticated \
  --set-env-vars="DATABASE_URL=jdbc:postgresql://<PRIVATE_IP>:5432/SkopeoDb,DATABASE_USER=skopeo,FIREBASE_PROJECT_ID=skopeo-prod" \
  --set-secrets="DATABASE_PASSWORD=skopeo-db-password:latest"
```

  Set `FIREBASE_PROJECT_ID` to your Firebase project (optional if it already matches the
  `application.yaml` default; explicit is safer so the project stays pinned). Record the Cloud Run URL.

- [ ] **2.4 Web (Firebase Hosting)** — set GitHub repo **Variables** `VITE_FIREBASE_*` and
      `VITE_API_BASE_URL` (= the Cloud Run URL), plus the **Secret** `FIREBASE_SERVICE_ACCOUNT`;
      then push to `main` (or run `deploy-web.yml`). Firebase serves `web/dist` over its CDN.

## Gaps to close before GCP works

- [ ] **CORS:** `Application.configureCORS()` allows only `localhost:5173`. Add the Firebase Hosting
      origin (`https://<project>.web.app`) and redeploy the API, or the browser blocks every call.
- [ ] **No API deploy workflow:** there is no `deploy-api.yml`; Cloud Run deploys are manual
      `gcloud run deploy`.
- [ ] **Authorized domains:** add your `*.web.app` / custom domain under Firebase Auth → Settings →
      Authorized domains, or Google/Facebook sign-in fails in production.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Sign-up/login returns **401** | API/web project IDs differ | Make the web's `VITE_FIREBASE_PROJECT_ID` match the API's project (set `FIREBASE_PROJECT_ID` on the API if it differs from the `application.yaml` default) |
| Facebook button errors / "provider disabled" | Facebook not enabled in Firebase | Enable the Facebook provider (App ID + secret) in the Firebase console |
| "account exists with different credential" | Email already registered via another provider | Sign in with the original method; expected Firebase behavior |
| Popup: "auth domain not authorized" | Domain not authorized | Add it under Firebase Auth → Settings → Authorized domains (`localhost` is there by default) |
| Web loads but API calls fail / CORS | Wrong base URL or proxy not used | Keep `VITE_API_BASE_URL` empty for the dev server; confirm API is up at :8080 |
| `docker compose up` fails on port in use | 5432/8080 taken | Stop the conflicting service or change the published port |
| API logs: connection refused to Postgres | DB not healthy yet | `docker compose logs postgres`; wait for healthy, then restart `skopeo` |
| Only Profile / Research / Standings tabs show | Account is just `PLAYER`+`RESEARCHER` | Expected; grant roles via Admin → Manage player, or bootstrap an admin (see "Bootstrap your first administrator") |
| Can't schedule a fixture ("need a rating") | One/both players are unrated | Rate them first (Ratings tab / Pending assessment, Test 5) |
| Recorded a result but ratings didn't change | By design | Ratings move only on the admin calculation step — Preview then **Commit** (Test 8) |
| New role granted but tab missing | Stale `users/me` | Hard-refresh the browser after any role change |

---

# Appendix A — Firebase project setup (reference)

Use this if you need to create your own Firebase project (or set up a separate one, e.g. a
`*-dev` project). Its config values are per-environment secrets — keep them in `web/.env.local`,
never in git.

1. <https://console.firebase.google.com> → **Add project**; note the **Project ID**.
2. **Build → Authentication → Get started**.
3. **Sign-in method** → enable **Email/Password**, **Google** (pick a support email), and
   **Facebook** (requires a Facebook app's App ID + secret from <https://developers.facebook.com>;
   register the OAuth redirect URI it shows).
4. **Project settings (⚙) → General → Your apps → Web (`</>`)** → register an app and copy the
   `apiKey`, `authDomain`, `projectId`, `appId` into `web/.env.local` (and the GitHub repo
   variables for deploys). The API only needs the project ID via `FIREBASE_PROJECT_ID`.
5. Add any non-localhost domains under **Authentication → Settings → Authorized domains**.
6. **Restrict the web API key** (recommended, and again after any key rotation). In **Google Cloud
   Console → APIs & Services → Credentials**, open the key and set:
   - **Application restrictions → Websites (HTTP referrers)**: your origins only —
     `localhost:5173/*`, `<project>.firebaseapp.com/*`, `<project>.web.app/*`, and your custom
     domain when it exists.
   - **API restrictions → Restrict key**: Identity Toolkit API + Token Service API + Firebase
     Installations API, plus any other product APIs you use (Firestore / Storage / FCM / Analytics).
     Over-restricting silently breaks Firebase — add only what you use.

   A Firebase web API key is a public client identifier, not a secret, so this caps abuse rather
   than hiding the key; the real enforcement is **Firebase App Check** + **Security Rules**. See
   `DEPLOYMENT_GCP.md` §7 ("Harden the Firebase web API key") for the full checklist.

---

_Generated as a manual testing runbook for the Skopeo project._
