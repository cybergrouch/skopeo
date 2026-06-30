# Local Demo & Test Data

How to run Skopeo **locally** with seedable test data — for demos and exploratory testing — without
touching the LIVE deployment. Everything here uses the **local** Postgres (Docker) and a **local** API
build, so production Cloud SQL data stays untouched.

> **What "local" isolates (and what it doesn't):** the local API writes to the **local** Dockerized
> Postgres, so all app/profile/match data is local-only — LIVE Cloud SQL is never touched. The test
> users are created in the **shared Firebase Auth project** (`web/.env.local` points at it), so their
> *auth accounts* are visible project-wide; their *app data* is local-only. `deleteTestUsers.sh`
> removes those Firebase accounts again, so run it to clean up.

## Prerequisites
- Docker (for Postgres), JDK 21 toolchain, Node (for the web), `jq`, `gh` optional.
- `web/.env.local` present (Firebase client config + `VITE_FIREBASE_API_KEY`) — the test scripts read
  the Firebase Web API key from it.

## Reset & seed sequence

### 1. Reset the local database (clean slate)
Wipes the `postgres_data` volume so the DB starts empty (the API re-applies Flyway V1 on startup):
```bash
docker compose down -v          # stops containers + removes the postgres volume
docker compose up -d postgres   # start a fresh, empty Postgres (localhost:5432, SkopeoDb, postgres/postgres)
```

### 2. Delete any leftover test users
Clears the roster's **Firebase Auth** accounts (they survive a DB reset since Firebase is external) and
any matching DB rows. After a fresh reset the DB rows don't exist yet — that's fine; the script
continues and still removes the Firebase accounts.
```bash
./scripts/testing/deleteTestUsers.sh
```

### 3. Run the API
Flyway applies V1 to the empty DB on startup. Uses `application.yaml` defaults (localhost Postgres,
`FIREBASE_PROJECT_ID` = the dev/prod Firebase project so web tokens verify).
```bash
./gradlew run
# To act as ADMINISTRATOR locally, set the verified-email allowlist (use your real, verified email):
ADMIN_EMAILS=you@example.com ./gradlew run
```
(`./scripts/start-server.sh` does the same with a port-8080-in-use guard.)

### 4. Check API health
```bash
curl -i http://localhost:8080/health
# {"status":"UP","service":"Skopeo API","version":"0.0.1-SNAPSHOT"}
```

### 5. Run the web app
Vite dev server on http://localhost:5173; it **proxies `/api` → localhost:8080** (see `vite.config.ts`),
so the local web talks to the local API with no CORS setup.
```bash
cd web
npm install      # first time only
npm run dev
```

### 6. Create the test users
Seeds the roster in `scripts/testing/_testUserRoster.sh` (Firebase accounts + local DB profiles),
including deliberate near-duplicate names for search/disambiguation testing.
```bash
./scripts/testing/createTestUsers.sh
```

## Notes
- **Order:** delete (step 2) is safe before the API is up — its DB deletes are a harmless no-op on the
  freshly-wiped DB and its real job there is clearing leftover Firebase accounts. To delete test data
  *without* a full reset, just run delete then create with the API running.
- **Refresh test data only (no reset):** `./scripts/testing/deleteTestUsers.sh && ./scripts/testing/createTestUsers.sh`.
- **Single user:** `scripts/testing/createTestUser.sh` / `deleteTestUser.sh` operate on one
  `email password "Full Name" Sex YYYY-MM-DD` (see each script's `--help`).
- **Demoing LIVE read-only:** just browse https://skopeo.co — don't run the create script against it
  (the scripts default to `http://localhost:8080`; never pass the production URL if you want LIVE clean).
- pgAdmin (optional): `docker compose --profile tools up -d pgadmin` → http://localhost:5050.
