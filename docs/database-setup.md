# Database Setup Guide

This guide explains how to set up and manage the PostgreSQL database for Tennis Levelr.

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
- Create database `tennis_levelr`
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
docker exec -it tennis_levelr_db psql -U postgres -d tennis_levelr

# List all tables
\dt

# Check migration history
SELECT * FROM flyway_schema_history;

# Exit psql
\q
```

Expected tables:
- `players`
- `player_kyc`
- `player_ratings`
- `player_rating_history`
- `teams`
- `team_players`
- `matches`
- `match_sets`
- `match_set_tiebreaks`

---

## Database Configuration

### Environment Variables

The application reads database configuration from environment variables or `application.yaml`:

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/tennis_levelr` | JDBC connection URL |
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

Migrations are managed by [Flyway](https://flywaydb.org/) and run automatically on application startup.

### Migration Files

Located in: `src/main/resources/db/migration/`

**Naming Convention:** `V{version}__{description}.sql`

Example:
- `V1__create_initial_schema.sql` - Initial schema
- `V2__add_social_media_table.sql` - Add social media verification

### Running Migrations Manually

Use Gradle tasks for manual migration management:

```bash
# Show migration info
./gradlew flywayInfo

# Run pending migrations
./gradlew flywayMigrate

# Validate applied migrations
./gradlew flywayValidate

# Clean database (CAUTION: Deletes all data!)
./gradlew flywayClean
```

### Creating New Migrations

1. Create a new file in `src/main/resources/db/migration/`
2. Follow naming convention: `V{next_version}__{description}.sql`
3. Write SQL DDL statements
4. Test with `./gradlew flywayMigrate`

Example:

```sql
-- V2__add_player_preferences.sql
CREATE TABLE player_preferences (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    player_id UUID NOT NULL,
    notification_enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_preferences_player
        FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
);

CREATE INDEX idx_preferences_player ON player_preferences(player_id);
```

---

## Database Management Tools

### Option 1: pgAdmin (Web UI)

Start pgAdmin with Docker Compose:

```bash
docker compose --profile tools up pgadmin -d
```

Access at: `http://localhost:5050`
- Email: `admin@tennislevelr.com`
- Password: `admin`

**Add Server Connection:**
1. Right-click "Servers" → "Register" → "Server"
2. Name: `Tennis Levelr Local`
3. Connection tab:
   - Host: `postgres` (container name)
   - Port: `5432`
   - Database: `tennis_levelr`
   - Username: `postgres`
   - Password: `postgres`

### Option 2: psql (Command Line)

Connect to PostgreSQL directly:

```bash
# Using Docker exec
docker exec -it tennis_levelr_db psql -U postgres -d tennis_levelr

# Or if PostgreSQL is installed locally
psql -h localhost -U postgres -d tennis_levelr
```

**Useful psql Commands:**

```sql
-- List all tables
\dt

-- Describe table structure
\d players

-- List all indexes
\di

-- Show table sizes
\dt+

-- Run SQL query
SELECT name, email FROM players LIMIT 10;

-- Export query results to CSV
\copy (SELECT * FROM players) TO '/tmp/players.csv' WITH CSV HEADER;

-- Exit
\q
```

### Option 3: DBeaver / DataGrip

Any PostgreSQL-compatible GUI client can connect using:

```
Host: localhost
Port: 5432
Database: tennis_levelr
Username: postgres
Password: postgres
```

---

## Sample Queries

### Get All Players with Their Ratings

```sql
SELECT
    p.name,
    p.email,
    pr.rating_system,
    pr.current_rating,
    pr.current_level,
    pr.matches_played,
    pr.confidence_score,
    pr.last_match_date
FROM players p
LEFT JOIN player_ratings pr ON p.id = pr.player_id
WHERE p.is_active = true
ORDER BY pr.rating_system, pr.current_rating DESC;
```

### Get Player Match History

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
JOIN team_players tp ON tp.team_id IN (t1.id, t2.id)
WHERE tp.player_id = '<player-uuid-here>'
    AND tp.left_at IS NULL
GROUP BY m.id, m.match_date, t1.name, t2.name, m.winner_team_id
ORDER BY m.match_date DESC;
```

### Calculate Rating Confidence Decay

```sql
-- Update confidence scores based on last match date
UPDATE player_ratings
SET confidence_score = GREATEST(
    0.0,
    1.0 - (EXTRACT(EPOCH FROM (CURRENT_DATE - last_match_date)) / (365.0 * 86400.0))
)
WHERE last_match_date < CURRENT_DATE;
```

### Top-Ranked Players (Seeding List)

```sql
SELECT
    p.name,
    pr.current_rating,
    pr.current_level,
    pr.matches_played,
    pr.confidence_score,
    pr.last_match_date,
    ROW_NUMBER() OVER (
        ORDER BY pr.current_rating DESC, pr.confidence_score DESC
    ) as seed
FROM players p
JOIN player_ratings pr ON p.id = pr.player_id
WHERE pr.rating_system = 'NTRP'
    AND p.is_active = true
    AND pr.last_match_date > CURRENT_DATE - INTERVAL '180 days'
ORDER BY pr.current_rating DESC, pr.confidence_score DESC
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
   docker logs tennis_levelr_db
   ```

### Migration Failed Error

**Problem:** Flyway migration fails on startup

**Solution:**
1. Check Flyway status:
   ```bash
   ./gradlew flywayInfo
   ```

2. If migration is marked as failed, repair it:
   ```bash
   ./gradlew flywayRepair
   ```

3. Re-run migration:
   ```bash
   ./gradlew flywayMigrate
   ```

4. For development, you can clean and start fresh (CAUTION: Deletes all data):
   ```bash
   ./gradlew flywayClean
   ./gradlew flywayMigrate
   ```

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
  url: jdbc:postgresql://localhost:5433/tennis_levelr
```

### Reset Database Completely

To start fresh with a clean database:

```bash
# Stop and remove containers
docker compose down

# Remove PostgreSQL volume (deletes all data)
docker volume rm tennis_levelr_postgres_data

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
export DATABASE_URL="jdbc:postgresql://prod-db-host:5432/tennis_levelr"
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

### Backup Database

```bash
# Backup entire database
docker exec tennis_levelr_db pg_dump -U postgres tennis_levelr > backup.sql

# Backup with compression
docker exec tennis_levelr_db pg_dump -U postgres tennis_levelr | gzip > backup.sql.gz

# Backup specific tables only
docker exec tennis_levelr_db pg_dump -U postgres -t players -t player_ratings tennis_levelr > players_backup.sql
```

### Restore Database

```bash
# Restore from backup
cat backup.sql | docker exec -i tennis_levelr_db psql -U postgres -d tennis_levelr

# Restore from compressed backup
gunzip -c backup.sql.gz | docker exec -i tennis_levelr_db psql -U postgres -d tennis_levelr

# Create new database and restore
docker exec tennis_levelr_db psql -U postgres -c "CREATE DATABASE tennis_levelr_restored;"
cat backup.sql | docker exec -i tennis_levelr_db psql -U postgres -d tennis_levelr_restored
```

---

## Next Steps

Once the database is set up, you can:

1. **Create repository interfaces** - See `docs/repository-implementation.md` (coming soon)
2. **Add API endpoints** - Implement CRUD operations for players, matches, etc.
3. **Write integration tests** - Test database interactions
4. **Set up monitoring** - Track query performance and connection pool metrics

For schema details and table relationships, see: [Database Schema Documentation](./database-schema.md)
