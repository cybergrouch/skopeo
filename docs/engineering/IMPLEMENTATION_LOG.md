# Skopeo Implementation Log

This document tracks major implementation milestones and architectural decisions, reconstructed from git history. Entries are chronological (oldest first). Dates are the dates the work landed on `main`; PR/issue numbers are noted where they exist.

> Note: an earlier revision of this log described the persistence schema with `players` / `player_kyc` / `player_ratings` tables. Those names were never correct — the schema uses a unified `users` aggregate (`users`, `user_kyc`, `user_ratings`, `user_rating_history`, `user_names`, `user_identities`, `user_capabilities`). This log reflects the actual table names.

---

## 2026-06-01: Stateless v1 ranking calculator

- Initialized the project, migrated from Spring Boot to Ktor, and added Docker containerization.
- Implemented the ranking-calculation API and data model: `POST /api/v1/calculate-ranking` over a map-based, team-oriented request schema (designed so doubles can be added without breaking the contract).
- Adopted a pure-function core with a monadic audit trail — `RankingCalculator.calculate()` returns a `RankingCalculationResult` (response + `AuditTrail`); the route layer logs the audit entries.
- Encapsulated rating as a dedicated `BigDecimal`-backed object (serialized as strings) for money-style precision; added ktlint, pre-commit hooks, and code-formatting automation.

## 2026-06-02 – 2026-06-05: Algorithm tuning and documentation

- Organized the calculator under a versioned package (`service/calculator/impl/v1/`) behind the `RankingCalculator` interface.
- Calibrated the Elo-style K-factor for the NTRP/UTR range vs the chess range; added system-specific K-factors (UTR has since been removed — see 2026-06-23).
- Added USTA NTRP Dynamic-style rating smoothing (`options.smoothingFactor`) with edge-case coverage, migrated tests to Kotest assertions, and enforced named parameters via detekt.
- Added comprehensive algorithm documentation (later renamed `RATING_CALCULATION_ALGORITHM.md`) and a USTA feature-comparison document.

## 2026-06-06 – 2026-06-10: API hardening and audit detail

- Added OpenAPI/Swagger documentation support.
- Added route integration tests; refined the MVP roadmap and doubles-support design notes in the docs.
- Renamed the project from "Tennis Levelr" to **Skopeo**; added `CLAUDE.md`.
- Averaged per-set dominance and logged all rating-change factors in the audit trail.
- Made winner/loser team IDs explicit on score payloads, with value-derived defaults (`feat!`).

## 2026-06-09: Initial PostgreSQL persistence infrastructure

- Stood up the persistence stack: PostgreSQL + Flyway migrations + Exposed ORM + HikariCP connection pooling, wired into application startup via `DatabaseConfig.kt`, with a shutdown hook for graceful pool closure.
- Added `docker-compose` PostgreSQL (and optional pgAdmin) services and database setup/schema documentation.
- This established the persistence foundation; the concrete schema was reworked into the unified-user model shortly after (see 2026-06-21+). UTR-specific columns from this era were later dropped (see 2026-06-23).

## 2026-06-21 – 2026-06-22: User-management foundation (#1–#8)

- Added the initial infrastructure PR (#1): GitHub Actions CI running `./gradlew check`, plus CORS for the web UI and exclusion of `web/` from the API build context.
- **User-management schema** (#2): a unified `users` aggregate with `user_names`, `user_identities`, `contact_information`, and `user_capabilities` — replacing any notion of a standalone `players` table. Recorded the Flyway-over-Liquibase rationale.
- **Firebase auth** (#3): verify Firebase JWTs; added an authenticated `/users/me` probe and auth smoke-test scripts.
- **User-aggregate persistence** (#4-prep, #5): Exposed-backed persistence with Testcontainers, then user provisioning + CRUD over the persisted store (`UserRoutes`/`UserService`/`UserRepository`).
- **Contacts** (#6): contact-information API with admin-driven verification; append-only (disable + add, no edits).
- **Names** (#7): user-names API, append-only, multiple names with an explicit `DISPLAY` name type.
- **Capabilities** (#8): admin-only, append-only capability (role) management API — the basis for capability-based authorization (PLAYER / HOST / CLUB_OWNER / ADMINISTRATOR, later RATER / RESEARCHER).
- Added CI coverage/test reporting (JaCoCo summary + JUnit/dorny + Codecov diff coverage) and AGPL-3.0 licensing across PRs #9–#13.

## 2026-06-23: Ratings, matches, calculation trigger, and the web client (#4, #2-web)

- **Ratings & assessment foundation** (PR1 of #4): admin-set initial ratings (`user_ratings`), the pending-assessment queue (users without a rating), and rating history (`user_rating_history`).
- **Match fixtures & results** (PR2a of #4): HOST/ADMINISTRATOR create fixtures and upload results (`matches`, `match_sets`, `match_set_tiebreaks`, `teams`, `team_users`); recording a result does not compute ratings.
- **Rating-calculation trigger** (PR2b of #4): `RatingCalculationService` processes pending matches oldest→newest, carrying ratings forward through an in-memory snapshot and reusing the stateless `RankingCalculator`. **Dry-run is the default**; only an explicit `{"dryRun": false}` persists.
- **Web client** (#2): React + Vite scaffold with Firebase sign-up, Vitest unit tests, Codecov flags, and Firebase Hosting CI.
- **Capability-gated dashboard** (web PRs A–D): Profile tab, Admin tab (pending assessment + calculation), Match tab + admin role grants.
- **User search** (PR C): user search + host-scoped match oversight, id-resolution lookup on `GET /users`, and typo-tolerant name search via `pg_trgm`.
- Renamed `gender` → `sex` (Male/Female only), added research-screen search filters (sex/age/rating), and made **sex + date of birth required at sign-up** (#25).

## 2026-06-23: NTRP-only (UTR removal)

- Removed UTR from the calculator and core model and dropped the rating-system concept entirely — the system is now NTRP-only by design (#26). Vestigial UTR columns and comments were dropped, and the schema was consolidated back into a single `V1` migration.

## 2026-06-24 – 2026-06-25: Auth/onboarding polish, public profiles, simulation studies

- Added authentication & authorization architecture docs and an ORM evaluation (Exposed vs jOOQ vs Jimmer); added Facebook sign-up/login and a refreshed (secret-redacted) manual testing guide.
- Added NTRP rating simulation studies (matchup matrix + Monte Carlo + K-factor reports) with embedded charts.
- **Onboarding hardening** (#42, #43): require a display name on manual sign-up; route authenticated-but-unprovisioned users to complete-profile; recover orphaned accounts.
- **Admin bootstrap** (#45): bootstrap ADMINISTRATOR via a verified-email allowlist, with a decision record.
- Added gitleaks secret scanning (CI + pre-commit) and Firebase web API-key hardening docs.
- **Match formats** (#54): single-set match format support.
- **Profile / shareable identity** (#55, #56, #61): profile avatar; shareable short player code with search-by-code; auth-gated player-profile deep link + QR.

## 2026-06-26: Player-facing history, search, invites, calculation breakdown

- **Player match history** (#65) with at-the-time NTRP bands.
- **Pending-assessment cards** (#67): richer, paged admin cards; optional self-rating at sign-up for admin approve/override (#75).
- **Rating history scope** (#73): owner + admin visibility, full value + band, band-change highlighting.
- **Awaiting-results UX** (#71): badge fixtures as Overdue/Today/Upcoming; research result cards (#64) link to public profiles.
- **Invites** (#74): invite-only manual onboarding (backend + web); test-user helper scripts seed an invite so manual sign-ups pass the gate.
- **Calculation breakdown** (#89/#92, #97/#98): surface the breakdown in the pending-calc detail view, and link rating-history entries to their match + persisted calculation. Consolidated migrations V2–V5 back into V1 (#68).
- **Unified search** (#86/#94): name-or-code-prefix player search; search-suggestion enrichment (#87) and pending-calculation match cards (#84); invite filtering by status (#85/#93).
- Enforced layered package architecture with ArchUnit (#69).

## 2026-06-27: Audit log, admin player management, v2-breakdown persistence

- **Audit log** (#100/#102): provenance for domain actions (`audit_log` table, V2), an admin trace-viewer read API, and an Activity Log tab; wired remaining audit events for user/name/contact/match.
- **Admin "Manage player" panel** (#96): edit profile, rating, and roles.
- Show date of birth and sex (read-only) on the own profile (#95); app logo + favicon set (#109).
- Consolidated the V2 calculation-breakdown migration into V1.

## 2026-06-28: Competitive context, duplicates, RATER/RESEARCHER, seeding, standings, v2 calculator, CD

- **Match occasion / competitive context** (#108): renamed match dimensions — `matchFormat` (SINGLES/DOUBLES) vs `matchType` (occasion) — with a per-occasion rating factor.
- **Duplicate handling** (#124, #126): duplicate-profile rectification, and duplicate-account detection (phone match + manual flag, `duplicate_candidates` table, V5) feeding rectification.
- **RATER capability** (#106): dedicated Ratings tab (Phase 1).
- **RESEARCHER capability** (#107): gates the Research tab (monetization-ready); a default player can search but cannot resolve ids.
- **Rating band UX** (#114): rating-band "speed meter" on the own profile (relative position, no exact value); moved the rating card into the Profile identity card (#111).
- **HOST seeding generator** (#111): named player lists → sorted, CSV-exportable seeding (`player_lists`, `player_list_members`, `seedings`, `seeding_entries`, V8).
- **Ranking Race standings** (#113): per-band standings, Phase 1, ratings-derived.
- **Per-set v2 calculator** (#110): per-set sequential rating calculation, behind the `RankingCalculator` interface.
- **API CD** (#130): continuous deployment to Cloud Run + deployment runbook; manual testing guide updated for the new features.

## 2026-06-29: Public pages, events, re-rate requests, seeding search

- **Match public code + public match page** (#136); QR codes on public Profile and public Match pages (#137).
- **Event Organizer** (#138): events with participants and event-scoped fixtures (`events`, `event_participants`, V11); public event page + QR (phase 2).
- **Re-rate requests** (#140): players request a re-rate, RATER approves/denies (`rating_requests` table, V12).
- **Invites & Activity Log tabs** (#132, #134, #135): block invites to emails already on an active account; promote Invites and Activity Log to their own tabs (25 rows/page).
- **Seeding tab** (#148): explicit Search + checkbox multi-select + Add to List.

---

## Current schema (tables)

For reference, the live schema (across migrations V1–V12) comprises:

- **Users aggregate** (V1): `users`, `user_names`, `user_identities`, `contact_information`, `user_capabilities`, `user_kyc`.
- **Ratings** (V1): `user_ratings`, `user_rating_history`.
- **Matches** (V1): `teams`, `team_users`, `matches`, `match_sets`, `match_set_tiebreaks`.
- **Onboarding** (V1): `invites`.
- **Audit** (V2): `audit_log`.
- **Duplicates** (V5): `duplicate_candidates`.
- **Seeding** (V8): `player_lists`, `player_list_members`, `seedings`, `seeding_entries`.
- **Events** (V11): `events`, `event_participants`.
- **Re-rate requests** (V12): `rating_requests`.

## References

- [Database Schema Documentation](architecture/database-schema.md)
- [Database Setup Guide](operations/database-setup.md)
- [Rating Calculation Algorithm](../product/RATING_CALCULATION_ALGORITHM.md)
- [Audit Trail](architecture/AUDIT_TRAIL.md)
- [Layered Architecture](architecture/LAYERED_ARCHITECTURE.md)
- [Web UI Architecture](architecture/WEB_UI_ARCHITECTURE.md)
