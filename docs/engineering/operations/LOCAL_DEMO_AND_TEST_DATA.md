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

## Alternate: run the API in Docker (instead of `./gradlew run`)

`docker-compose.yml` includes a `skopeo` service that builds the production `Dockerfile` and runs the
API in a container next to Postgres — closer to what ships to Cloud Run. Use this instead of step 3.

```bash
# 1. (Reset, if you want a clean slate)
docker compose down -v

# 2. Build the API image and start it + Postgres (depends_on brings Postgres up first).
#    The container connects to the `postgres` service over the compose network
#    (DATABASE_URL=jdbc:postgresql://postgres:5432/SkopeoDb) and Flyway migrates V1 on startup.
docker compose up -d --build skopeo

# 3. Watch it boot / confirm Flyway ran, then health-check (port 8080 is published to the host):
docker compose logs -f skopeo      # Ctrl-C once you see "started successfully"
curl -i http://localhost:8080/health
```

Then continue with the web (step 5) and test users (steps 2 & 6) exactly as above — the test scripts
hit `http://localhost:8080` (published by the container) and `deleteTestUsers.sh` uses
`docker compose exec postgres`, so both work unchanged.

Docker-specific notes:
- **Order:** with this option the API + DB come up together and migrate, so run `deleteTestUsers.sh`
  *after* `docker compose up` (the tables exist), then `createTestUsers.sh`.
- **Rebuild after code changes:** `docker compose up -d --build skopeo` (re-runs the image build).
- **Admin locally:** the compose `skopeo` service doesn't set `ADMIN_EMAILS`. To act as ADMINISTRATOR,
  add `- ADMIN_EMAILS=you@example.com` under its `environment:` and rebuild — or just use `./gradlew run`
  with the env var for admin sessions.
- **Stop:** `docker compose down` (keep data) or `docker compose down -v` (wipe the DB volume).
- **vs `./gradlew run`:** Docker builds the full image (slower first build) and the DB host is
  `postgres` (compose network), not `localhost`; `./gradlew run` is faster to iterate and connects to
  `localhost:5432`. Both serve the API on `localhost:8080`.

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
