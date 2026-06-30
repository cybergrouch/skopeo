# BFF vs core engine (architecture decision)

**Status:** Proposed — phased. Relates to [#225](https://github.com/cybergrouch/skopeo/issues/225)
(client→API auth). No code change yet; this records the intended direction so later work lands behind
a deliberate boundary instead of accreting ad hoc.

## Context

Today Skopeo is a single Ktor deployable, layered `routes → service → repository`
([LAYERED_ARCHITECTURE.md](LAYERED_ARCHITECTURE.md)). The React web client calls that API **directly**
(orval-generated client over the OpenAPI spec, authenticated with a Firebase **end-user** token —
[AUTHENTICATION.md](AUTHENTICATION.md), [WEB_UI_ARCHITECTURE.md](WEB_UI_ARCHITECTURE.md)). There is no
notion of a calling *application*: the API only knows *which user* is calling, not *which client*.

Two pressures are now visible:

1. **Opening to third-party integration** (#225) needs application-level identity — API keys / OAuth
   clients, scopes, per-client rate limits, revocation — which the per-user model doesn't provide.
2. **The web UI wants UI-shaped responses** — aggregated, trimmed, few round-trips — which pulls the
   API toward the screen rather than toward a clean, stable domain contract.

Serving both from one undifferentiated surface makes each worse: the public/integration API drifts
toward UI convenience, and the UI is coupled to raw domain endpoints.

## The two concerns

| | **BFF (backend-for-frontend)** | **Core engine (true backend)** |
|---|---|---|
| Audience | Our own web UI (first-party) | The system itself + **third-party integrations** |
| Shape | UI-tailored: aggregates/trims, fewer chatty calls | Stable, versioned, domain-faithful contract |
| Auth | End-user (Firebase) session concerns | **Client credentials** (#225) + capability/scope checks |
| Stability | Can change with the UI, freely | Backwards-compatibility matters (external consumers) |
| Trust | A single **trusted internal client** of the engine | The integration boundary; least-privilege per client |

The BFF is, in effect, *one well-known client of the engine* that exists to serve the frontend.

```
            today                                  target (logical first)

  ┌────────────┐                          ┌────────────┐        ┌──────────────────┐
  │  web UI    │ ── Firebase user ──►     │  web UI    │ ─user─► │  BFF surface     │
  └────────────┘        API               └────────────┘        │ (UI-shaped)      │
                                                                 └────────┬─────────┘
  ┌────────────┐                          ┌────────────┐                  │ trusted
  │ 3rd party  │ ── (nothing today) ─►    │ 3rd party  │ ─client cred──►  ▼
  └────────────┘                          └────────────┘        ┌──────────────────┐
                                                                 │  engine surface  │
                                                                 │ (domain API,     │
                                                                 │  #225 client auth│
                                                                 └────────┬─────────┘
                                                                          ▼ service → repository
```

## Decision

**Separate the two concerns, but phase it — logical boundary now, physical split only when integrations justify it.**

### Phase 1 — logical separation (do when #225 starts)

Draw the boundary *inside the current deployable*. No new service, no network hop.

- Two distinct **API surfaces** in the same app:
  - **engine surface** — the domain-faithful, versioned, integration-facing API. Client-credential auth (#225) + scope checks live here.
  - **BFF surface** — UI-shaped endpoints the web client calls; owns the Firebase end-user session and composes engine/service calls for the screen.
- Enforce the boundary with **package separation + ArchUnit** (extend `LayeredArchitectureTest`): the BFF surface may depend on services/engine; the engine surface must **not** depend on the BFF. Both still bottom out at `service → repository`.
- The web's orval client generates against the **BFF** portion of the OpenAPI spec; integrations generate against the **engine** portion. (Tag operations so the spec can be split per audience.)

This gets #225 implementable immediately: the engine surface is where client credentials, scopes, and per-client rate limits apply; the BFF is the lone trusted first-party caller.

### Phase 2 — physical split (defer)

Promote the BFF to its own deployable (separate Cloud Run service) **only when** real third-party
integrations exist and the separation earns its keep (independent scaling, blast-radius isolation, a
lean public API). Until then it's premature: see the cost below.

## Why this resolves #225's open question

#225 asks "does the first-party web UI need a client credential?" With this split the answer is clean:

- The **web UI talks only to the BFF** (Firebase user token, as today).
- The **BFF is the single trusted internal caller** of the engine.
- **Third-party clients** get client-credentialed, scoped access to the **engine** surface and never touch the BFF.

So we don't have to hand the public web bundle a (necessarily public, non-secret) client key just to satisfy a uniform scheme.

## Cost of a physical BFF (why Phase 2 is deferred)

For a solo developer at pilot stage this is real:

- A second deployable (Cloud Run service) + its own CI/CD, config, secrets, monitoring.
- An extra network hop on every UI request (latency, failure modes).
- **Internal** BFF→engine authentication (service identity) to design and operate.
- Two OpenAPI/client-generation pipelines to keep green.

A logical boundary captures ~80% of the design benefit (clean separation of concerns, #225 scoping) at ~none of the operational cost, and keeps the physical split a later, mechanical extraction rather than a rewrite.

## Non-goals / open questions

- **Not** introducing a new runtime now. Phase 1 is package/surface separation within the monolith.
- **Spec split**: how to partition the OpenAPI document (operation tags vs two documents) for BFF vs engine client generation — to decide when Phase 1 starts.
- **Scope model**: whether engine client scopes reuse the existing capability set or a dedicated scope vocabulary (tracked in #225).
- **Internal auth** (Phase 2 only): how a physical BFF authenticates to the engine (service account / mTLS / signed internal token).
- **Where existing public pages live** (player/match/event public reads): likely engine surface (they're domain reads consumed by share links and potentially integrations), fronted by the BFF for the UI.

## Relationship to existing docs

- [LAYERED_ARCHITECTURE.md](LAYERED_ARCHITECTURE.md) — the within-app layering this boundary extends (a new surface dimension, enforced the same way via ArchUnit).
- [AUTHENTICATION.md](AUTHENTICATION.md) — end-user (Firebase) auth that stays on the BFF.
- [#225](https://github.com/cybergrouch/skopeo/issues/225) — client/application auth that lands on the engine surface.
