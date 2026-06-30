# CI/CD Plan

How Skopeo builds, gates, and deploys via GitHub Actions — phased so the pieces that unblock a proper PR workflow come first, and automated deployment follows when it earns its keep.

Context: solo developer, pilot stage, GitHub-hosted repo, cost-sensitive. The API deploys to GCP Cloud Run + Cloud SQL ([DEPLOYMENT_GCP.md](DEPLOYMENT_GCP.md)); the web UI (later) to Firebase Hosting ([WEB_UI_ARCHITECTURE.md](../architecture/WEB_UI_ARCHITECTURE.md)).

---

## Decisions at a glance

| # | Decision | Choice | Status |
|---|---|---|---|
| C1 | CI/CD platform | **GitHub Actions** (native to the repo, free at this scale) | ✅ Decided |
| C2 | Sequencing | **CI + branch protection first; CD later** | ✅ Decided |
| C3 | CI gate | **`./gradlew check`** on every PR (compile, ktlint, detekt, tests, coverage) | ✅ Decided |
| C4 | GCP auth for CD | **Workload Identity Federation** (keyless — no stored SA keys) | ⭐ Recommended |
| C5 | Deploy cadence | **Manual `gcloud run deploy` until past the stateless-calculator stage; automate after** | ⭐ Recommended |

---

## Why GitHub Actions

The repo is on GitHub, so Actions is the native choice — no extra service to wire up, runs `./gradlew` exactly as you do locally, and is free at this scale (unlimited minutes on public repos; a generous monthly free tier on private). It also has first-party support for the two things we need later: keyless GCP auth (`google-github-actions/auth`) and Firebase deploys.

## Why CI before CD

For a solo dev at pilot stage, the pieces have very different value-right-now:

- **CI + branch protection** is the *prerequisite* for "push properly via PRs" — without it, a PR gate is just etiquette. This is the immediate need.
- **CD (auto-deploy)** is convenience. Deploying a rapidly-changing pilot by hand (`gcloud run deploy`) is fine, and avoids automating a target that's still moving. Add it once the deploy is boring.

So Phase 1 (CI) is do-now; Phase 2 (CD) is do-when-ready.

---

## Phase 1 — CI + branch protection (do now)

### 1a. CI workflow

Create `.github/workflows/ci.yml`. It runs the existing quality gates — which you already run locally — on every PR and on pushes to `main`:

```yaml
name: CI

on:
  pull_request:
    branches: [main]
  push:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7

      # Install BOTH JDK 17 and 21. The last entry (21) becomes JAVA_HOME, so
      # Gradle's daemon — and detekt — run on 21. This is required: detekt 1.23.8
      # crashes on JDK 25+, which is exactly why gradle-daemon-jvm.properties pins
      # the daemon to 21. The build still compiles with the Java 17 toolchain.
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: |
            17
            21

      - uses: gradle/actions/setup-gradle@v6   # Gradle caching, build scans

      - name: Build, lint, detekt, test, verify coverage
        run: ./gradlew check
```

> The committed `ci.yml` also adds a second **`web`** job (Node 22 via
> `actions/setup-node@v5`) that lints, type-checks, tests (Vitest), and builds
> the `web/` SPA — typecheck/build regenerate the orval API client from the
> backend OpenAPI spec, proving the frontend stays in sync with the contract.
> Both jobs publish drillable per-test reports via `dorny/test-reporter@v3` and
> upload coverage to Codecov via `codecov/codecov-action@v7`.

`./gradlew check` already chains everything: compile → ktlint → detekt → tests (JUnit 5 + Kotest) → JaCoCo coverage verification (75% line / 70% branch). One command, the full gate.

**Toolchain discovery (the one gotcha to get right):** Gradle needs to find *both* JDK 21 (to run the daemon/detekt) and JDK 17 (the compile toolchain). The reliable way on CI is to let Gradle discover the JDKs `setup-java` installed, by adding this line to the repo's `gradle.properties`:

```properties
org.gradle.java.installations.fromEnv=JAVA_HOME_17_X64,JAVA_HOME_21_X64
```

(Harmless locally — Gradle ignores env vars that aren't set.) Without this, CI can fail to locate the 17 toolchain even though it works on your machine.

### 1d. CI reporting (test results + coverage)

After `./gradlew check`, three reporting steps surface results in the GitHub UI — focused on the PR's own changes:

- **Coverage summary** — `scripts/coverage-summary.py` parses the JaCoCo XML and writes an overall line/branch table (with the 75%/70% thresholds) plus a per-package breakdown to the run **Summary** page. Runs `if: always()` so coverage shows even when only the coverage gate fails. (Also runs locally: `python3 scripts/coverage-summary.py`.)
- **Test report (drillable)** — `dorny/test-reporter` publishes a **Test Report** check run from the JUnit XML: expandable per-suite/per-test results, with failures expanded (stack trace) and annotated on the source line. Needs `checks: write`. _(JUnit XML has no source line for passing tests, so the exhaustive per-test view with captured output is Gradle's HTML report under `build/reports/tests/test/`.)_
- **Diff coverage on the PR** — `codecov/codecov-action` uploads the JaCoCo XML to **Codecov**, which comments **patch (changed-lines) coverage** on the PR and **annotates uncovered changed lines inline in the “Files changed” tab**, with a hosted per-file drill-down. Codecov statuses are **informational** (see `codecov.yml`); the authoritative gate stays `./gradlew check`.

**One-time setup (Codecov):** sign in at [codecov.io](https://codecov.io) with GitHub and enable `cybergrouch/skopeo` (installs the Codecov GitHub App that posts the PR comment + inline annotations). The repo is public so uploads work tokenless, but adding a `CODECOV_TOKEN` repo secret is recommended for reliability. Until the repo is enabled on Codecov, the upload step still runs (non-blocking) but no comment/annotations appear.

### 1b. Branch protection (the actual PR enforcement)

In **GitHub → Settings → Branches → Add rule** for `main`:

- ☑ Require a pull request before merging (blocks direct pushes to `main`).
- ☑ Require status checks to pass → select the **CI `build`** job.
- ☑ Require branches to be up to date before merging (optional but recommended).
- ☑ (Optional) Require linear history.

The workflow + this rule together are what make `main` unmergeable without green CI — i.e. "doing it properly via PRs."

### 1c. Landing the existing commits via the first PR

There are local commits on `main` ahead of `origin/main`, but they should arrive by PR. Clean path:

```bash
# Move the local commits onto a feature branch
git branch feat/initial-infra            # mark current main
git push origin feat/initial-infra       # push the branch
# Reset local main back to the remote's main, then open a PR from the branch
git fetch origin
git reset --hard origin/main             # only if origin/main is the intended base
```

Then open a PR from `feat/initial-infra` → `main` on GitHub and let CI gate it.

> If `origin/main` is still empty/unseeded, the pragmatic alternative is to push `main` once to seed the repo, *then* enable branch protection and PR everything afterward. Pick based on whether `origin/main` already has history.

---

## Phase 2 — CD for the API (when the deploy is boring)

Auto-deploy the API to Cloud Run on merge to `main`, authenticating **keylessly** via Workload Identity Federation (no service-account JSON keys stored as secrets — GitHub's OIDC token is exchanged for a short-lived GCP credential).

### 2a. One-time GCP setup (keyless auth)

Run these once, in order. Substitute your real `<owner>/<repo>` (e.g. `cybergrouch/skopeo`) and
project id. Flags verified against [google-github-actions/auth](https://github.com/google-github-actions/auth).

```bash
PROJECT_ID=skopeo-prod
REPO="<owner>/<repo>"          # e.g. cybergrouch/skopeo
gcloud config set project "$PROJECT_ID"
PROJECT_NUMBER=$(gcloud projects describe "$PROJECT_ID" --format="value(projectNumber)")
```

**1. Dedicated deploy service account:**

```bash
gcloud iam service-accounts create github-deployer \
  --project="$PROJECT_ID" --display-name="GitHub Actions deployer"

DEPLOY_SA="github-deployer@${PROJECT_ID}.iam.gserviceaccount.com"
```

**2. Project roles the SA needs** for `gcloud run deploy --source .` (Cloud Build builds the image,
pushes to Artifact Registry, then deploys to Cloud Run; it also reads the mounted secrets):

```bash
for ROLE in \
  roles/run.admin \
  roles/iam.serviceAccountUser \
  roles/cloudbuild.builds.editor \
  roles/artifactregistry.writer \
  roles/storage.admin \
  roles/secretmanager.secretAccessor; do
  gcloud projects add-iam-policy-binding "$PROJECT_ID" \
    --member="serviceAccount:${DEPLOY_SA}" \
    --role="$ROLE" --condition=None
done
```

> `roles/storage.admin` is required because `gcloud run deploy --source .` stages the build context
> in a GCS bucket (`run-sources-<project>-<region>`); without it the deploy fails with
> `storage.buckets.get` denied on that bucket.

> **`--condition=None` is required** if your project already has any *conditional* IAM binding (GCP/
> Firebase auto-adds time-bound ones like `developer-connect-connection-setup`). Without it, gcloud
> refuses to add an unconditional binding non-interactively and prompts "Please specify a condition."
> These deploy grants are intentionally unconditional, so `--condition=None` is correct. Add it to
> every `add-iam-policy-binding` below too.

(Per-secret `secretAccessor` grants are shown in [DEPLOYMENT_GCP.md §4a](DEPLOYMENT_GCP.md) if you
prefer least privilege over a project-wide grant.)

**3. Workload Identity pool + GitHub OIDC provider** (the provider is restricted to your repo via
the attribute condition, so only workflows from that repo can authenticate):

```bash
gcloud iam workload-identity-pools create github \
  --project="$PROJECT_ID" --location=global \
  --display-name="GitHub Actions"

gcloud iam workload-identity-pools providers create-oidc github \
  --project="$PROJECT_ID" --location=global \
  --workload-identity-pool=github \
  --display-name="GitHub OIDC" \
  --issuer-uri="https://token.actions.githubusercontent.com" \
  --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository,attribute.repository_owner=assertion.repository_owner" \
  --attribute-condition="assertion.repository=='${REPO}'"
```

**4. Bind the GitHub repo's identity to the deploy SA** so only that repo can impersonate it:

```bash
gcloud iam service-accounts add-iam-policy-binding "$DEPLOY_SA" \
  --project="$PROJECT_ID" \
  --role="roles/iam.workloadIdentityUser" \
  --member="principalSet://iam.googleapis.com/projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/github/attribute.repository/${REPO}" \
  --condition=None
```

**5. Retrieve the provider resource name** — this exact string goes into `vars.WIF_PROVIDER`:

```bash
gcloud iam workload-identity-pools providers describe github \
  --project="$PROJECT_ID" --location=global \
  --workload-identity-pool=github \
  --format="value(name)"
# => projects/<PROJECT_NUMBER>/locations/global/workloadIdentityPools/github/providers/github
```

**6. Set the two GitHub repo *variables*** (Settings → Secrets and variables → Actions →
**Variables** — these are identifiers, not credentials, so they are variables not secrets):

```bash
gh variable set WIF_PROVIDER --body "$(gcloud iam workload-identity-pools providers describe github \
  --project="$PROJECT_ID" --location=global --workload-identity-pool=github --format='value(name)')"
gh variable set DEPLOY_SA --body "$DEPLOY_SA"
```

Setting `WIF_PROVIDER` is what flips `deploy-api.yml` from inert to active (its `if: vars.WIF_PROVIDER
!= ''` guard). The remaining API variables/secrets are tracked in
[DEPLOYMENT_RUNBOOK.md](DEPLOYMENT_RUNBOOK.md).

### 2b. Firebase Hosting deploy credential (`FIREBASE_SERVICE_ACCOUNT`)

`deploy-web.yml` uses `FirebaseExtended/action-hosting-deploy`, which authenticates with a **service
account JSON key** passed as the `FIREBASE_SERVICE_ACCOUNT` GitHub *secret* (the only secret the web
deploy needs; all `VITE_*` values are public repo variables).

Easiest path — let the Firebase CLI create the SA, grant Hosting roles, and mint the key in one step:

```bash
cd web
npx firebase login
npx firebase init hosting:github   # creates the SA + key and offers to store it as a repo secret
```

Or do it manually with `gcloud` and store the secret via `gh`:

```bash
PROJECT_ID=skopeo-prod
gcloud iam service-accounts create firebase-deployer \
  --project="$PROJECT_ID" --display-name="Firebase Hosting deployer"
FB_SA="firebase-deployer@${PROJECT_ID}.iam.gserviceaccount.com"

# Roles needed to deploy Hosting releases
for ROLE in roles/firebasehosting.admin roles/firebase.viewer; do
  gcloud projects add-iam-policy-binding "$PROJECT_ID" \
    --member="serviceAccount:${FB_SA}" --role="$ROLE" --condition=None
done

# Generate a JSON key and store its FULL CONTENTS as the GitHub secret, then delete the local file
gcloud iam service-accounts keys create /tmp/firebase-sa.json --iam-account="$FB_SA"
gh secret set FIREBASE_SERVICE_ACCOUNT < /tmp/firebase-sa.json
rm -f /tmp/firebase-sa.json
```

> `action-hosting-deploy` expects the **raw JSON key contents** (not a base64 blob) in the secret.
> Setting `VITE_FIREBASE_PROJECT_ID` is what activates `deploy-web.yml` (its
> `if: vars.VITE_FIREBASE_PROJECT_ID != ''` guard).

### 2c. `production` approval gate (GitHub Environment)

`deploy-api.yml` runs in `environment: production`, so every API deploy pauses for a manual approval
before it releases. Configure it once:

1. Repo **Settings → Environments → New environment** → name it exactly **`production`**.
2. Enable **Required reviewers** and add at least one reviewer (yourself for a solo project).
3. Save.

Caveat: by default GitHub **allows the actor who triggered the run to approve their own deploy** if
they're listed as a reviewer. For a solo dev that's the only workable setup (self-approval acts as a
deliberate "yes, ship it" checkpoint); on a team, add reviewers other than the typical pusher.

Both `deploy-api.yml` **and** `deploy-web.yml` run in `environment: production`, so a release waits for
your approval before **either** ships — the API and web release together. (Each is a separate pending
deployment in the run, so you approve both.)

### 2d. Deploy workflow

`.github/workflows/deploy-api.yml` now exists (a fuller version of the sketch below: it adds a
`production` approval gate, a `workflow_dispatch` trigger, an `if: vars.WIF_PROVIDER != ''` guard so
it stays inert until configured, and passes runtime env/secrets explicitly). The skeleton:

```yaml
name: Deploy API to Cloud Run

on:
  push:
    branches: [main]
    paths:                      # API-only — see the monorepo note in Phase 3
      - 'src/**'
      - 'build.gradle.kts'
      - 'settings.gradle.kts'
      - 'gradle/**'
      - 'Dockerfile'

permissions:
  contents: read
  id-token: write               # required for WIF OIDC

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: google-github-actions/auth@v2
        with:
          workload_identity_provider: ${{ vars.WIF_PROVIDER }}
          service_account: ${{ vars.DEPLOY_SA }}
      - uses: google-github-actions/setup-gcloud@v2
      - run: gcloud run deploy skopeo --source . --region=asia-southeast1 --quiet
```

Notes:
- `--source .` builds via Cloud Build and respects the committed `.gcloudignore` (so `web/` and docs aren't uploaded).
- The real workflow sets **all** runtime config explicitly on every deploy: non-secret env (`FIREBASE_PROJECT_ID`, `DATABASE_URL`, `DATABASE_USER`, `WEB_ORIGINS`) via `--set-env-vars` from repo *variables*, and `DATABASE_PASSWORD` + `ADMIN_EMAILS` via `--set-secrets` from Secret Manager (`skopeo-db-password`, `skopeo-admin-emails`). Repo variables/secrets are therefore the source of truth — see [DEPLOYMENT_RUNBOOK.md](DEPLOYMENT_RUNBOOK.md).
- `DATABASE_URL` is the **private-IP** form (`jdbc:postgresql://<PRIVATE_IP>:5432/SkopeoDb`) over direct VPC egress — the build has no Cloud SQL socket factory. `--add-cloudsql-instances` is passed (harmless) but is not how the JDBC connection is made.
- Migrations still run on app startup (Flyway in `DatabaseConfig.init`), so no separate migration step. Flyway is forward-only — see the rollback note in [DEPLOYMENT_RUNBOOK.md](DEPLOYMENT_RUNBOOK.md).

Until the WIF resources + repo variables exist the job skips, so deploy manually per
[DEPLOYMENT_GCP.md](DEPLOYMENT_GCP.md). The go-live config (variables, secrets, custom domain,
approval gate, status) is tracked in [DEPLOYMENT_RUNBOOK.md](DEPLOYMENT_RUNBOOK.md).

---

## Phase 3 — web UI CI/CD (`web/` now exists)

The monorepo holds both the API and the SPA. This phase is now **implemented**:

- **Web CI:** the `web` job in `ci.yml` runs on every PR/push — `npm ci`, lint,
  type-check (which regenerates the orval API client from the backend OpenAPI
  spec, proving the frontend stays in sync with the contract), the Vitest suite
  (`npm run test:ci`), and `npm run build`. It publishes a "Web Test Report"
  check and uploads frontend coverage to Codecov under the `frontend` flag.
- **Web CD:** `.github/workflows/deploy-web.yml` deploys to **Firebase Hosting**
  on pushes to `main` that touch `web/**`, the OpenAPI spec, `firebase.json`, or
  the workflow itself (path-filtered). It uses `actions/setup-node@v5` (Node 22)
  to build, then `FirebaseExtended/action-hosting-deploy@v0` to deploy the
  `live` channel. The job is **inert until configured** — guarded by
  `if: ${{ vars.VITE_FIREBASE_PROJECT_ID != '' }}` — so merging it never
  produces a red run before the Firebase vars/secrets exist. `VITE_*` client
  values come from repo *variables* (safe in the bundle); the deploy service
  account is the only secret (`FIREBASE_SERVICE_ACCOUNT`).
- The API deploy workflow (Phase 2) keeps its own `paths:` filters so an API
  change doesn't trigger a web deploy and vice-versa.

---

## Recommended order of work

1. Add `.github/workflows/ci.yml` + the `gradle.properties` toolchain line (1a).
2. Push, confirm CI goes green on a test PR.
3. Enable branch protection on `main` (1b).
4. Route the pending commits through the first PR (1c).
5. ✅ Phase 2 — `deploy-api.yml` is in place (WIF, `production` approval gate, deploy-from-source);
   **inert until** `WIF_PROVIDER` + the GCP resources exist (see [DEPLOYMENT_RUNBOOK.md](DEPLOYMENT_RUNBOOK.md)).
6. ✅ Web CI/CD (Phase 3) — `web` job in `ci.yml` + `deploy-web.yml` (now in place).

---

## References

- [gradle/actions — setup-gradle](https://github.com/gradle/actions) · [actions/setup-java](https://github.com/actions/setup-java) · [GitHub Docs — building Java with Gradle](https://docs.github.com/en/actions/automating-builds-and-tests/building-and-testing-java-with-gradle)
- [google-github-actions/auth — Workload Identity Federation](https://github.com/google-github-actions/auth) · [Cloud Run CD via WIF (2026 guide)](https://oneuptime.com/blog/post/2026-02-17-how-to-set-up-continuous-deployment-to-cloud-run-using-github-actions-and-workload-identity-federation/view)
- Related: [DEPLOYMENT_GCP.md](DEPLOYMENT_GCP.md) · [WEB_UI_ARCHITECTURE.md](../architecture/WEB_UI_ARCHITECTURE.md) · [JVM_COMPATIBILITY.md](JVM_COMPATIBILITY.md) (the detekt/JDK pin)
