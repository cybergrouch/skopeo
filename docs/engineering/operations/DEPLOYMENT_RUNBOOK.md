# Deployment Runbook & Environment Tracking

The single source of truth for **what is deployed, where, and how to reproduce it**. It ties together
the design docs and the CD pipelines, lists the exact config each pipeline needs to go live, and
tracks the current state of the environment.

- **Provisioning detail** (gcloud commands, Cloud SQL, hardening, day-2 ops): [DEPLOYMENT_GCP.md](DEPLOYMENT_GCP.md)
- **CD design** (Workload Identity Federation, phases): [CICD.md](CICD.md)
- **Manual test runbook**: [MANUAL_TESTING_GUIDE.md](MANUAL_TESTING_GUIDE.md)

## Architecture & decisions

| Decision | Choice |
|---|---|
| Environments | **Production only** (pilot; ~$10–13/mo per DEPLOYMENT_GCP.md) |
| API | **Cloud Run** (`skopeo`), built from the repo `Dockerfile` via Cloud Build, scales to zero |
| Database | **Cloud SQL** for PostgreSQL (`SkopeoDb`), password in Secret Manager |
| Web | **Firebase Hosting** (static SPA from `web/dist`) |
| Region | `asia-southeast1` (Singapore) |
| Domain layout | Web on the **apex** (`skopeo.com`); API on **`api.skopeo.com`** (Cloud Run domain mapping) |
| GCP auth for CD | **Workload Identity Federation** — keyless, no stored SA JSON |
| API deploy gate | **Manual approval** via the `production` GitHub Environment |
| DB migrations | **Flyway on app startup** (`DatabaseConfig.init`) — the new Cloud Run revision migrates as it boots |

> Replace `skopeo.com` / `skopeo-prod` below with the real registered domain / GCP + Firebase project
> ids. They are placeholders that match the in-repo defaults (`application.yaml`).

```
apex  skopeo.com  ──► Firebase Hosting (web/dist SPA)
                      │  Firebase JS SDK ──► Firebase Auth (Google / password / Facebook)
                      ▼  XHR to VITE_API_BASE_URL
api.skopeo.com ──► Cloud Run (skopeo)  ──► Cloud SQL (SkopeoDb)
                      verifies the ID token against the Firebase project's JWKS
```

## Pipelines

Both workflows are **inert until configured** — each is guarded by an `if:` on a repo variable, so
merging them never produces a red run before the cloud resources exist.

| Workflow | Trigger | Guard (skips unless set) | Gate |
|---|---|---|---|
| `.github/workflows/deploy-api.yml` | push to `main` touching backend paths, or `workflow_dispatch` | `vars.WIF_PROVIDER` | `production` environment approval |
| `.github/workflows/deploy-web.yml` | push to `main` touching `web/**`, OpenAPI spec, `firebase.json` | `vars.VITE_FIREBASE_PROJECT_ID` | none |

### API pipeline — required repo **Variables**

Settings → Secrets and variables → Actions → **Variables**:

| Variable | Example | Notes |
|---|---|---|
| `WIF_PROVIDER` | `projects/123/locations/global/workloadIdentityPools/github/providers/github` | Setting this **activates** the API deploy job |
| `DEPLOY_SA` | `github-deployer@skopeo-prod.iam.gserviceaccount.com` | The deploy service account WIF impersonates |
| `GCP_PROJECT_ID` | `skopeo-prod` | |
| `GCP_REGION` | `asia-southeast1` | Optional; defaults to `asia-southeast1` |
| `CLOUD_RUN_SERVICE` | `skopeo` | Optional; defaults to `skopeo` |
| `CLOUDSQL_INSTANCE` | `skopeo-prod:asia-southeast1:skopeo-db` | Cloud SQL connection name (`--add-cloudsql-instances`) |
| `DATABASE_URL` | `jdbc:postgresql:///SkopeoDb?cloudSqlInstance=...&socketFactory=...` | JDBC URL (Cloud SQL socket factory or private IP) |
| `DATABASE_USER` | `skopeo` | |
| `FIREBASE_PROJECT_ID` | `skopeo-prod` | Token issuer/audience anchor |
| `WEB_ORIGINS` | `https://skopeo.com,https://skopeo-prod.web.app` | CORS allow-list (see "CORS") |

### API pipeline — required **Secret Manager** entries

The workflow wires these with `--set-secrets` (not GitHub secrets — they live in GCP Secret Manager):

| Secret name | Maps to env | Purpose |
|---|---|---|
| `skopeo-db-password` | `DATABASE_PASSWORD` | Cloud SQL user password |
| `skopeo-admin-emails` | `ADMIN_EMAILS` | Verified-email allowlist auto-granted ADMINISTRATOR ([ADMIN_BOOTSTRAP.md](../architecture/ADMIN_BOOTSTRAP.md)) |

The `DEPLOY_SA` needs `roles/secretmanager.secretAccessor` (or grant per-secret), plus the deploy
roles from CICD.md (`run.admin`, `iam.serviceAccountUser`, `cloudbuild.builds.editor`,
`artifactregistry.writer`).

### Web pipeline — required config

| Kind | Name | Notes |
|---|---|---|
| Variable | `VITE_FIREBASE_PROJECT_ID` | Setting this **activates** the web deploy job |
| Variable | `VITE_FIREBASE_API_KEY`, `VITE_FIREBASE_AUTH_DOMAIN`, `VITE_FIREBASE_APP_ID` | Public Firebase client values (safe in the bundle) |
| Variable | `VITE_API_BASE_URL` | `https://api.skopeo.com` (the API's custom domain) |
| Secret | `FIREBASE_SERVICE_ACCOUNT` | Firebase Hosting deploy credential (the only web secret) |

## CORS

The API allows `localhost:5173` always and reads production origins from config `cors.origins`
(env **`WEB_ORIGINS`**, comma-separated `scheme://host[:port]`). Set `WEB_ORIGINS` to the web origins
the browser uses — the custom apex **and** the Firebase default while DNS propagates, e.g.
`https://skopeo.com,https://skopeo-prod.web.app`. No code change is needed to add an origin.

## Custom domain (apex → Web, `api.` → API)

1. **Web (apex):** Firebase Console → Hosting → **Add custom domain** → `skopeo.com`; add the A/TXT
   records Firebase shows at your DNS registrar. Firebase provisions a managed TLS cert.
2. **API (subdomain):** `gcloud run domain-mappings create --service skopeo --domain api.skopeo.com
   --region asia-southeast1`; add the resulting CNAME/A records. Cloud Run provisions managed TLS.
3. **Auth:** Firebase Console → Authentication → Settings → **Authorized domains** → add `skopeo.com`
   (and `www` if used). Otherwise Google/Facebook sign-in fails in production.
4. **CORS:** ensure `WEB_ORIGINS` includes `https://skopeo.com`, then redeploy the API.
5. **Web → API base URL:** set `VITE_API_BASE_URL=https://api.skopeo.com` and redeploy the web.

## Manual-approval gate (API)

GitHub → Settings → **Environments → `production`** → enable **Required reviewers** (add yourself).
`deploy-api.yml` runs in `environment: production`, so each API deploy pauses for approval before it
releases. Web deploys are automatic on green `main`.

## Rollback

- **API:** list revisions and shift 100% traffic back to a known-good one (see DEPLOYMENT_GCP.md §8):
  `gcloud run revisions list --service skopeo --region asia-southeast1` then
  `gcloud run services update-traffic skopeo --to-revisions <REVISION>=100 --region asia-southeast1`.
  Note: Flyway migrations are forward-only — a rollback restores the image, not the schema.
- **Web:** Firebase Console → Hosting → **release history** → **Rollback** to a prior release.

## Go-live checklist

- [ ] GCP project + APIs enabled, Cloud SQL instance + `SkopeoDb` + user created (DEPLOYMENT_GCP.md §3–4).
- [ ] Secret Manager: `skopeo-db-password`, `skopeo-admin-emails` created.
- [ ] WIF pool + provider + `github-deployer` SA with deploy + `secretAccessor` roles (CICD.md §2a).
- [ ] API repo **Variables** + **Secrets** set (tables above) → first API deploy approved & green.
- [ ] Firebase project confirmed = the Auth project; web **Variables** + `FIREBASE_SERVICE_ACCOUNT` set → first web deploy green.
- [ ] Custom domains mapped (apex → Hosting, `api.` → Cloud Run), TLS active, Authorized domains updated.
- [ ] `WEB_ORIGINS` + `VITE_API_BASE_URL` point at the custom domains; end-to-end sign-in + an API call work.
- [ ] `production` environment requires approval.

## Environment status

Update this table as resources come up.

| Item | Value | Status |
|---|---|---|
| GCP project id | `skopeo-prod` (confirm) | ☐ not provisioned |
| Region | `asia-southeast1` | — |
| Cloud Run service | `skopeo` | ☐ not deployed |
| Cloud Run URL | _(fill in)_ | ☐ |
| Cloud SQL instance | _(connection name)_ | ☐ not provisioned |
| Firebase project | `skopeo-prod` (confirm) | ☐ |
| Firebase Hosting URL | `https://<project>.web.app` | ☐ not deployed |
| Web apex domain | `skopeo.com` | ☐ not mapped |
| API domain | `api.skopeo.com` | ☐ not mapped |
| WIF provider | _(resource name)_ | ☐ not configured |
| `production` approval gate | required reviewers | ☐ not set |
