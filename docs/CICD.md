# CI/CD Plan

How Skopeo builds, gates, and deploys via GitHub Actions — phased so the pieces that unblock a proper PR workflow come first, and automated deployment follows when it earns its keep.

Context: solo developer, pilot stage, GitHub-hosted repo, cost-sensitive. The API deploys to GCP Cloud Run + Cloud SQL ([DEPLOYMENT_GCP.md](DEPLOYMENT_GCP.md)); the web UI (later) to Firebase Hosting ([WEB_UI_ARCHITECTURE.md](WEB_UI_ARCHITECTURE.md)).

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
      - uses: actions/checkout@v4

      # Install BOTH JDK 17 and 21. The last entry (21) becomes JAVA_HOME, so
      # Gradle's daemon — and detekt — run on 21. This is required: detekt 1.23.8
      # crashes on JDK 25+, which is exactly why gradle-daemon-jvm.properties pins
      # the daemon to 21. The build still compiles with the Java 17 toolchain.
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: |
            17
            21

      - uses: gradle/actions/setup-gradle@v4   # Gradle caching, build scans

      - name: Build, lint, detekt, test, verify coverage
        run: ./gradlew check
```

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

High level (verify exact flags against the [linked WIF guide](https://github.com/google-github-actions/auth)):

```bash
# A dedicated deploy service account
gcloud iam service-accounts create github-deployer --project=skopeo-prod

# Roles it needs for `gcloud run deploy --source` (Cloud Build + deploy)
#   roles/run.admin, roles/iam.serviceAccountUser,
#   roles/cloudbuild.builds.editor, roles/artifactregistry.writer
# (grant each with: gcloud projects add-iam-policy-binding skopeo-prod ...)

# Workload Identity Pool + GitHub OIDC provider, bound to THIS repo only
gcloud iam workload-identity-pools create github --location=global
gcloud iam workload-identity-pools providers create-oidc github \
  --location=global --workload-identity-pool=github \
  --issuer-uri="https://token.actions.githubusercontent.com" \
  --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository"
# Bind the SA so only <owner>/skopeo can impersonate it.
```

Store the **provider resource name** and the **deploy SA email** as GitHub repo *variables* (not secrets — they're identifiers, not credentials).

### 2b. Deploy workflow

Create `.github/workflows/deploy-api.yml`:

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
      - uses: actions/checkout@v4
      - uses: google-github-actions/auth@v2
        with:
          workload_identity_provider: ${{ vars.WIF_PROVIDER }}
          service_account: ${{ vars.DEPLOY_SA }}
      - uses: google-github-actions/setup-gcloud@v2
      - run: gcloud run deploy skopeo --source . --region=asia-southeast1 --quiet
```

Notes:
- `--source .` builds via Cloud Build and respects the committed `.gcloudignore` (so `web/` and docs aren't uploaded).
- It **retains the service's existing env vars and secrets** (the `DATABASE_*` config and `skopeo-db-password` set during the manual deploy) unless you override them — so the workflow stays minimal.
- Migrations still run on app startup (Flyway in `DatabaseConfig.init`), so no separate migration step.

Until this is in place, deploy manually per [DEPLOYMENT_GCP.md](DEPLOYMENT_GCP.md) — that's the intended Phase-1-era workflow.

---

## Phase 3 — web UI CI/CD (when `web/` exists)

The monorepo holds both the API and (later) the SPA, so workflows are **path-filtered** to run only what changed:

- **Web CI:** lint + build the SPA on `pull_request` touching `web/**` (`npm ci && npm run build`, plus the OpenAPI type-gen check).
- **Web CD:** deploy to **Firebase Hosting** on merge. `firebase init hosting:github` auto-generates the workflows — including **PR preview deploys** (a temporary URL per PR) and merge-to-live deploy — using a Firebase-managed service account.
- Keep the API workflows' `paths:` filters (Phase 2) so an API change doesn't trigger a web deploy and vice-versa.

---

## Recommended order of work

1. Add `.github/workflows/ci.yml` + the `gradle.properties` toolchain line (1a).
2. Push, confirm CI goes green on a test PR.
3. Enable branch protection on `main` (1b).
4. Route the pending commits through the first PR (1c).
5. *(Later)* WIF + `deploy-api.yml` (Phase 2).
6. *(When `web/` lands)* web CI/CD (Phase 3).

---

## References

- [gradle/actions — setup-gradle](https://github.com/gradle/actions) · [actions/setup-java](https://github.com/actions/setup-java) · [GitHub Docs — building Java with Gradle](https://docs.github.com/en/actions/automating-builds-and-tests/building-and-testing-java-with-gradle)
- [google-github-actions/auth — Workload Identity Federation](https://github.com/google-github-actions/auth) · [Cloud Run CD via WIF (2026 guide)](https://oneuptime.com/blog/post/2026-02-17-how-to-set-up-continuous-deployment-to-cloud-run-using-github-actions-and-workload-identity-federation/view)
- Related: [DEPLOYMENT_GCP.md](DEPLOYMENT_GCP.md) · [WEB_UI_ARCHITECTURE.md](WEB_UI_ARCHITECTURE.md) · [JVM_COMPATIBILITY.md](JVM_COMPATIBILITY.md) (the detekt/JDK pin)
