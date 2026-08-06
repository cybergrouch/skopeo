# Scripts Directory

Utility scripts for running and testing the Skopeo API.

## Available Scripts

### 🚀 Server Management

#### `start-server.sh`
Start the Skopeo API server.
- Checks if port 8080 is already in use
- Offers to kill existing process if needed
- Starts the server using Gradle

**Usage:**
```bash
./scripts/start-server.sh
```

#### `stop-server.sh`
Stop the Skopeo API server.
- Finds processes using port 8080
- Safely terminates the server

**Usage:**
```bash
./scripts/stop-server.sh
```

---

### 🧪 Testing

#### `test-api.sh`
Automated test suite for all API endpoints.
- Tests root endpoint
- Tests health endpoint
- Checks response times
- Validates HTTP status codes

**Usage:**
```bash
./scripts/test-api.sh
```

**Sample Output:**
```
🎾 Testing Skopeo API...
================================

✅ Server is running

1️⃣  Testing ROOT endpoint (GET /):
   Response: Skopeo API
   Status: 200
   ✅ PASSED
...
```

#### `test-partner-api.sh`
End-to-end test + living documentation for third-party (partner) **API-key** access (#225/#596/#597/#598/#603, issue #695). Provisions a partner client + key, then exercises the partner endpoints and prints the exact method / URL / headers / body of each request. Proves a third party authenticates with the `X-Api-Key` header alone (no Firebase bearer), and that user-oriented `/me` endpoints reject an API-key-only request. Bootstrap (create client + issue key) is ADMINISTRATOR-only, so supply an admin Firebase token (see `grant-admin-local.sh`).

**Usage:**
```bash
# paste an admin token (browser → DevTools → Network → Authorization: Bearer <jwt>)
ADMIN_TOKEN=<firebase-id-token-of-an-admin> ./scripts/test-partner-api.sh

# …or mint tokens via the Firebase REST API, and enable the delegated 200 case:
WEB_API_KEY=AIza… ADMIN_EMAIL=admin@skopeo.dev ADMIN_PASSWORD=… \
  USER_EMAIL=player@skopeo.dev USER_PASSWORD=… ./scripts/test-partner-api.sh
```
Config: `BASE_URL` (default `http://localhost:8080`), `ADMIN_TOKEN`, `USER_TOKEN`, `WEB_API_KEY`, `ADMIN_EMAIL`/`ADMIN_PASSWORD`, `USER_EMAIL`/`USER_PASSWORD`, `KEEP=1` (skip cleanup). Requires `curl`, `jq`, and a running server.

---

### 🐳 Docker

#### `docker-build.sh`
Build and tag Docker images for Skopeo.
- Builds multi-stage Docker image
- Tags with specified version
- Also tags as 'latest'
- Shows image size after build

**Usage:**
```bash
# Build with version tag
./scripts/docker-build.sh 1.0.0

# Build as latest only
./scripts/docker-build.sh
```

**Sample Output:**
```
======================================
  Building Docker Image
======================================

Image: skopeo:latest
Dockerfile: ./Dockerfile

Building image...
[Docker build output...]

Tagging as latest...

======================================
  Build Complete!
======================================

Image: skopeo:latest
Size: 215MB

To run the container:
  docker run -p 8080:8080 skopeo:latest
```

---

### 🎨 Code Quality

#### `check-coverage.sh`
Run code coverage analysis and verify the local threshold.

> **Note on thresholds.** This helper applies a stricter **local bar of 85%** to both line and branch coverage as an aspirational target. The **authoritative CI gate** enforced by the build is **75% line / 70% branch** (`./gradlew jacocoTestCoverageVerification`, configured in `build.gradle.kts`). A run can pass CI while this helper still flags room to improve.

- Executes all tests with JaCoCo instrumentation
- Generates coverage reports (HTML, XML, CSV)
- Parses coverage metrics for instructions, branches, lines
- Checks if coverage meets 85% threshold
- Displays detailed coverage breakdown
- Exits with success/failure status

**Usage:**
```bash
./scripts/check-coverage.sh
```

**Sample Output:**
```
==========================================
  Code Coverage Check
==========================================

Threshold: 85%
Project: <your-repo-root>

Step 1: Running tests with coverage...
✓ Tests completed successfully

Step 2: Checking for coverage reports...
✓ Coverage reports generated
  - HTML: build/reports/jacoco/test/html/index.html
  - XML:  build/reports/jacoco/test/jacocoTestReport.xml
  - CSV:  build/reports/jacoco/test/jacocoTestReport.csv

Step 3: Parsing coverage metrics...

Coverage Breakdown:
─────────────────────────────────────────
  Instructions:    71.42%
  Branches:        71.42%
  Lines:           71.42%
  Complexity:      71.42%
  Methods:         71.42%
  Classes:         71.42%
─────────────────────────────────────────

Step 4: Checking coverage against threshold (85%)...
✗ Line coverage: 71.42% < 85%
✗ Branch coverage: 71.42% < 85%

==========================================
  Summary
==========================================

✗ FAILED: Coverage below 85% threshold

View detailed report:
  open build/reports/jacoco/test/html/index.html

To improve coverage:
  1. Add unit tests for uncovered code
  2. Add integration tests for API endpoints
  3. Add edge case tests for boundary conditions
```

**When to use:**
- Before merging pull requests
- As part of CI/CD pipeline
- To verify test coverage goals
- After adding new features

**Exit codes:**
- `0`: Coverage meets or exceeds 85% threshold
- `1`: Coverage below threshold or tests failed

---

#### `format-code.sh`
Auto-format all Kotlin code using ktlint.
- Applies opinionated formatting rules
- Fixes style violations automatically
- Ensures consistent code style
- Compatible with ktlint linting rules

**Usage:**
```bash
./scripts/format-code.sh
```

**Sample Output:**
```
======================================
  🎨 Formatting Code with ktlint
======================================

Formatting Kotlin code...
[ktlint output...]

======================================
  ✅ Formatting Complete!
======================================

Next steps:
  1. Review changes: git diff
  2. Verify format: ./gradlew ktlintCheck
  3. Run tests: ./gradlew test
```

**When to use:**
- Before committing code
- After writing new features
- To fix ktlint violations automatically
- To ensure consistent code style across the team

---

### 💾 Database Backup & Restore

See [database-setup.md → Backup and Restore](../docs/engineering/operations/database-setup.md#backup-and-restore) for the full runbook (managed Cloud SQL backups + PITR, portability, PII handling). ⚠️ Production dumps contain real personal data — keep them in the backup bucket or a local temp file only.

The production backup bucket is **`gs://skopeo-prod-db-backups`** (project `skopeo-prod`) — substitute it for `gs://<backup-bucket>` below.

#### `backup-db.sh`
Portable logical backup of the production database to GCS (Cloud SQL export). Complements Cloud SQL's managed daily backups; this artifact is engine-restorable (off-GCP) and feeds `restore-prod-to-local.sh`.

**Usage:**
```bash
BACKUP_BUCKET=gs://<backup-bucket> ./scripts/backup-db.sh
```

#### `schedule-backup.sh`
One-time setup to automate `backup-db.sh` via Cloud Scheduler (creates a versioned bucket, the IAM grant, and a weekly job).

**Usage:**
```bash
BACKUP_BUCKET=gs://<backup-bucket> \
SCHEDULER_SA=<sa>@skopeo-prod.iam.gserviceaccount.com \
./scripts/schedule-backup.sh
```

#### `backup-firebase-auth.sh`
Portable backup of Firebase Auth users to GCS (users are keyed by `firebase_uid`, so a DB dump alone isn't a complete restore). Automated weekly by `.github/workflows/firebase-auth-backup.yml`. ⚠️ Contains password hashes + PII.

**Usage:**
```bash
FIREBASE_PROJECT=<firebase-project-id> BACKUP_BUCKET=gs://<backup-bucket> ./scripts/backup-firebase-auth.sh
```

#### `restore-prod-to-local.sh`
Restore the latest production backup into a **throwaway** local database (`skopeo_prodcopy`) for debugging — never touches your dev `SkopeoDb`. Prompts before pulling real data.

**Usage:**
```bash
BACKUP_BUCKET=gs://<backup-bucket> ./scripts/restore-prod-to-local.sh
DATABASE_URL=jdbc:postgresql://localhost:5432/skopeo_prodcopy ./gradlew run
```

#### `grant-admin-local.sh`
Grant `ADMINISTRATOR` to a user in a **local** database (default `skopeo_prodcopy`) so you can exercise admin flows when debugging against a restored production copy where your identity isn't an admin. ⚠️ Local only — refuses to touch the dev `SkopeoDb`. Effective on the next request (capabilities are read per-request).

**Usage:**
```bash
./scripts/grant-admin-local.sh                       # list users (provider_uid, id, name, capabilities)
./scripts/grant-admin-local.sh <provider_uid-or-id>  # grant ADMINISTRATOR
# Adopt an account as your local login (repoint its firebase_uid) + grant admin — needed on a prod copy,
# whose stored prod Firebase uids won't match your local dev-project login:
./scripts/grant-admin-local.sh --adopt <local-firebase-uid> <target-provider_uid-or-id>
```

#### `health-check.sh`
Health/smoke check against a running API (default `http://localhost:8080`). Doubles as the "restore verified" step — samples row counts from the restored db.

**Usage:**
```bash
./scripts/health-check.sh [BASE_URL]
```

---

### 🧹 One-off data cleanup

#### `cleanup/remove-award-points.sh` (#576)
One-off: remove the ranking-point awards created by finalizing events during the **testing phase**, when awarding should not yet have counted. Hard-deletes the finalize-generated award rows (`ranking_point_awards` where `source_type = 'INTERNAL' AND event_id IS NOT NULL`) across **all** events; manual admin adjustments (#469 — `EXTERNAL`, event-less) are left untouched. Defaults to a **dry run** (preview counts only); `--apply` deletes (with a typed confirmation). Connection-agnostic — point it at a restored copy first, then prod. ⚠️ Deletes real data: back up first (`backup-db.sh`) and dry-run on a restored copy (`restore-prod-to-local.sh`). After applying on prod, recompute standings.

**Usage:**
```bash
# Dry run against the local restored copy (default connection):
PGPASSWORD=postgres ./scripts/cleanup/remove-award-points.sh
# Dry run against an explicit connection:
./scripts/cleanup/remove-award-points.sh "postgresql://postgres@localhost:5432/skopeo_prodcopy"
# Apply (prompts to confirm; --yes skips the prompt):
./scripts/cleanup/remove-award-points.sh --apply "postgresql://user@host:5432/dbname"
```
The runner is the safe entry point; `cleanup/remove-award-points.sql` is the underlying apply step (markers-first delete, idempotent) if you prefer to run it directly in `psql`.

---

### 📚 Reference

#### `rating-delta-table.py`
Explainer/what-if tool that prints a table of rating deltas for a set of scores, given two players' ratings — reproducing the v2 per-set calculation (dominance + gap/scale + K + sign) documented in [`RATING_CALCULATION_ALGORITHM.md`](../docs/product/RATING_CALCULATION_ALGORITHM.md). Floats, not the server's BigDecimal engine, so treat it as illustrative. Supports `independent` (each score its own single-set match from the same start) and `sequential` (scores as consecutive sets of one match → net delta) modes, `--markdown` output, and overridable constants (`--k`, `--threshold`, `--match-type`/`--mtf`, …).

**Usage:**
```bash
./scripts/rating-delta-table.py --rating-a 3.243325 --rating-b 3.266000 \
    --name-a Tin --name-b Razel --match-type OPEN_PLAY --markdown \
    --scores 6-6,6-4,6-1,6-0,5-6,0-6
./scripts/rating-delta-table.py --help
```

#### `curl-examples.sh`
Collection of useful cURL commands and examples.
- Basic GET requests
- POST examples for future endpoints
- Testing tips and tricks
- HTTPie alternatives

**Usage:**
```bash
./scripts/curl-examples.sh
```

---

## Quick Start

1. **Make scripts executable:**
   ```bash
   chmod +x scripts/*.sh
   ```

2. **Start the server:**
   ```bash
   ./scripts/start-server.sh
   ```

3. **In a new terminal, test the API:**
   ```bash
   ./scripts/test-api.sh
   ```

4. **When done, stop the server:**
   ```bash
   ./scripts/stop-server.sh
   ```

---

## Manual Testing

Open your browser and navigate to:
- Root: http://localhost:8080/
- Health: http://localhost:8080/health

Or use curl:
```bash
curl http://localhost:8080/health
```

---

## Notes

- All scripts assume the server runs on `http://localhost:8080`
- The `test-api.sh` script will fail if the server is not running
- Use `start-server.sh` to automatically handle port conflicts
