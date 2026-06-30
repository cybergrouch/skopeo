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

Deploys are **release-driven, not on-merge**: publishing a GitHub Release deploys that tag.

| Workflow | Trigger | Guard (skips unless set) | Gate |
|---|---|---|---|
| `.github/workflows/release.yml` | `workflow_dispatch` (the one-click release) | none | none |
| `.github/workflows/deploy-api.yml` | `release: published`, or `workflow_dispatch` (manual/sandbox) | `vars.WIF_PROVIDER` | `production` environment approval |
| `.github/workflows/deploy-web.yml` | `release: published`, or `workflow_dispatch` (manual/sandbox) | `vars.VITE_FIREBASE_PROJECT_ID` | `production` environment approval |

**Release flow:** Actions → **Release → Run workflow** → it strips `-SNAPSHOT` to form the official
version (e.g. `0.0.1`), tags `vX.Y.Z` + publishes a GitHub Release (→ triggers the two deploys), and
opens a PR bumping `main`'s dev version to the next `-SNAPSHOT`. The version is single-sourced from
`build.gradle.kts` → generated `version.properties` → `/health`, so the tag's version is what `/health`
reports. (To ship the current `-SNAPSHOT` as-is — e.g. the initial `v0.0.1-SNAPSHOT` marker — pass it
to the Release workflow's `version` input.)

### API pipeline — required repo **Variables**

Settings → Secrets and variables → Actions → **Variables**:

| Variable | Example | Notes |
|---|---|---|
| `WIF_PROVIDER` | `projects/123/locations/global/workloadIdentityPools/github/providers/github` | Setting this **activates** the API deploy job |
| `DEPLOY_SA` | `github-deployer@skopeo-prod.iam.gserviceaccount.com` | The deploy service account WIF impersonates |
| `GCP_PROJECT_ID` | `skopeo-prod` | |
| `GCP_REGION` | `asia-southeast1` | Optional; defaults to `asia-southeast1` |
| `CLOUD_RUN_SERVICE` | `skopeo` | Optional; defaults to `skopeo` |
| `CLOUDSQL_INSTANCE` | `skopeo-prod:asia-southeast1:skopeo-db` | Cloud SQL **connection name** (`project:region:instance`), passed to `--add-cloudsql-instances` |
| `DATABASE_URL` | `jdbc:postgresql://<PRIVATE_IP>:5432/SkopeoDb` | JDBC URL via the instance's **private IP** over direct VPC egress (DEPLOYMENT_GCP.md §4–5). The build has **no** Cloud SQL socket-factory dependency, so the `jdbc:postgresql:///...&socketFactory=...` form does **not** work here |
| `DATABASE_USER` | `skopeo` | |
| `FIREBASE_PROJECT_ID` | `skopeo-prod` | Token issuer/audience anchor |
| `WEB_ORIGINS` | `https://skopeo.com,https://skopeo-prod.web.app` | CORS allow-list (see "CORS") |

### API pipeline — required **Secret Manager** entries

The workflow wires these with `--set-secrets` (not GitHub secrets — they live in GCP Secret Manager):

| Secret name | Maps to env | Purpose |
|---|---|---|
| `skopeo-db-password` | `DATABASE_PASSWORD` | Cloud SQL user password |
| `skopeo-admin-emails` | `ADMIN_EMAILS` | Verified-email allowlist auto-granted ADMINISTRATOR ([ADMIN_BOOTSTRAP.md](../architecture/ADMIN_BOOTSTRAP.md)) — comes from Secret Manager, **not** a repo variable |

Both are created in Secret Manager (commands in [DEPLOYMENT_GCP.md §4a](DEPLOYMENT_GCP.md)). The
`DEPLOY_SA` needs `roles/secretmanager.secretAccessor` (or grant per-secret), plus the deploy
roles from [CICD.md §2a](CICD.md) (`run.admin`, `iam.serviceAccountUser`, `cloudbuild.builds.editor`,
`artifactregistry.writer`, `secretmanager.secretAccessor`).

> **Cloud SQL connection:** the workflow passes `--add-cloudsql-instances` (it mounts the instance
> and is harmless), but this app's actual JDBC connection is via the **private IP over VPC egress**
> (`--network=default --subnet=default`) — the build has no Cloud SQL socket-factory dependency.
> See [DEPLOYMENT_GCP.md §5](DEPLOYMENT_GCP.md).

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

> **Format is strict** — each entry must be a full `scheme://host[:port]` (include `https://`, no
> trailing slash, no path). Malformed entries (e.g. a bare `skopeo.com`) are **silently dropped**, so
> a typo means the browser is blocked with no server-side error.

> **Multi-origin + gcloud:** `WEB_ORIGINS` is the one runtime variable that legitimately contains
> commas, but `gcloud run deploy --set-env-vars` also uses commas to separate assignments. So a
> multi-origin value would be mis-parsed by the default delimiter. `deploy-api.yml` therefore sets env
> vars with gcloud's alternate-delimiter syntax — a leading `^##^` makes `##` the separator so commas
> inside `WEB_ORIGINS` survive. If you ever set these vars by hand, do the same, e.g.
> `gcloud run services update skopeo --set-env-vars "^##^WEB_ORIGINS=https://skopeo.com,https://skopeo-prod.web.app"`.

Changing `WEB_ORIGINS` only takes effect on the **next API deploy** (env vars are injected at deploy
time) — redeploy the API after editing it. Verify with a preflight:

```bash
curl -i -X OPTIONS https://api.skopeo.com/api/v1/users \
  -H "Origin: https://skopeo.com" \
  -H "Access-Control-Request-Method: GET"
# expect: Access-Control-Allow-Origin: https://skopeo.com
```

## Custom domain (apex → Web, `api.` → API)

1. **Web (apex):** Firebase Console → Hosting → **Add custom domain** → `skopeo.com`; add the A/TXT
   records Firebase shows at your DNS registrar. Firebase provisions a managed TLS cert.
2. **API (subdomain):** `gcloud run domain-mappings create --service skopeo --domain api.skopeo.com
   --region asia-southeast1`; add the resulting CNAME/A records. Cloud Run provisions managed TLS.
3. **Auth:** Firebase Console → Authentication → Settings → **Authorized domains** → add `skopeo.com`
   (and `www` if used). Otherwise Google/Facebook sign-in fails in production.
4. **CORS:** ensure `WEB_ORIGINS` includes `https://skopeo.com`, then redeploy the API.
5. **Web → API base URL:** set `VITE_API_BASE_URL=https://api.skopeo.com` and redeploy the web.

Exact DNS records, `dig`/`nslookup` verification, and propagation/TLS-issuance expectations are in
[DEPLOYMENT_GCP.md §9](DEPLOYMENT_GCP.md).

## Manual-approval gate (API)

GitHub → Settings → **Environments → `production`** → enable **Required reviewers** (add yourself).
`deploy-api.yml` runs in `environment: production`, so each API deploy pauses for approval before it
releases. Web deploys are automatic on green `main`. Setup steps and the self-approval caveat are in
[CICD.md §2c](CICD.md).

## Pre-deployment checklist

Before the first deploy (and before each release that changes config), confirm:

- [ ] **CI is green on `main`** — the `build`, `web`, and `secret-scan` jobs all pass.
- [ ] **The `Dockerfile` builds** — `docker build -t skopeo .` succeeds locally (or the last `--source` Cloud Build did).
- [ ] **`.gcloudignore` excludes** `web/`, `docs/`, `build/` (and keeps the `Dockerfile`, `src/`, Gradle files) — already committed; don't remove those exclusions.
- [ ] **OpenAPI spec current** — `OpenAPIIntegrationTest` passes (it's part of `./gradlew check`), so `src/main/resources/openapi/documentation.yaml` matches the API the web client generates against.
- [ ] **All repo Variables + Secret Manager entries set** — every row in the API/Web tables above, plus `skopeo-db-password` and `skopeo-admin-emails` in Secret Manager.
- [ ] **`production` environment requires approval** (see below).

## Manual API deploy (`workflow_dispatch`)

`deploy-api.yml` also has a `workflow_dispatch:` trigger: **Actions → Deploy API → Run workflow →
`main`**. It still runs through the `production` approval gate and the `vars.WIF_PROVIDER` guard.
Use it to redeploy after changing a repo variable or rotating a secret without a code push.

## First-deploy verification

After the first deploy is approved and green:

```bash
# Confirm Flyway actually ran the migrations as the revision booted
gcloud run services logs read skopeo --region asia-southeast1 --limit=50 | grep -i flyway

# Smoke-test the service (cold start: first request after idle pays ~5–10s — JVM + Flyway check)
SERVICE_URL=$(gcloud run services describe skopeo --region asia-southeast1 --format="value(status.url)")
curl "$SERVICE_URL/health"
```

Expect Flyway log lines like `Successfully applied N migrations` (or `Schema ... is up to date`).
Full verification steps (including the calculator smoke test) are in [DEPLOYMENT_GCP.md §6](DEPLOYMENT_GCP.md).

## Manual deployment by hand (CD fallback)

If GitHub Actions is unavailable, deploy directly with the gcloud / Firebase CLIs from a local
checkout. First authenticate: `gcloud auth login` && `gcloud config set project <GCP_PROJECT_ID>`, and
`firebase login`. Deploy the **release tag** to match what CD ships (`git checkout vX.Y.Z`), or `main`
for a dev/sandbox build. Real values for the `<…>` placeholders live in the git-ignored
`presentations/GCP_DEPLOYMENT_WALKTHROUGH.md` — never commit them here.

**API → Cloud Run** (mirrors `deploy-api.yml`; `^##^` keeps the comma inside `WEB_ORIGINS`; the flags
wire direct VPC egress for the private-IP DB + one warm instance):
```bash
git checkout vX.Y.Z      # the release to ship (or `main` for the -SNAPSHOT dev build)
gcloud run deploy skopeo \
  --source . \
  --region asia-southeast1 \
  --allow-unauthenticated \
  --min-instances=1 --max-instances=2 \
  --network=default --subnet=default \
  --add-cloudsql-instances "<CLOUDSQL_INSTANCE>" \
  --set-env-vars "^##^FIREBASE_PROJECT_ID=<FIREBASE_PROJECT_ID>##DATABASE_URL=<DATABASE_URL>##DATABASE_USER=<DATABASE_USER>##WEB_ORIGINS=<comma,separated,origins>" \
  --set-secrets "DATABASE_PASSWORD=skopeo-db-password:latest,ADMIN_EMAILS=skopeo-admin-emails:latest"
```
> To change ONLY one env var on the running service later, use `--update-env-vars` (NOT `--set-env-vars`,
> which replaces them all). Keep the `^##^` prefix when the value contains commas.

**Web → Firebase Hosting** (mirrors `deploy-web.yml`):
```bash
cd web
printf 'VITE_API_BASE_URL=<API_URL>\n' > .env.production.local   # Firebase VITE_* come from .env.local
npm ci && npm run build
cd ..
firebase deploy --only hosting --project <FIREBASE_PROJECT_ID>
```

Then run the **First-deploy verification** block above (Flyway log + `/health`). To roll back, redeploy
the previous tag, or `gcloud run services update-traffic skopeo --region asia-southeast1 --to-revisions <PREV>=100`.

## Rollback

- **API:** list revisions and shift 100% traffic back to a known-good one (see DEPLOYMENT_GCP.md §8):
  `gcloud run revisions list --service skopeo --region asia-southeast1` then
  `gcloud run services update-traffic skopeo --to-revisions <REVISION>=100 --region asia-southeast1`.
- **Web:** Firebase Console → Hosting → **release history** → **Rollback** to a prior release.

### Flyway is forward-only — rolling the API back does not roll the schema back

Migrations apply on startup and are **never auto-reverted**. If you roll the API image back to a
revision whose code predates a migration that already ran, the older code runs against a **newer
schema**. That is usually fine (additive migrations), but if the older code can't tolerate the new
schema it may fail to start or error at runtime. The remediation is **roll forward**: ship a new
migration (a `V{n+1}__*.sql`, per the incremental-migrations rule) that reconciles the schema with
the code you want running, rather than trying to undo the applied migration. Plan schema changes to
be backward-compatible across one release so an image rollback stays safe.

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
