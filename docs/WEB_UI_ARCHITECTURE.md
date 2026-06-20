# Web UI Architecture & Decisions

How the Skopeo web interface is structured, why, and the steps to build and deploy it. This is a decision record plus a roadmap — read top to bottom before scaffolding the frontend.

Context: the web UI is an **HTML5, mobile-browser-first** interface to the existing Ktor JSON API. It is an **intermediate solution pending a native mobile app**, and is being built by a solo developer at pilot stage (cost-sensitive).

---

## Decisions at a glance

| # | Decision | Choice | Status |
|---|---|---|---|
| D1 | Frontend ↔ backend coupling | **Decoupled** — UI is a pure client of the JSON API | ✅ Decided |
| D2 | Repository layout | **Monorepo** — frontend lives in `web/` beside the API | ✅ Decided |
| D3 | Rendering model | **Static SPA** (not SSR) | ✅ Decided |
| D4 | Mobile strategy | **Responsive + PWA now, Capacitor → native later** | ⭐ Recommended |
| D5 | Production hosting | **Firebase Hosting** (static + CDN, GCP) | ⭐ Recommended |
| D6 | Tech stack | **React + Vite + TypeScript** (see [§5](#5-tech-stack-d6)) | ✅ Decided |
| D7 | Authentication | **Token-based (JWT) via Firebase Auth; verified at the Ktor API** | ⭐ Recommended |

"Decided" = agreed. "Recommended" = proposed and sound, and the [implementation plan](#8-implementation-plan) assumes them — but awaiting your explicit go; see [§9](#9-open-items-and-questions).

---

## 1. Decoupled frontend and backend (D1)

The UI is a separate deployable that talks to the API over HTTP/JSON. It is **not** bundled into the Ktor app.

Why:

- **Multiple clients are coming.** The roadmap includes a native mobile app; both web and mobile consume the same API. A clean API boundary stays clean only if no single client is welded to it.
- **Different runtimes, different cadences.** The API is JVM; the UI is a JS/TS build. The UI changes far more often than the rating engine, and bundling them would force a full JVM redeploy for a CSS tweak.
- **Different ideal hosting.** The API scales to zero on Cloud Run; the UI is static files that belong on a CDN. One unit means neither gets its right home.

**Consequence — CORS:** because UI and API live on different origins, the browser enforces CORS. The Ktor app must install `ktor-server-cors` and allow the UI origin(s). (Not yet present in the API — a required addition before the UI can call it from a browser.)

---

## 2. Monorepo layout (D2)

One repository (`skopeo`). The API stays at the root (don't restructure a working Gradle project); the frontend is added as a self-contained `web/` directory.

```
skopeo/
├── build.gradle.kts        # API (unchanged)
├── Dockerfile              # API container — the ONLY Dockerfile in production
├── docker-compose.yml      # local stack: postgres + api (+ optional web)
├── src/                    # API source
├── docs/
├── .gcloudignore           # ← exclude web/ from API Cloud Build context
├── .dockerignore           # ← exclude web/ from API image build context
└── web/                    # the SPA — package.json, vite config, src, etc.
```

Why monorepo (vs. two repos), for a solo dev at pilot stage:

- **Atomic cross-cutting changes.** An API field rename and its UI update ship in one commit; nothing drifts out of sync.
- **Shared source of truth.** The UI generates its TypeScript types directly from the API's OpenAPI spec (`src/main/resources/openapi/documentation.yaml`) — see [§5](#5-tech-stack-d6). Across two repos this becomes a publish-and-version chore.
- **One of everything to operate** — one clone, issue tracker, board, CI config.
- **Independent deploys are still possible** via path-filtered CI (deploy the API only on `src/**` changes, the UI only on `web/**` changes).

Splitting `web/` into its own repo later is cheap (`git filter-repo` preserves history); merging two repos started apart is the more painful direction — so start together.

### No Docker conflict

There is exactly **one Dockerfile in production** — the API's, at the root. The SPA deploys as static files (no container), so `web/` has no production Dockerfile. Dockerfiles are inert until explicitly invoked with a build context, and `docker-compose` names each service's context explicitly, so even an optional local-dev `web` container cannot collide with the root one. The only housekeeping: add `web/` to `.gcloudignore` and `.dockerignore` so the API's build context stays lean (the API Dockerfile copies specific paths, not `COPY . .`, so web files never leak into the image regardless — this is purely a build-speed nicety).

---

## 3. SPA vs SSR (D3)

The central rendering decision. Both are viable; the choice is dictated by this project's specifics.

### Static SPA (client-rendered — the choice)

The UI is built to static HTML/CSS/JS, served from a CDN; the browser renders everything and fetches data from the API.

| Pros | Cons |
|---|---|
| Matches the static-hosting / no-container / scale-to-zero cost model (≈ free on Firebase) | Larger initial JS payload → slower first paint on low-end phones / weak networks |
| Simplest operations — no server runtime for the UI | No SEO for public pages (irrelevant for an authenticated app) |
| **Same rendering model as a native app** → this code becomes the mobile app via Capacitor | Naive client-side data fetching can waterfall (mitigated by a good data layer) |
| Ideal for an authenticated, interactive dashboard over a JSON API | |

### Server-side rendering (Next.js / Nuxt / SvelteKit SSR)

A Node server renders HTML per request.

| Pros | Cons |
|---|---|
| Faster first contentful paint on slow devices/networks | **Requires a running Node server** → a container on Cloud Run or a platform like Vercel: more cost, ops, and a moving part |
| SEO for public content | **Does not align with the native-app endgame** — native apps render on-device, so SSR logic is throwaway when you move to Capacitor |
| Server-side data composition | Overkill complexity for a solo dev for benefits this app barely needs |

### Decision

**Static SPA.** The clincher is the native-mobile endgame: a native app is client-rendered, so an SPA *is* the future app's rendering model and can be wrapped into it (D4) instead of rebuilt. SSR's first-paint/SEO advantages don't apply to an authenticated dashboard, and its server requirement breaks the cost model. Mobile first-paint — SSR's one real edge here — is addressed instead by a lean bundle, code-splitting, and PWA caching.

**Middle ground available:** a meta-framework in **static/SPA mode** (SvelteKit `adapter-static`, Next.js static export, or Astro) gives you mature routing and DX and can *prerender* the few public pages (e.g. a public leaderboard) while still deploying as static files — framework niceties without a server. This stays compatible with everything above.

---

## 4. Mobile strategy: PWA now, Capacitor later (D4)

The UI is **mobile-browser-first and responsive**, and built as a **PWA** (installable, offline-capable via a service worker, app-like on phones). The PWA *is* the intermediate "mobile app" — usable from any phone browser, no app store needed.

When a true native app is warranted (app-store presence, push notifications, deeper device APIs), **[Capacitor](https://capacitorjs.com/)** wraps the existing web app into iOS/Android native shells reusing essentially the entire codebase — typically minutes of setup, not a rewrite. This is why the SPA choice matters: it makes the "intermediate web UI" the literal foundation of the future app, potentially removing the need for a separate native build entirely.

This is the explicit stepping-stone path: **responsive web → installable PWA → Capacitor-wrapped native app**, one codebase throughout.

---

## 5. Tech stack (D6)

**Decision: React + Vite + TypeScript.** For a solo developer building an intermediate solution, the dominant factor is *help-when-stuck* — React's ecosystem, tutorial depth, and AI-assistant coverage are unmatched, and it has the most-trodden Capacitor (and later React Native) path. The comparison that led here is kept below as the rationale of record; the other two remain valid fallbacks if priorities change.

### Framework — the options considered

| Option | Best when | Trade-off |
|---|---|---|
| **React + Vite** (primary recommendation) | You want the largest ecosystem, easiest help/hiring/AI-tooling support, and the most-trodden Capacitor + (later) React Native path | Larger bundles than Svelte; more boilerplate |
| **Ionic React + Capacitor** | You want native-*feeling* mobile UI components out of the box and the smoothest web→app-store path | Opinionated component system; heavier |
| **SvelteKit (`adapter-static`)** | You prioritize the smallest bundles and best runtime perf on low-end devices, and value DX | Smaller ecosystem; fewer ready-made libraries |

Given a solo developer, an intermediate (not heavily-invested) solution, and the value of ecosystem/help, **React + Vite** is the pragmatic default. **Ionic React** is the choice if you want it to look and feel like a native app from day one. **SvelteKit** if Philippine mobile-network performance is the top priority and you're comfortable with a leaner ecosystem.

### Language & JS/TS knowledge required

All three use **TypeScript** (or JavaScript) as the core language plus HTML and CSS; they differ only in template syntax:

| | Core language | Template / component syntax |
|---|---|---|
| React + Vite | TypeScript | JSX/TSX (HTML-like markup in TS) |
| Ionic React | TypeScript | JSX/TSX + Ionic components |
| SvelteKit | TypeScript | `.svelte` files (HTML + CSS + JS, Svelte 5 runes) |

The Capacitor wrap stays in TS/JS for all three; you'd only touch Swift/Kotlin for a *custom* native feature beyond existing plugins (rare).

Knowledge splits into three layers, only the middle of which varies much by framework:

1. **The language (TypeScript)** — a modest step from Kotlin. Transfers almost 1:1: static types, generics, interfaces, null-safety (`?.`, `??` ≈ Kotlin `?:`), lambdas, collection ops (`map`/`filter`/`reduce`), immutability, async intuition (coroutines → Promises/`async-await`).
2. **The framework's reactivity model** — where JS demand differs:
   - **React / Ionic React — higher.** Markup, state, and logic all live in TS; you rely on closures, immutable updates, and hooks (`useState`/`useEffect`, dependency arrays). Offset by the largest tutorial pool and the strongest AI-assistant support.
   - **SvelteKit — lower.** `.svelte` files are augmented HTML+CSS with minimal JS; compiler-driven reactivity means fewer JS gymnastics. Gentlest for someone newer to JS, but with thinner resources and newer (less-AI-covered) syntax.
3. **The UI domain** (DOM, CSS, responsive layout, browser behavior) — framework-independent, and for a backend developer usually the *bigger* learning curve than the language.

**Takeaway:** TypeScript is the small part of the climb for a Kotlin developer; CSS/layout and the reactivity model are the real new territory. Svelte minimizes the framework-JS you write; React maximizes help-when-stuck. Four mitigants lower the bar for all three: the **typed OpenAPI client** (Kotlin-like end-to-end type safety, fewer hand-written fetches), **TanStack Query** (absorbs async/caching), **Tailwind** (utilities instead of hand-rolled CSS), and AI coding assistants.

### Supporting libraries (stack-agnostic)

- **Build tool:** Vite (current major) — fast dev server, optimized static builds.
- **Data layer:** **TanStack Query** (React Query) — caching, loading/error states, retries for an API-driven app. Strongly recommended over hand-rolled `fetch`.
- **Typed API client from the OpenAPI spec:** generate TypeScript types/client from `src/main/resources/openapi/documentation.yaml` with `openapi-typescript` + `openapi-fetch` (or `orval`). This is the monorepo payoff — the UI's types stay in lockstep with the API contract, and field renames (e.g. `winnerTeamId`) surface as compile errors.
- **Styling:** Tailwind CSS (current major) — fast, mobile-first responsive utilities. Pair with a headless component lib (Radix/Headless UI) or, for Ionic React, its built-in components.
- **Routing:** React Router (current major) — or TanStack Router if you want end-to-end type-safe routes.
- **PWA:** `vite-plugin-pwa` (Workbox under the hood) — service worker, offline caching, install manifest.

The concrete React + Vite package set and setup steps are in the [implementation plan](#8-implementation-plan).

### Authentication

See [§6](#6-authentication-d7) — token-based auth that the SPA and the future native app share.

---

## 6. Authentication (D7)

The API currently has **no auth** and stores no player/KYC data yet, so this is a clean retrofit rather than a rearchitecture. The decision is shaped by the same native-app endgame that drove the SPA choice.

### Principle: the API is the security boundary

Authentication is *enforced at the API*. Every client — web SPA, future native app, any future integration — must present a valid credential on each request, and the Ktor app validates it. This work is the **same regardless of the frontend rendering model**: the choice of SPA vs SSR does not make the API itself easier or harder to secure. Where rendering models differ is only in *how the browser client stores the credential*.

### Why token-based (not server-session cookies)

| Approach | Fit for this project |
|---|---|
| **Token-based (JWT, `Authorization: Bearer`)** ✅ | Works identically for the SPA **and** the Capacitor native app (native apps store a Bearer token in secure device storage — Keychain/Keystore). Build the scheme once, reuse on web and mobile. Stateless at the API. |
| Server-session + HttpOnly cookie (BFF) | Genuinely the most XSS-resistant option *for a web-only app* — but it requires a running server (the thing we avoid by going static SPA) and is **web-only**: cookies don't carry to a native app, so mobile auth would have to be built separately. |

SSR's one real security edge — keeping the token out of JavaScript via an HttpOnly cookie behind a backend-for-frontend — evaporates here because (a) we run no server for the UI, and (b) the native endgame mandates token auth anyway. So token-based auth is "retrofit once, reuse everywhere"; a cookie/BFF scheme would be a web-only detour built twice.

### Recommended: Firebase Authentication + Ktor JWT verification

You're already on Firebase Hosting and GCP, so Firebase Authentication (the scaled tier is GCP Identity Platform) is the low-friction fit:

- **Identity provider:** Firebase Auth handles sign-up/login (email-password, Google, etc.) and issues a signed **JWT**.
- **One flow, both clients:** the **same Firebase SDK** runs in the web SPA and in the Capacitor native app — a single auth implementation for both.
- **API just verifies the JWT:** add `ktor-server-auth` + `ktor-server-auth-jwt`, validate the token's signature against Google's public keys plus issuer/audience, then mark protected routes with `authenticate { ... }`. Stateless — no session store.
- **Token storage best practice:** web — short-lived access token held in memory with the SDK handling refresh (avoid `localStorage` for long-lived tokens); native — secure device storage via the platform Keychain/Keystore.

This is deliberately easy to add later: the engine and routes work unauthenticated today, and auth is introduced by installing the JWT plugin, wrapping the routes that need protection, and adding a login screen to the SPA — no change to the rating logic.

### Out of scope (separate future decisions)

- **Authorization / roles** (player vs. club admin vs. system admin) — layered on top of authentication once roles exist.
- **Club-level / service credentials** (e.g. API keys for an inter-club integration) — distinct from individual-user auth.
- **KYC document handling** — the sensitive-data storage design (signed URLs to Cloud Storage, retention) is covered separately when feature #6 is built.

---

## 7. Hosting & deployment (D5)

**Production:** Firebase Hosting — free tier, global CDN, automatic SSL, one-command deploy (`firebase deploy`), run from `web/`. Effectively ₱0 at pilot scale. (Cloud-agnostic alternatives: Cloudflare Pages, Netlify, Vercel; AWS equivalent: S3 + CloudFront.)

The API deploys separately to Cloud Run as documented in [DEPLOYMENT_GCP.md](DEPLOYMENT_GCP.md). The two never share a deploy command.

**Local development:**

- **Simplest (recommended):** run the frontend dev server on the host — `cd web && npm run dev` (Vite, with hot reload) — pointing at the API on `localhost:8080`.
- **Full stack in one command (optional):** add a `web` service to `docker-compose.yml` (explicit `context: ./web`) so `docker-compose up` brings up Postgres + API + UI together. Use the framework dev server in the container for hot reload.

---

## 8. Implementation plan

Concrete steps for the chosen stack (React + Vite + TypeScript). Versions are intentionally unpinned — use current majors; `npm create vite` and `npm install` fetch latest.

### Phase 0 — API plumbing ✅ done

- CORS installed in the Ktor app (`ktor-server-cors`): allows `http://localhost:5173` (Vite dev) plus the `Content-Type`/`Authorization` headers; `allowCredentials` off (token auth). A `TODO` marks where the deployed Firebase origin goes.
- `.gcloudignore` (new) and `.dockerignore` exclude `web/` (and `presentation/`) from the API build context.

### Phase 1 — scaffold `web/` (React + Vite + TypeScript)

1. **Scaffold:** `npm create vite@latest web -- --template react-ts`
2. **Styling:** add **Tailwind CSS** (current major) — mobile-first responsive utilities.
3. **Routing:** add **React Router** (current major).
4. **Data layer:** add **TanStack Query** for fetching/caching/loading-error state.
5. **PWA:** add **`vite-plugin-pwa`** (manifest + service worker).
6. **Typed API client:** generate TS types from `../src/main/resources/openapi/documentation.yaml` with **`openapi-typescript`** (+ `openapi-fetch`); add an npm script (e.g. `gen:api`) so types regenerate when the spec changes.
7. **API base URL:** read from a Vite env var (`VITE_API_BASE_URL`, default `http://localhost:8080`) so dev and prod point at the right backend.

### Phase 2 — pilot screens

8. **Match entry → rating result** *(works against today's API):* a responsive form that POSTs to `/api/v1/calculate-ranking` and shows both players' rating changes. This is the one screen fully buildable now, since the API is currently stateless.
9. **Player list / leaderboard** *(blocked):* needs DB-backed endpoints that **do not exist yet** (roadmap #5/#7/#8 — players, matches, rankings). See [§9](#9-open-items-and-questions) — this is the key scoping decision.

### Phase 3 — PWA polish

10. App manifest, icons, install prompt, and an offline caching strategy; verify on a real phone browser.

### Phase 4 — deploy

11. **Firebase Hosting:** `firebase init hosting` in `web/`, point it at `dist/`, then `firebase deploy`.
12. **Lock down CORS:** replace the API's placeholder with the deployed Firebase origin.
13. **(Optional) Path-filtered CI:** GitHub Actions deploying the API on `src/**` changes and the UI on `web/**` changes.

### Phase 5 — future (when warranted)

14. **Authentication** — before any real player/KYC data: Firebase Auth on the client, `ktor-server-auth-jwt` verifying the JWT and protecting routes (approach in [§6](#6-authentication-d7)).
15. **Capacitor wrap** — `npm i @capacitor/core @capacitor/cli`, `npx cap add ios android`, reusing the same codebase and Firebase auth flow.

### Optional local-dev convenience

- Add a `web` service to `docker-compose.yml` (explicit `context: ./web`) if you want `docker-compose up` to bring up Postgres + API + UI together. Not required — running `cd web && npm run dev` on the host is simpler ([§7](#7-hosting--deployment-d5)).

---

## 9. Open items and questions

### Recommendations awaiting your explicit go

The implementation plan assumes these; confirm or adjust:

- **D4 — mobile strategy** (responsive + PWA now, Capacitor later).
- **D5 — Firebase Hosting** for the deployed UI.
- **D7 — Firebase Auth + Ktor JWT** for authentication.

### Genuinely open — need a decision or input

- **API surface vs. UI scope (the big one).** Today the API exposes only the *stateless* `POST /api/v1/calculate-ranking` (plus `/health`, `/metrics`). There are **no endpoints yet for players, matches, or leaderboards** — those are roadmap features #5/#7/#8 and require the database work. So the pilot UI can do **match-entry → rating result** now, but a player list, profiles, or a leaderboard need DB-backed endpoints built first. **Decide:** ship a calculator-only UI now, or build the persistence endpoints before starting the UI?
- **Pilot screen scope.** Exactly which screens for the first cut — match entry only, or also profiles / leaderboard / seeding view (subject to the API-surface question above)?
- **Firebase project.** Reuse the `skopeo-prod` GCP project or create a separate Firebase project, and what hosting domain — this determines the CORS origin to allowlist and the `VITE_API_BASE_URL`.
- **Auth timing.** Leave the pilot demo unauthenticated, or add Firebase Auth before first real use? (Required before any real player/KYC data is stored.)
- **UI design / branding.** Visual design, logo/colors, and whether to add a headless component library (Radix / Headless UI) on top of Tailwind.
- **Native-app trigger.** What event justifies the Capacitor wrap (app-store presence, push notifications)? Deferred — not now.

---

## References

- [Capacitor — web to native](https://capacitorjs.com/) · [Ionic PWA → native guide](https://ionic.io/blog/take-your-web-app-further-with-capacitor)
- [Best frontend frameworks 2026 (overview)](https://strapi.io/blog/best-javascript-frameworks)
- Related: [DEPLOYMENT_GCP.md](DEPLOYMENT_GCP.md) (API + DB on GCP) · [DOCKER_DEPLOYMENT.md](DOCKER_DEPLOYMENT.md) · [API_DOCUMENTATION.md](API_DOCUMENTATION.md)
