# Database Setup Guide

This guide explains how to set up and manage the PostgreSQL database for Skopeo.

## Quick Start (Local Development)

### 1. Start PostgreSQL with Docker Compose

The easiest way to run PostgreSQL locally is using Docker Compose:

```bash
# Start PostgreSQL only
docker compose up postgres -d

# Or start PostgreSQL with pgAdmin (database management UI)
docker compose --profile tools up postgres pgadmin -d
```

This will:
- Start PostgreSQL 15 on `localhost:5432`
- Create database `SkopeoDb`
- Use credentials: `postgres/postgres`
- Optionally start pgAdmin on `http://localhost:5050`

### 2. Run the Application

The application will automatically:
1. Connect to the database using configuration from `application.yaml`
2. Run Flyway migrations to create all tables
3. Start accepting requests

```bash
./gradlew run
```

### 3. Verify Database Setup

Check that migrations ran successfully:

```bash
# Connect to PostgreSQL
docker exec -it SkopeoDb_db psql -U postgres -d SkopeoDb

# List all tables
\dt

# Check migration history
SELECT * FROM flyway_schema_history;

# Exit psql
\q
```

Expected tables:
- `users`
- `user_kyc`
- `user_names`
- `user_identities`
- `contact_information`
- `user_capabilities`
- `user_ratings`
- `user_rating_history`
- `teams`
- `team_users`
- `matches`
- `match_sets`
- `match_set_tiebreaks`
- `invites`
- `audit_log`
- `duplicate_candidates`
- `events`
- `event_participants`
- `rating_requests`
- `player_lists`
- `player_list_members`
- `seedings`
- `seeding_entries`

---

## Database Configuration

### Environment Variables

The application reads database configuration from environment variables or `application.yaml`:

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/SkopeoDb` | JDBC connection URL |
| `DATABASE_USER` | `postgres` | Database username |
| `DATABASE_PASSWORD` | `postgres` | Database password |

### Connection Pool Settings

HikariCP connection pool is configured in `application.yaml`:

```yaml
database:
  pool:
    maximumPoolSize: 10      # Maximum connections in pool
    minimumIdle: 2            # Minimum idle connections
    connectionTimeout: 30000  # 30 seconds
    idleTimeout: 600000       # 10 minutes
    maxLifetime: 1800000      # 30 minutes
```

---

## Database Migrations

Migrations are managed by [Flyway](https://flywaydb.org/) (the `flyway-core` library, run automatically on application startup via `DatabaseConfig.init`).

> **Note:** only the Flyway *Gradle plugin* was removed (it broke on Gradle 9 and is unmaintained). The Flyway engine itself — versioned migrations, history tracking, startup application — is fully in place. Manual/ad-hoc runs use the Flyway CLI Docker image (see [Running Migrations](#running-migrations)).

### Why Flyway (and not Liquibase)

Flyway is **SQL-first**: each migration is a plain, versioned `V__*.sql` file containing the exact SQL that runs. Liquibase is changelog-based (XML/YAML/JSON/SQL) with a database-agnostic abstraction layer. For Skopeo, Flyway is the better fit:

- **Single database, Postgres-specific DDL.** The schema leans on Postgres features — partial unique indexes (e.g. `… WHERE verification_status = 'VERIFIED'`), `JSONB`, triggers, regex `CHECK`s, `uuid-ossp`. These are trivial in raw SQL but awkward in Liquibase's abstract changelogs. Liquibase's main advantage — cross-database portability — gives us nothing while we're committed to Postgres and *want* its features.
- **The SQL is the source of truth and runs anywhere.** Migrations can be applied straight through `psql` or the Flyway CLI with no tooling translation — exactly how new migrations are validated here.
- **Low overhead for a solo dev / pilot.** "Write SQL, version it, apply forward" is the simplest model.

**When we'd reconsider Liquibase:** if we later need automatic rollbacks/down-migrations (Flyway's `undo` is a paid feature; Liquibase OSS includes rollback), must support multiple database engines, or want richer change-tracking (contexts, preconditions, diff-based generation). For now, forward-only migrations on a single Postgres are the right call.

### Migration Files

Located in: `src/main/resources/db/migration/`

**Naming Convention:** `V{version}__{description}.sql`

The schema currently lives in a **single consolidated baseline**:
- `V1__create_initial_schema.sql` — the entire schema (users, names, identities, contacts,
  capabilities, KYC, ratings + history, teams, matches/sets/tiebreaks, events, seeding,
  duplicate detection, rating requests, audit log, invites)

The earlier incremental migrations (the former V2–V12) were folded back into V1 while the project
is **pre-production** — no persistent database has ever been provisioned, so collapsing them is
safe. Once a production database exists this file is **frozen**: never edit an applied migration;
the next change is a new `V2__...sql` (see [Creating New Migrations](#creating-new-migrations)).

### Running Migrations

**Normally nothing is run manually** — the app applies migrations at startup via
`flyway-core` (`DatabaseConfig.init`), so `./gradlew run`, `docker-compose up`, and
Cloud Run deploys all migrate automatically.

> The Flyway **Gradle plugin** is intentionally not used (it depends on
> `JavaPluginConvention`, removed in Gradle 9, and is unmaintained). For manual/ad-hoc
> runs use the **Flyway CLI Docker image** — no local install, version-pinned:

```bash
docker run --rm \
  -v "$(pwd)/src/main/resources/db/migration:/flyway/sql:ro" \
  flyway/flyway:10 \
  -url="jdbc:postgresql://host.docker.internal:5432/SkopeoDb" \
  -user=postgres -password=postgres \
  info        # or: migrate | validate | clean

# Against Cloud SQL: run the Cloud SQL Auth Proxy and point -url at it.
```

### Creating New Migrations

1. Create a new file in `src/main/resources/db/migration/`
2. Follow naming convention: `V{next_version}__{description}.sql`
3. Write SQL DDL statements
4. Test by starting the app (it migrates on startup) or with the Flyway CLI Docker `migrate` command above

Example:

```sql
-- V2__add_user_preferences.sql
CREATE TABLE user_preferences (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL,
    notification_enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_preferences_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_preferences_user ON user_preferences(user_id);
```

---

## Database Management Tools

### Option 1: pgAdmin (Web UI)

Start pgAdmin with Docker Compose:

```bash
docker compose --profile tools up pgadmin -d
```

Access at: `http://localhost:5050`
- Email: `admin@skopeo.com`
- Password: `admin`

**Add Server Connection:**
1. Right-click "Servers" → "Register" → "Server"
2. Name: `Skopeo Local`
3. Connection tab:
   - Host: `postgres` (container name)
   - Port: `5432`
   - Database: `SkopeoDb`
   - Username: `postgres`
   - Password: `postgres`

### Option 2: psql (Command Line)

Connect to PostgreSQL directly:

```bash
# Using Docker exec
docker exec -it SkopeoDb_db psql -U postgres -d SkopeoDb

# Or if PostgreSQL is installed locally
psql -h localhost -U postgres -d SkopeoDb
```

**Useful psql Commands:**

```sql
-- List all tables
\dt

-- Describe table structure
\d users

-- List all indexes
\di

-- Show table sizes
\dt+

-- Run SQL query
SELECT id, city, country FROM users LIMIT 10;

-- Export query results to CSV
\copy (SELECT * FROM users) TO '/tmp/users.csv' WITH CSV HEADER;

-- Exit
\q
```

### Option 3: DBeaver / DataGrip

Any PostgreSQL-compatible GUI client can connect using:

```
Host: localhost
Port: 5432
Database: SkopeoDb
Username: postgres
Password: postgres
```

---

## Sample Queries

### Get All Users with Their Ratings

```sql
SELECT
    n.value AS display_name,
    ur.current_rating,
    ur.current_level,
    ur.matches_played,
    ur.confidence_score,
    ur.last_match_date
FROM users u
JOIN user_names n    ON n.user_id = u.id AND n.is_primary
LEFT JOIN user_ratings ur ON ur.user_id = u.id
WHERE u.is_active = true
ORDER BY ur.current_rating DESC;
```

### Get User Match History

```sql
SELECT
    m.match_date,
    t1.name as team1,
    t2.name as team2,
    CASE
        WHEN m.winner_team_id = t1.id THEN t1.name
        ELSE t2.name
    END as winner,
    string_agg(
        ms.team1_games || '-' || ms.team2_games,
        ', ' ORDER BY ms.set_number
    ) as score
FROM matches m
JOIN teams t1 ON m.team1_id = t1.id
JOIN teams t2 ON m.team2_id = t2.id
JOIN match_sets ms ON m.id = ms.match_id
JOIN team_users tu ON tu.team_id IN (t1.id, t2.id)
WHERE tu.user_id = '<user-uuid-here>'
    AND tu.left_at IS NULL
GROUP BY m.id, m.match_date, t1.name, t2.name, m.winner_team_id
ORDER BY m.match_date DESC;
```

### Calculate Rating Confidence Decay

```sql
-- Update confidence scores based on last match date
UPDATE user_ratings
SET confidence_score = GREATEST(
    0.0,
    1.0 - (EXTRACT(EPOCH FROM (CURRENT_DATE - last_match_date)) / (365.0 * 86400.0))
)
WHERE last_match_date < CURRENT_DATE;
```

### Top-Ranked Users (Seeding List)

```sql
SELECT
    n.value AS display_name,
    ur.current_rating,
    ur.current_level,
    ur.matches_played,
    ur.confidence_score,
    ur.last_match_date,
    ROW_NUMBER() OVER (
        ORDER BY ur.current_rating DESC, ur.confidence_score DESC
    ) as seed
FROM users u
JOIN user_names n   ON n.user_id = u.id AND n.is_primary
JOIN user_ratings ur ON ur.user_id = u.id
WHERE u.is_active = true
    AND ur.last_match_date > CURRENT_DATE - INTERVAL '180 days'
ORDER BY ur.current_rating DESC, ur.confidence_score DESC
LIMIT 64;
```

---

## Troubleshooting

### Connection Refused Error

**Problem:** Application can't connect to PostgreSQL

**Solution:**
1. Check if PostgreSQL is running:
   ```bash
   docker ps | grep postgres
   ```

2. If not running, start it:
   ```bash
   docker compose up postgres -d
   ```

3. Check logs:
   ```bash
   docker logs SkopeoDb_db
   ```

### Migration Failed Error

**Problem:** Flyway migration fails on startup

**Solution** (using the Flyway CLI Docker image — see [Running Migrations](#running-migrations) for the full `docker run` invocation; substitute the final word):

1. Check status: `... info`
2. If a migration is marked failed, repair the history: `... repair`
3. Re-run: `... migrate`
4. For development, clean and start fresh — **CAUTION: deletes all data**: `... clean` then `... migrate`. (Locally you can instead just drop/recreate the dev database, e.g. `docker exec skopeo_db psql -U postgres -c 'DROP DATABASE SkopeoDb' -c 'CREATE DATABASE SkopeoDb'`, then restart the app.)

### Port 5432 Already in Use

**Problem:** Another PostgreSQL instance is using port 5432

**Solution:**

Option 1: Stop the other PostgreSQL instance

Option 2: Change the port in `docker-compose.yml`:
```yaml
postgres:
  ports:
    - "5433:5432"  # Use 5433 on host
```

Then update `DATABASE_URL` in `application.yaml`:
```yaml
database:
  url: jdbc:postgresql://localhost:5433/SkopeoDb
```

### Reset Database Completely

To start fresh with a clean database:

```bash
# Stop and remove containers
docker compose down

# Remove PostgreSQL volume (deletes all data)
docker volume rm SkopeoDb_postgres_data

# Start fresh
docker compose up postgres -d

# Migrations will run automatically on next application start
./gradlew run
```

---

## Production Deployment

### Database Setup Checklist

- [ ] Use managed PostgreSQL service (AWS RDS, Azure Database, Google Cloud SQL)
- [ ] Set strong passwords for database users
- [ ] Enable SSL/TLS for connections
- [ ] Configure automated backups
- [ ] Set up connection pooling appropriately
- [ ] Monitor slow queries and optimize indexes
- [ ] Enable query logging for debugging
- [ ] Set up read replicas for high traffic
- [ ] Configure firewall rules to restrict database access

### Environment Variables

Set these in your production environment:

```bash
export DATABASE_URL="jdbc:postgresql://prod-db-host:5432/SkopeoDb"
export DATABASE_USER="prod_user"
export DATABASE_PASSWORD="<strong-password>"
```

### Migration Strategy

For production deployments:

1. **Test migrations in staging first**
2. **Backup database before running migrations**
3. **Use blue-green deployment for zero-downtime updates**
4. **Monitor application logs during migration**

---

## Backup and Restore

Two complementary layers:

- **Managed (Cloud SQL)** — automated daily backups + point-in-time recovery (PITR). Fast in-place
  disaster recovery, but restorable **only back into Cloud SQL**.
- **Portable (logical export)** — a `pg_dump`-style dump to GCS. Engine-restorable: this is what
  moves to another database/provider, and what you pull into a local db to debug a production issue.

The schema itself is reproducible from the Flyway migrations in `src/main/resources/db/migration/`,
so a clean migration to another PostgreSQL is "run Flyway to build the schema, then load a data dump."

> ⚠️ Every production dump contains **real personal data** (emails, dates of birth, Firebase UIDs).
> Store it only in the access-controlled backup bucket or a local temp file, never in the repo or
> `presentations/`, and delete local copies when done.

### Production: managed backups (Cloud SQL)

Verify the instance is protected:

```bash
gcloud sql instances describe skopeo-db --project skopeo-prod \
  --format="value(settings.backupConfiguration.enabled, \
settings.backupConfiguration.pointInTimeRecoveryEnabled, \
settings.deletionProtectionEnabled)"
# expect: True  True  True
```

Enable/repair if any are off (one patch; PITR archiving is online for PostgreSQL, no restart):

```bash
gcloud sql instances patch skopeo-db --project skopeo-prod \
  --backup-start-time=18:00 \            # UTC → 02:00 PHT (low traffic)
  --enable-point-in-time-recovery \
  --retained-backups-count=30 \
  --retained-transaction-log-days=7 \
  --deletion-protection
```

Take an on-demand backup (also bootstraps the base backup PITR needs):

```bash
gcloud sql backups create --instance=skopeo-db --project skopeo-prod
```

PITR restore (clones to a **new** instance at a timestamp — the original is untouched):

```bash
gcloud sql instances clone skopeo-db skopeo-db-pitr --project skopeo-prod \
  --point-in-time '2026-07-14T09:00:00Z'
```

### Production: portable logical backups → GCS

On-demand (Cloud SQL export → GCS; `--offload` keeps prod unloaded):

```bash
BACKUP_BUCKET=gs://<backup-bucket> ./scripts/backup-db.sh
```

Automated (one-time setup — creates a versioned bucket, grants the Cloud SQL service account write,
and a Cloud Scheduler job). Managed backups cover daily DR, so the logical export defaults to
**weekly**:

```bash
BACKUP_BUCKET=gs://<backup-bucket> \
SCHEDULER_SA=<sa>@skopeo-prod.iam.gserviceaccount.com \
./scripts/schedule-backup.sh
```

(GitHub Actions on a `schedule:` cron calling `gcloud sql export` is a reasonable alternative if you
prefer the backup job to live with CI — it just needs GCP auth via Workload Identity Federation.)

### Production → local (for debugging)

Restore the latest production export into a throwaway local database, then run the app against it:

```bash
BACKUP_BUCKET=gs://<backup-bucket> ./scripts/restore-prod-to-local.sh
# → restores into db 'skopeo_prodcopy' (never touches your dev 'SkopeoDb')

DATABASE_URL=jdbc:postgresql://localhost:5432/skopeo_prodcopy ./gradlew run
./scripts/health-check.sh        # verifies /health UP + samples row counts
```

The dump includes `flyway_schema_history`, so the app won't re-migrate; if your local code has newer
migrations than production, Flyway applies just those on startup — usually exactly what you want when
reproducing a bug against an about-to-ship change.

### Local backups (docker)

```bash
# Backup the local dev database
docker exec skopeo_db pg_dump -U postgres SkopeoDb | gzip > backup.sql.gz

# Restore it
gunzip -c backup.sql.gz | docker exec -i skopeo_db psql -U postgres -d SkopeoDb

# Specific tables only
docker exec skopeo_db pg_dump -U postgres -t users -t user_ratings SkopeoDb > users_backup.sql
```

> **Don't forget Firebase Auth.** Users are keyed by `firebase_uid`; the Postgres rows are incomplete
> without the matching identities. A full migration/DR backup must also include a
> `firebase auth:export` (store it alongside the DB dumps in the backup bucket).

---

## Next Steps

Once the database is set up, you can:

1. **Create repository interfaces** - See `docs/repository-implementation.md` (coming soon)
2. **Add API endpoints** - Implement CRUD operations for users, matches, etc.
3. **Write integration tests** - Test database interactions
4. **Set up monitoring** - Track query performance and connection pool metrics

For schema details and table relationships, see: [Database Schema Documentation](../architecture/database-schema.md)
