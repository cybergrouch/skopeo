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
| API | **Cloud Run** (`skopeo`), built from the repo `Dockerfile` via Cloud Build, `--min-instances=1 --max-instances=2` (does **not** scale to zero) |
| Database | **Cloud SQL** for PostgreSQL (`SkopeoDb`), password in Secret Manager |
| Web | **Firebase Hosting** (static SPA from `web/dist`) |
| Region | `asia-southeast1` (Singapore) |
| Domain layout | Web on the **apex** (`skopeo.co`); API on **`api.skopeo.co`** (Cloud Run domain mapping) |
| GCP auth for CD | **Workload Identity Federation** — keyless, no stored SA JSON |
| API deploy gate | **Manual approval** via the `production` GitHub Environment |
| DB migrations | **Flyway on app startup** (`DatabaseConfig.init`) — the new Cloud Run revision migrates as it boots |

> Replace `skopeo.co` / `skopeo-prod` below with the real registered domain / GCP + Firebase project
> ids. They are placeholders that match the in-repo defaults (`application.yaml`).

```
apex  skopeo.co  ──► Firebase Hosting (web/dist SPA)
                      │  Firebase JS SDK ──► Firebase Auth (Google / password / Facebook)
                      ▼  XHR to VITE_API_BASE_URL
api.skopeo.co ──► Cloud Run (skopeo)  ──► Cloud SQL (SkopeoDb)
                      verifies the ID token against the Firebase project's JWKS
```

## Pipelines

Both workflows are **inert until configured** — each is guarded by an `if:` on a repo variable, so
merging them never produces a red run before the cloud resources exist.

Deploys are **release-driven, not on-merge**: a release tag is deployed (the deploys are dispatched by
`tag-and-ship.yml` once the release lands on `main`).

| Workflow | Trigger | Guard (skips unless set) | Gate |
|---|---|---|---|
| `.github/workflows/release.yml` | `workflow_dispatch` — **step 1**: opens the `release: vX.Y.Z` PR | none | none |
| `.github/workflows/tag-and-ship.yml` | `push` to `main` — **step 2**: fires when `main` holds an untagged non-SNAPSHOT version | none | none |
| `.github/workflows/deploy-api.yml` | `workflow_dispatch` (dispatched by tag-and-ship, or manual) | `vars.WIF_PROVIDER` | `production` environment approval |
| `.github/workflows/deploy-web.yml` | `workflow_dispatch` (dispatched by tag-and-ship, or manual) | `vars.VITE_FIREBASE_PROJECT_ID` | `production` environment approval |

**Release flow (two phases, two merges):** `main` is branch-protected, so the release-version commit
lands via a PR rather than a tag-only orphan.

1. Actions → **Release → Run workflow** → `release.yml` opens a **`release: vX.Y.Z` PR** that sets the
   official version (strips `-SNAPSHOT`, e.g. `0.0.3`) on a branch. Review + merge it.
2. Merging that PR puts the release version on `main`, which triggers `tag-and-ship.yml`: it tags
   `vX.Y.Z`, publishes the GitHub Release, **dispatches `deploy-api` + `deploy-web` for the tag** (each
   waits on the `production` approval gate), and opens a **dev-version bump PR** returning `main` to the
   next `-SNAPSHOT`. Merge that to finish.

The version is single-sourced from `build.gradle.kts` → generated `version.properties` → `/health`, so
the tag's version is what `/health` reports. (To ship the current `-SNAPSHOT` as-is — e.g. an initial
marker — pass the version explicitly to the Release workflow's `version` input.) `tag-and-ship` is
idempotent: a normal `-SNAPSHOT` push or an already-tagged version is a no-op.

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
| `WEB_ORIGINS` | `https://skopeo.co,https://skopeo-prod.web.app` | CORS allow-list (see "CORS") |

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
| Variable | `VITE_API_BASE_URL` | `https://api.skopeo.co` (the API's custom domain) |
| Secret | `FIREBASE_SERVICE_ACCOUNT` | Firebase Hosting deploy credential (the only web secret) |

## CORS

The API allows `localhost:5173` always and reads production origins from config `cors.origins`
(env **`WEB_ORIGINS`**, comma-separated `scheme://host[:port]`). Set `WEB_ORIGINS` to the web origins
the browser uses — the custom apex **and** the Firebase default while DNS propagates, e.g.
`https://skopeo.co,https://skopeo-prod.web.app`. No code change is needed to add an origin.

> **Format is strict** — each entry must be a full `scheme://host[:port]` (include `https://`, no
> trailing slash, no path). Malformed entries (e.g. a bare `skopeo.co`) are **silently dropped**, so
> a typo means the browser is blocked with no server-side error.

> **Multi-origin + gcloud:** `WEB_ORIGINS` is the one runtime variable that legitimately contains
> commas, but `gcloud run deploy --set-env-vars` also uses commas to separate assignments. So a
> multi-origin value would be mis-parsed by the default delimiter. `deploy-api.yml` therefore sets env
> vars with gcloud's alternate-delimiter syntax — a leading `^##^` makes `##` the separator so commas
> inside `WEB_ORIGINS` survive. If you ever set these vars by hand, do the same, e.g.
> `gcloud run services update skopeo --set-env-vars "^##^WEB_ORIGINS=https://skopeo.co,https://skopeo-prod.web.app"`.

Changing `WEB_ORIGINS` only takes effect on the **next API deploy** (env vars are injected at deploy
time) — redeploy the API after editing it. Verify with a preflight:

```bash
curl -i -X OPTIONS https://api.skopeo.co/api/v1/users \
  -H "Origin: https://skopeo.co" \
  -H "Access-Control-Request-Method: GET"
# expect: Access-Control-Allow-Origin: https://skopeo.co
```

## Custom domain (apex → Web, `api.` → API)

1. **Web (apex):** Firebase Console → Hosting → **Add custom domain** → `skopeo.co`; add the A/TXT
   records Firebase shows at your DNS registrar. Firebase provisions a managed TLS cert.
2. **API (subdomain):** `gcloud run domain-mappings create --service skopeo --domain api.skopeo.co
   --region asia-southeast1`; add the resulting CNAME/A records. Cloud Run provisions managed TLS.
3. **Auth:** Firebase Console → Authentication → Settings → **Authorized domains** → add `skopeo.co`
   (and `www` if used). Otherwise Google/Facebook sign-in fails in production.
4. **CORS:** ensure `WEB_ORIGINS` includes `https://skopeo.co`, then redeploy the API.
5. **Web → API base URL:** set `VITE_API_BASE_URL=https://api.skopeo.co` and redeploy the web.

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

# Smoke-test the service (first request to a NEW revision pays ~5–10s for JVM + Flyway; with
#  --min-instances=1 an idle service no longer cold-starts, but a scale-up to a 2nd instance does)
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

## Alerts

Two paging alerts, and deliberately nothing else (#751 decision 4 / #808). Configuration lives in
[`infra/monitoring/`](../../../infra/monitoring/README.md), not only in the console.

They are not redundant — they catch different shapes of failure:

| Alert | Means | Runbook |
| --- | --- | --- |
| **Skopeo API returning 5xx** | The app is answering, and answering **wrongly** | [below](#api-returning-5xx) |
| **Skopeo API unreachable** | The app **cannot answer at all** | [below](#api-unreachable) |

Alerts go to a team-managed group, never a personal address (#190).

### API returning 5xx

1. **Confirm the blast radius.** Open the **Skopeo API** dashboard (#809) and read the *per-endpoint
   request rate* and *4xx by status code* panels: one endpoint failing is a code path, every endpoint
   failing is the database or a dependency. Failing that, Cloud Logging with `severity>=ERROR` over the
   last 30 minutes, grouped by the `route` field.
2. **Get a request id.** Every 5xx carries one in its body and in `X-Request-Id` (#805). If a user
   reported it, their screenshot has it — query `requestId="…"` for the full trace.
3. **Check the stack trace.** Cloud Error Reporting groups backend exceptions automatically (#804), so
   the top group is usually the culprit without hand-searching logs.
4. **Check whether a deploy caused it.** Compare the alert's start time against the most recent Deploy
   API run. If it lines up, [roll back](#rollback) first and diagnose afterwards.
5. **Check the database.** Cloud SQL CPU, memory and connection count. Connection exhaustion presents as
   a broad 5xx across unrelated endpoints.

### API unreachable

The uptime check is failing from more than one region. A single region failing is usually the checker,
not us — the policy requires two for that reason.

1. **Is the revision serving?**
   `gcloud run services describe skopeo --region asia-southeast1 --format='value(status.url,status.conditions)'`
2. **Did a revision fail to boot?** This is the shape the **v2.0.8** failure took: Flyway failed, the
   revision never passed readiness, Cloud Run never shifted traffic, and **no 5xx was ever recorded**.
   Check `gcloud run services logs read skopeo --region asia-southeast1 --limit=100 | grep -i flyway`.
   Note that in that case users were *never affected* — the previous revision kept serving.
3. **Is it DNS or TLS rather than the app?** If the uptime check targets `api.skopeo.co` and the
   `*.run.app` URL still answers, the fault is the domain mapping or the certificate, not the container.
   `curl -sSv https://api.skopeo.co/health 2>&1 | grep -E "expire|subject|HTTP/"`
4. **Is Cloud Run itself degraded?** Check the GCP status dashboard for `asia-southeast1` before
   digging further.

### A failed deploy does not page, on purpose

Cloud Run refuses to shift traffic to a revision that fails readiness. So a bad deploy produces **no
5xx and no uptime dip** — correctly, because users are still served by the previous revision. The
signal for that is the **Deploy API workflow failing** in GitHub, not a monitoring alert. Do not add one:
it would page for something that has no user impact, which is how a two-alert budget becomes a
twelve-alert budget nobody reads.

### Testing the alerts

An alerting path that has never delivered a message is not an alerting path. Cloud Monitoring does
**not** verify an email channel on creation and sends from `alerting-noreply@google.com`, so a group
whose posting policy rejects non-members shows a healthy channel and delivers nothing.

1. **Delivery:** email the alerts group from an address that is *not* a member. Confirm it arrives.
2. **Uptime alert:** temporarily point the uptime check at a path that 404s (e.g. `/health-nope`), wait
   for two check intervals, confirm the email lands, then set it back.
3. **5xx alert:** hardest to force safely. Prefer waiting for a real one over manufacturing an outage;
   if you want certainty, temporarily lower the policy's threshold in a copy of the policy rather than
   inducing 5xx in production.

### Review the budget after two weeks

#751 decision 4 starts with two paging alerts precisely because thresholds set without data become
noise. After ~2 weeks of real traffic, revisit: promote a dashboard panel to a paging alert only if it
would have caught something, and record the outcome on #751. Latency is the likeliest candidate — it is
dashboard-only at launch because `--max-instances=2` means a scale-up still cold-starts and sets a p99
nobody has measured yet.

## Go-live checklist

- [ ] GCP project + APIs enabled, Cloud SQL instance + `SkopeoDb` + user created (DEPLOYMENT_GCP.md §3–4).
- [ ] Secret Manager: `skopeo-db-password`, `skopeo-admin-emails` created.
- [ ] WIF pool + provider + `github-deployer` SA with deploy + `secretAccessor` roles (CICD.md §2a).
- [ ] API repo **Variables** + **Secrets** set (tables above) → first API deploy approved & green.
- [ ] Firebase project confirmed = the Auth project; web **Variables** + `FIREBASE_SERVICE_ACCOUNT` set → first web deploy green.
- [ ] Custom domains mapped (apex → Hosting, `api.` → Cloud Run), TLS active, Authorized domains updated.
- [ ] `WEB_ORIGINS` + `VITE_API_BASE_URL` point at the custom domains; end-to-end sign-in + an API call work.
- [ ] **Auth email branding** (issue #133): Firebase **public-facing name** = `Skopeo` and the Authentication email template's sender/subject branded — so invite emails say "Skopeo", not the project id. Steps in AUTHENTICATION.md → "Firebase-native gaps".
- [ ] `production` environment requires approval.
- [ ] **Alerting** (#808): `infra/monitoring/apply.sh` applied; alerts go to a team group, not a personal
      address (#190); delivery verified by mail from a non-member; the uptime alert fired end to end.
- [ ] A ~2-week alert-budget review is scheduled, with the outcome to be recorded on #751.

## Environment status

Verified against the repo's Actions variables, DNS and the GitHub environment API on 2026-08-28.

| Item | Value | Status |
|---|---|---|
| GCP project id | `skopeo-prod` | ✅ live |
| Region | `asia-southeast1` | ✅ |
| Cloud Run service | `skopeo` — `--min-instances=1 --max-instances=2` (does **not** scale to zero) | ✅ live |
| Cloud Run URL | `https://skopeo-lljnrq2m2q-as.a.run.app` | ✅ |
| Cloud SQL instance | `skopeo-prod:asia-southeast1:skopeo-db`, private IP `10.82.0.3` | ✅ live |
| DB backup bucket | `gs://skopeo-prod-db-backups` | ✅ |
| Firebase project | `skopeo-prod` | ✅ |
| Web apex domain | `skopeo.co` → Firebase Hosting | ✅ mapped |
| **API custom domain** | **none.** `api.skopeo.co` has no DNS record; the SPA calls the `*.run.app` URL directly | ☐ not mapped |
| Deploy service account | `github-deployer@skopeo-prod.iam.gserviceaccount.com` | ✅ |
| Uptime check + alerts | `infra/monitoring/` (#808) | ☐ not applied |
| Dashboard + per-endpoint metrics | `infra/monitoring/` (#809) | ☐ not applied |
| **`production` approval gate** | **no protection rules configured** — see below | ⚠️ **not set** |

### ⚠️ Deploys are not gated by a reviewer

The GitHub `Production` environment exists but has `protection_rules: []`, so a `workflow_dispatch` of
Deploy API or Deploy Web reaches production with no second pair of eyes — contrary to the
[manual-approval section](#manual-approval-gate-api) and the go-live checklist below. Enable **Required
reviewers** on the environment to close it.

Worth weighing against how deploys actually fail here: Cloud Run refuses to shift traffic to a revision
that fails readiness, so the V44 migration failure cost a red workflow and a manual fix rather than an
outage. The gate is still worth having — a *successful* deploy of bad code is the case it protects
against, and that one does reach users.

### Two notes on the API domain

1. **No `api.` domain means the uptime check targets `*.run.app`**, which is currently correct because
   that is the host the SPA actually calls.
2. **If a custom domain is ever mapped, repoint the check** (`apply.sh --api-host api.skopeo.co`). A
   check against `*.run.app` stays green through a DNS, TLS or domain-mapping failure — a full outage
   for every browser client, invisible to monitoring.
