# Deploying Skopeo to Google Cloud (Cloud Run + Cloud SQL)

How to deploy the Skopeo API and its PostgreSQL database to Google Cloud Platform, and why GCP was chosen over AWS for the pilot phase.

Research date: **June 2026** — pricing and platform facts below were verified then and should be re-checked before any major commitment.

---

## 1. Platform Decision: Why GCP

Skopeo's pilot phase has two defining constraints: **low, bursty traffic** (a handful of clubs) and **high cost sensitivity** (system sustainability is an explicit project concern). Evaluated against AWS, GCP wins on four points:

| Concern | GCP | AWS |
|---|---|---|
| Container hosting fit | **Cloud Run**: runs the existing Docker image, scales to zero when idle, perpetual free tier (2M requests, 180k vCPU-s, 360k GiB-s per month) | **App Runner is in maintenance mode** (no new customers after April 30, 2026). The recommended replacement, ECS Express Mode on Fargate, has no scale-to-zero and provisions an Application Load Balancer (~$16+/month fixed) |
| Managed PostgreSQL floor | Cloud SQL `db-f1-micro` shared-core: ~$7–11/month + storage | RDS `db.t4g.micro`: ~$12–15/month + storage; 12-month free RDS tier **removed for accounts created after July 15, 2025** (replaced by $100–200 credits expiring in 6 months) |
| Free tier longevity | Cloud Run free tier **never expires**; ~$300/90-day new-account trial on top | Credits-based free plan, expires after 6 months or when credits run out |
| Philippines proximity | No PH region; serve from `asia-southeast1` (Singapore), ~30–50 ms from Manila | No PH region either. The Manila **Local Zone** is compute-edge only — no RDS or Fargate — and parents to Singapore (`ap-southeast-1`) anyway |

**Estimated pilot cost: ~$10–13/month** (Cloud Run ≈ $0 within free tier; Cloud SQL `db-f1-micro` + 10 GB SSD ≈ $10–13).

The app itself is platform-neutral: it ships as a Docker container (any JVM inside the image — see [JVM_COMPATIBILITY.md](JVM_COMPATIBILITY.md)) and reads all database config from environment variables (`DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` with local defaults in `application.yaml`). Moving to AWS later requires no code changes.

### Caveats accepted for the pilot

- `db-f1-micro` is a **shared-core** instance: no SLA, not eligible for committed-use discounts. Fine for a pilot; upgrade the tier (one command, brief downtime) when clubs depend on it.
- **Scale-to-zero means cold starts**: the first request after idle pays ~5–10 s (JVM startup + Flyway migration check). Acceptable for a pilot; eliminate later with `--min-instances=1` (a few $/month).

---

## 2. Prerequisites

- A Google Cloud account with billing enabled ([console.cloud.google.com](https://console.cloud.google.com))
- The `gcloud` CLI installed and authenticated (`gcloud auth login`)
- This repository checked out (the existing `Dockerfile` is used as-is)

---

## 3. One-Time Project Setup

```bash
# Create and select a project
gcloud projects create skopeo-prod --name="Skopeo"
gcloud config set project skopeo-prod

# Link billing (find the account ID with: gcloud billing accounts list)
gcloud billing projects link skopeo-prod --billing-account=<BILLING_ACCOUNT_ID>

# Enable the required APIs
gcloud services enable run.googleapis.com sqladmin.googleapis.com \
  secretmanager.googleapis.com cloudbuild.googleapis.com \
  artifactregistry.googleapis.com compute.googleapis.com

# Default region: Singapore (closest to the Philippines)
gcloud config set run/region asia-southeast1
```

**Recommended:** set a budget alert immediately (Console → Billing → Budgets & alerts) at e.g. $20/month so a misconfiguration can't surprise you.

---

## 4. Database: Cloud SQL for PostgreSQL

Create a private-IP-only instance (no public IP — Cloud Run reaches it through the VPC):

```bash
gcloud sql instances create skopeo-db \
  --database-version=POSTGRES_15 \
  --tier=db-f1-micro \
  --region=asia-southeast1 \
  --network=default \
  --no-assign-ip \
  --storage-size=10GB --storage-type=SSD

gcloud sql databases create SkopeoDb --instance=skopeo-db

gcloud sql users create skopeo --instance=skopeo-db --password=<STRONG_PASSWORD>
```

> The first private-IP instance in a project may ask you to configure a
> private services access connection on the `default` network — `gcloud`
> prompts with the exact command (`gcloud services vpc-peerings connect ...`).

Store the password in Secret Manager (never in env vars or source):

```bash
echo -n "<STRONG_PASSWORD>" | gcloud secrets create skopeo-db-password --data-file=-
```

Note the instance's **private IP** for the next step:

```bash
gcloud sql instances describe skopeo-db --format="value(ipAddresses[0].ipAddress)"
```

---

## 5. Application: Cloud Run

Deploy straight from source — Cloud Build builds the repo's multi-stage `Dockerfile` and pushes to Artifact Registry automatically:

```bash
gcloud run deploy skopeo \
  --source . \
  --region=asia-southeast1 \
  --allow-unauthenticated \
  --network=default --subnet=default \
  --min-instances=0 --max-instances=2 \
  --memory=512Mi --cpu=1 \
  --set-env-vars="DATABASE_URL=jdbc:postgresql://<PRIVATE_IP>:5432/SkopeoDb,DATABASE_USER=skopeo" \
  --set-secrets="DATABASE_PASSWORD=skopeo-db-password:latest"
```

Why these flags, specifically for this codebase:

| Flag | Reason |
|---|---|
| `--network/--subnet` | **Direct VPC egress** — lets the container reach Cloud SQL's private IP with the plain JDBC URL the app already uses. (The alternative, Cloud Run's built-in `/cloudsql` unix socket, would require adding the Cloud SQL JDBC socket-factory dependency to the build.) |
| `--max-instances=2` | `db-f1-micro` allows ~25 connections and HikariCP is configured with `maximumPoolSize: 10` per instance. 2 × 10 leaves headroom; raise both together when the DB tier grows. |
| `--min-instances=0` | Scale to zero — free when idle. Set to `1` once clubs use it daily and cold starts matter. |
| `--set-secrets` | Injects the DB password from Secret Manager as the `DATABASE_PASSWORD` env var. |
| `--allow-unauthenticated` | Public API. Tighten later with API keys/IAP when player data lands. |

**Migrations:** nothing extra to do — `DatabaseConfig.init` runs Flyway on startup, so each new revision applies pending migrations from `src/main/resources/db/migration/` when its first instance boots.

**Health checks:** Cloud Run's default TCP startup probe works, but pointing it at the app's `/health` endpoint is better (Console → service → Edit → Health checks, or `--startup-probe` flags).

---

## 6. Verify

```bash
SERVICE_URL=$(gcloud run services describe skopeo --format="value(status.url)")

curl "$SERVICE_URL/health"
# {"status":"UP","service":"Skopeo API","version":"0.0.1-SNAPSHOT"}

curl -X POST "$SERVICE_URL/api/v1/calculate-ranking" \
  -H "Content-Type: application/json" \
  -d @- <<'JSON'
{
  "teams": {
    "T1": {"teamId": "T1", "name": "Player 1",
           "players": [{"playerId": "P1", "name": "Player 1", "rating": {"value": "4.0", "system": "NTRP"}}],
           "teamType": "SINGLES"},
    "T2": {"teamId": "T2", "name": "Player 2",
           "players": [{"playerId": "P2", "name": "Player 2", "rating": {"value": "4.0", "system": "NTRP"}}],
           "teamType": "SINGLES"}
  },
  "matchScore": {"sets": [{"games": {"T1": 6, "T2": 4}, "winnerTeamId": "T1"}]}
}
JSON
```

Swagger UI is served at `$SERVICE_URL/swagger`.

---

## 7. Day-2 Operations

```bash
# Logs (includes the calculation audit trail entries)
gcloud run services logs read skopeo --region=asia-southeast1 --limit=50

# Deploy a new version (same command as the first deploy)
gcloud run deploy skopeo --source . ...

# Roll back to a previous revision
gcloud run revisions list --service=skopeo
gcloud run services update-traffic skopeo --to-revisions=<REVISION>=100

# Database backups: enable automated daily backups (do this once)
gcloud sql instances patch skopeo-db --backup-start-time=18:00   # 02:00 PHT

# Grow the DB when the pilot does (brief restart)
gcloud sql instances patch skopeo-db --tier=db-g1-small
```

Scaling path as adoption grows: `min-instances=1` (kill cold starts) → `db-g1-small` → dedicated-core Cloud SQL + raise `max-instances` — no architectural changes required.

---

## 8. Teardown

```bash
gcloud run services delete skopeo --region=asia-southeast1
gcloud sql instances delete skopeo-db
gcloud secrets delete skopeo-db-password
# or remove everything at once:
gcloud projects delete skopeo-prod
```

---

## 9. If AWS Is Required Instead

The same container deploys to AWS without code changes:

- **Compute:** ECS **Express Mode** (Fargate) in `ap-southeast-1` — App Runner is closed to new customers as of April 30, 2026.
- **Database:** RDS PostgreSQL `db.t4g.micro` (or evaluate the Aurora PostgreSQL free-tier offering announced March 2026).
- **Cost floor:** ~$25–35/month realistic minimum — the always-on Fargate task plus the Application Load Balancer's fixed cost; no scale-to-zero.
- The Manila Local Zone does not help this workload (no RDS/Fargate there).

---

## Sources

- [Cloud Run pricing](https://cloud.google.com/run/pricing) · [Cloud SQL pricing](https://cloud.google.com/sql/pricing)
- [Cloud Run cost-optimization best practices](https://docs.cloud.google.com/run/docs/tips/services-cost-optimization)
- [AWS App Runner maintenance mode and alternatives](https://northflank.com/blog/aws-app-runner-alternatives)
- [AWS Free Tier update — credits model for new accounts](https://aws.amazon.com/blogs/aws/aws-free-tier-update-new-customers-can-get-started-and-explore-aws-with-up-to-200-in-credits/) · [AWS Free Tier with RDS](https://aws.amazon.com/rds/free/)
- [AWS Local Zones in Manila — GA announcement](https://aws.amazon.com/about-aws/whats-new/2023/06/general-availability-aws-local-zones-manila/)

Related docs: [DOCKER_DEPLOYMENT.md](DOCKER_DEPLOYMENT.md) (container details) · [database-setup.md](database-setup.md) (schema/migrations) · [JVM_COMPATIBILITY.md](JVM_COMPATIBILITY.md) (runtime JVM strategy)
