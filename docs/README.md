# Skopeo Documentation

Docs are grouped by audience, then by topic.

## `product/` — what the product does (domain & roadmap)

- [RATING_CALCULATION_ALGORITHM.md](product/RATING_CALCULATION_ALGORITHM.md) — the NTRP rating algorithm: formulas, dominance, upsets, worked examples.
- [RATING_SMOOTHING.md](product/RATING_SMOOTHING.md) — optional USTA-style smoothing of rating changes.
- [USTA_FEATURE_COMPARISON.md](product/USTA_FEATURE_COMPARISON.md) — roadmap study comparing Skopeo to USTA NTRP (adopted / in-pipeline / not-planned).

## `engineering/` — how it's built and run

### `api/` — HTTP API references

- [API_DOCUMENTATION.md](engineering/api/API_DOCUMENTATION.md) — overall API reference (request/response, examples).
- [API_MODEL_PROPOSAL.md](engineering/api/API_MODEL_PROPOSAL.md) — the rating/data model as implemented.
- [RATINGS_API.md](engineering/api/RATINGS_API.md) · [MATCHES_API.md](engineering/api/MATCHES_API.md) · [CAPABILITIES_API.md](engineering/api/CAPABILITIES_API.md) · [CONTACT_INFORMATION_API.md](engineering/api/CONTACT_INFORMATION_API.md) · [USER_NAMES_API.md](engineering/api/USER_NAMES_API.md) — per-resource endpoint docs.

### `architecture/` — design & decisions

- [AUTHENTICATION.md](engineering/architecture/AUTHENTICATION.md) — Firebase auth (IDaaS) + in-house capability authorization.
- [AUDIT_TRAIL.md](engineering/architecture/AUDIT_TRAIL.md) — the pure-function-plus-audit-trail calculator pattern.
- [WEB_UI_ARCHITECTURE.md](engineering/architecture/WEB_UI_ARCHITECTURE.md) — React/Vite web UI decisions and roadmap.
- [ORM_EVALUATION.md](engineering/architecture/ORM_EVALUATION.md) — Exposed vs jOOQ vs Jimmer decision record.
- [database-schema.md](engineering/architecture/database-schema.md) — schema, ER diagram, constraints.

### `operations/` — deploy, run, tooling

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
