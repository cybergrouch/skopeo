# Skopeo Documentation

Docs are grouped by audience, then by topic.

## `product/` — what the product does (domain & roadmap)

- [RATING_CALCULATION_ALGORITHM.md](product/RATING_CALCULATION_ALGORITHM.md) — the NTRP rating algorithm: formulas, dominance, upsets, worked examples.
- [RATING_SMOOTHING.md](product/RATING_SMOOTHING.md) — optional USTA-style smoothing of rating changes.
- [USTA_FEATURE_COMPARISON.md](product/USTA_FEATURE_COMPARISON.md) — roadmap study comparing Skopeo to USTA NTRP (adopted / in-pipeline / not-planned).
- [RATING_SIMULATION_STUDIES.md](product/RATING_SIMULATION_STUDIES.md) — matchup-matrix + Monte Carlo findings, K-factor sensitivity (0.16 vs 0.016), and where a configurable K should reside.

## `engineering/` — how it's built and run

### `api/` — HTTP API references

The **canonical, complete API reference is the OpenAPI spec** (`src/main/resources/openapi/documentation.yaml`, verified by `OpenAPIIntegrationTest`). The pages below are human-readable companions.

- [API_DOCUMENTATION.md](engineering/api/API_DOCUMENTATION.md) — the stateless ranking calculator (`/api/v1/calculate-ranking`) + pointer to the OpenAPI spec.
- [API_MODEL_PROPOSAL.md](engineering/api/API_MODEL_PROPOSAL.md) — the rating/data model as implemented.
- [RATINGS_API.md](engineering/api/RATINGS_API.md) — ratings & assessment, **re-rate requests** (#140), and the rating-calculation trigger.
- [MATCHES_API.md](engineering/api/MATCHES_API.md) — fixtures & results, the two-dimension `matchFormat`/`matchType` model (#108), public match pages (#136), match calculation detail (#97), and the **events** tie-in (#138).
- [CAPABILITIES_API.md](engineering/api/CAPABILITIES_API.md) — roles: PLAYER/HOST/CLUB_OWNER/**RATER** (#106)/**RESEARCHER** (#107)/ADMINISTRATOR.
- [CONTACT_INFORMATION_API.md](engineering/api/CONTACT_INFORMATION_API.md) · [USER_NAMES_API.md](engineering/api/USER_NAMES_API.md) — user sub-resources.
- Audit / activity log (#100/#102): the domain audit log + read API (`/api/v1/audit`) are documented in [AUDIT_TRAIL.md](engineering/architecture/AUDIT_TRAIL.md) (see *architecture* below).

### `architecture/` — design & decisions

- [AUTHENTICATION.md](engineering/architecture/AUTHENTICATION.md) — Firebase auth (IDaaS) + in-house capability authorization.
- [ADMIN_BOOTSTRAP.md](engineering/architecture/ADMIN_BOOTSTRAP.md) — how the first/break-glass administrator is established (verified-email allowlist via env).
- [AUDIT_TRAIL.md](engineering/architecture/AUDIT_TRAIL.md) — two distinct concepts: the pure-function calculator audit trail, **and** the persisted domain audit/activity log with its admin read API (#100/#102).
- [WEB_UI_ARCHITECTURE.md](engineering/architecture/WEB_UI_ARCHITECTURE.md) — React/Vite web UI decisions and roadmap.
- [ORM_EVALUATION.md](engineering/architecture/ORM_EVALUATION.md) — Exposed vs jOOQ vs Jimmer decision record.
- [database-schema.md](engineering/architecture/database-schema.md) — schema, ER diagram, constraints.

### `operations/` — deploy, run, tooling

- [MANUAL_TESTING_GUIDE.md](engineering/operations/MANUAL_TESTING_GUIDE.md) — end-to-end manual test runbook (local Docker + GCP).
- [DEPLOYMENT_GCP.md](engineering/operations/DEPLOYMENT_GCP.md) — Cloud Run + Cloud SQL + Firebase Hosting deployment.
- [DOCKER_DEPLOYMENT.md](engineering/operations/DOCKER_DEPLOYMENT.md) — container build & local Docker run.
- [database-setup.md](engineering/operations/database-setup.md) — local DB setup, migrations, sample queries.
- [CICD.md](engineering/operations/CICD.md) — GitHub Actions CI/CD plan.
- [JVM_COMPATIBILITY.md](engineering/operations/JVM_COMPATIBILITY.md) — Java/Gradle/detekt version strategy.
- [LOGGING_AND_METRICS.md](engineering/operations/LOGGING_AND_METRICS.md) — logging and Prometheus metrics.
- [GIT_HOOKS.md](engineering/operations/GIT_HOOKS.md) — pre-commit hook setup.

### `quality/` — testing & code style

- [TESTING_STRATEGY.md](engineering/quality/TESTING_STRATEGY.md) — testing pyramid and approach.
- [CODE_COVERAGE.md](engineering/quality/CODE_COVERAGE.md) — JaCoCo thresholds and exclusions.
- [LINTING_NAMED_PARAMETERS.md](engineering/quality/LINTING_NAMED_PARAMETERS.md) — the `NamedArguments` detekt rule and rationale.

### `engineering/`

- [IMPLEMENTATION_LOG.md](engineering/IMPLEMENTATION_LOG.md) — point-in-time log of past implementation work.
