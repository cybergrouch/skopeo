# Skopeo Documentation

Docs are grouped by audience, then by topic.

## `product/` — what the product does (domain & roadmap)

- [RATING_CALCULATION_ALGORITHM.md](product/RATING_CALCULATION_ALGORITHM.md) — the NTRP rating algorithm: formulas, dominance, upsets, worked examples.
- [RATING_SMOOTHING.md](product/RATING_SMOOTHING.md) — optional smoothing of rating changes (averaging against the previous rating).
- [MATCH_SCORE_CORRECTION.md](product/MATCH_SCORE_CORRECTION.md) — how correcting an already-rated match's score affects ratings, ranking points, confidence, and clamping (#776).
- [USTA_FEATURE_COMPARISON.md](product/USTA_FEATURE_COMPARISON.md) — roadmap study comparing Skopeo to USTA's rating system (adopted / in-pipeline / not-planned).
- [RATING_SIMULATION_STUDIES.md](product/RATING_SIMULATION_STUDIES.md) — matchup-matrix + Monte Carlo findings, K-factor sensitivity (0.16 vs 0.016), and where a configurable K should reside.
- [PLACEHOLDER_ACCOUNTS.md](product/PLACEHOLDER_ACCOUNTS.md) — login-less "dummy" players + the secret-code claim/adopt (merge-into-empty) flow (#496).
- [ROADMAP.md](product/ROADMAP.md) — feature roadmap: shipped ✅, remaining MVP, and post-MVP/aspirational work (extracted from the root README).
- [STANDINGS_PHASE_2_DESIGN.md](product/STANDINGS_PHASE_2_DESIGN.md) — design of the per-band "Ranking Race" standings.
- [TOURNAMENTS_CIRCUITS_AND_OPEN_PLAY_POINTS.md](product/TOURNAMENTS_CIRCUITS_AND_OPEN_PLAY_POINTS.md) — tournaments/circuits model + open-play points (design of record, #525).
- [POINTS_AWARDING_AND_BUDGET.md](product/POINTS_AWARDING_AND_BUDGET.md) — points-awarding design (⚠️ per-club budget premise superseded by #559 — see the banner in-doc).
- [UI_SEASONAL_THEMING.md](product/UI_SEASONAL_THEMING.md) — admin-selectable seasonal UI themes (#378).
- [EVENT_PAYMENTS.md](product/EVENT_PAYMENTS.md) — Philippine QR-payment exploration for event fees (design only).
- Rating studies: [RATING_CONFIDENCE.md](product/RATING_CONFIDENCE.md) · [RATING_CONFIDENCE_SPARSITY.md](product/RATING_CONFIDENCE_SPARSITY.md) · [RATING_HANDICAP.md](product/RATING_HANDICAP.md) · [POINTS_RANKING_SIMULATION_STUDY.md](product/POINTS_RANKING_SIMULATION_STUDY.md) · [DOUBLES_RATING_STUDY.md](product/DOUBLES_RATING_STUDY.md) · [DOUBLES_DOMINANCE_STUDY.md](product/DOUBLES_DOMINANCE_STUDY.md).

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
- [database-schema.md](engineering/architecture/database-schema.md) — schema, ER diagram, constraints, and domain lifecycle state diagrams.
- [LAYERED_ARCHITECTURE.md](engineering/architecture/LAYERED_ARCHITECTURE.md) — the `routes → service → repository` layering enforced by ArchUnit.
- [CLIENT_API_AUTH.md](engineering/architecture/CLIENT_API_AUTH.md) — partner application identity: hashed API keys, scopes, per-client rate limits (#225).
- [BFF_VS_ENGINE.md](engineering/architecture/BFF_VS_ENGINE.md) — proposed backend-for-frontend vs core-engine split.
- [INPUT_VALIDATION.md](engineering/architecture/INPUT_VALIDATION.md) — the "validate shape at routes, business rules in services" convention (#116).

#### Architecture Decision Records

The design/decision records above, with status:

| Record | Status |
|---|---|
| [ORM_EVALUATION.md](engineering/architecture/ORM_EVALUATION.md) | ✅ Accepted — stay on Exposed |
| [ADMIN_BOOTSTRAP.md](engineering/architecture/ADMIN_BOOTSTRAP.md) | ✅ Accepted — verified-email allowlist (partly implemented) |
| [CLIENT_API_AUTH.md](engineering/architecture/CLIENT_API_AUTH.md) | ✅ Implemented — API keys/scopes/rate limits shipped (#225) |
| [INPUT_VALIDATION.md](engineering/architecture/INPUT_VALIDATION.md) | ✅ Accepted — standing convention (#116) |
| [BFF_VS_ENGINE.md](engineering/architecture/BFF_VS_ENGINE.md) | 🟡 Proposed — not implemented |

### `operations/` — deploy, run, tooling

- [MANUAL_TESTING_GUIDE.md](engineering/operations/MANUAL_TESTING_GUIDE.md) — end-to-end manual test runbook (local Docker + GCP).
- [DEPLOYMENT_GCP.md](engineering/operations/DEPLOYMENT_GCP.md) — Cloud Run + Cloud SQL + Firebase Hosting deployment.
- [DOCKER_DEPLOYMENT.md](engineering/operations/DOCKER_DEPLOYMENT.md) — container build & local Docker run.
- [database-setup.md](engineering/operations/database-setup.md) — local DB setup, migrations, sample queries.
- [DB_MIGRATIONS.md](engineering/operations/DB_MIGRATIONS.md) — Flyway conventions + the checklist for constraint-tightening migrations (must carry their own backfill; CI migrates an empty DB, #799), with the V44 post-mortem and an audit of V1–V44.
- [CICD.md](engineering/operations/CICD.md) — GitHub Actions CI/CD plan.
- [JVM_COMPATIBILITY.md](engineering/operations/JVM_COMPATIBILITY.md) — Java/Gradle/detekt version strategy.
- [LOGGING_AND_METRICS.md](engineering/operations/LOGGING_AND_METRICS.md) — structured JSON logging for Cloud
  Logging, the severity mapping, and why there is no `/metrics` endpoint.
- [GIT_HOOKS.md](engineering/operations/GIT_HOOKS.md) — pre-commit hook setup.
- [DEPLOYMENT_RUNBOOK.md](engineering/operations/DEPLOYMENT_RUNBOOK.md) — step-by-step release/deploy runbook.
- [infra/monitoring/](../infra/monitoring/README.md) — Cloud Monitoring as code: the uptime check, the
  two launch alert policies, the log-based per-endpoint metrics and the dashboard, with one apply
  script (#808/#809).
- [LOCAL_DEMO_AND_TEST_DATA.md](engineering/operations/LOCAL_DEMO_AND_TEST_DATA.md) — seeding a local demo + test data.

### `quality/` — testing & code style

- [TESTING_STRATEGY.md](engineering/quality/TESTING_STRATEGY.md) — testing pyramid and approach.
- [CODE_COVERAGE.md](engineering/quality/CODE_COVERAGE.md) — JaCoCo thresholds and exclusions.
- [LINTING_NAMED_PARAMETERS.md](engineering/quality/LINTING_NAMED_PARAMETERS.md) — the `NamedArguments` detekt rule and rationale.

### `engineering/`

- [IMPLEMENTATION_LOG.md](engineering/IMPLEMENTATION_LOG.md) — point-in-time log of past implementation work.
