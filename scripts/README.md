# Scripts Directory

Utility scripts for running and testing the Tennis Levelr API.

## Available Scripts

### 🚀 Server Management

#### `start-server.sh`
Start the Tennis Levelr API server.
- Checks if port 8080 is already in use
- Offers to kill existing process if needed
- Starts the server using Gradle

**Usage:**
```bash
./scripts/start-server.sh
```

#### `stop-server.sh`
Stop the Tennis Levelr API server.
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
🎾 Testing Tennis Levelr API...
================================

✅ Server is running

1️⃣  Testing ROOT endpoint (GET /):
   Response: Tennis Levelr API
   Status: 200
   ✅ PASSED
...
```

---

### 🐳 Docker

#### `docker-build.sh`
Build and tag Docker images for Tennis Levelr.
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

Image: tennis-levelr:1.0.0
Dockerfile: ./Dockerfile

Building image...
[Docker build output...]

Tagging as latest...

======================================
  Build Complete!
======================================

Image: tennis-levelr:1.0.0
Size: 215MB

To run the container:
  docker run -p 8080:8080 tennis-levelr:1.0.0
```

---

### 🎨 Code Quality

#### `check-coverage.sh`
Run code coverage analysis and verify 85% threshold.
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
Project: /Users/lange/Repositories/kotlin/tennis_levelr

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

#### `health-check.sh`
Health/smoke check against a running API (default `http://localhost:8080`). Doubles as the "restore verified" step — samples row counts from the restored db.

**Usage:**
```bash
./scripts/health-check.sh [BASE_URL]
```

---

### 📚 Reference

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
