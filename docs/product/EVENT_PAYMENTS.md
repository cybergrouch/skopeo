# Event Payments (QR Ph) — Design Exploration

> **Status:** 🟡 Proposed / design exploration. No implementation yet; this doc captures the approach, the requirements, and the decisions to settle before building. Provider names and fees are indicative and must be verified against current terms.

## Goal

Let event participants **pay the host through the app**, and have the backend **automatically reconcile and audit each payment** — with **no manual "upload your receipt" step**. The host and the participant should not have to exchange or verify QR-code screenshots.

## Key insight: QR Ph is one rail, not four

GCash, Maya, GoTyme, and every participating bank all consume the **interoperable QR Ph** standard (BSP-mandated, EMVCo-based). A single **dynamic QR Ph** code is scannable by all of them. So this is **one integration (QR Ph via a payment provider)**, not separate GCash/Maya/GoTyme integrations.

## Architecture: dynamic QR + reference + webhook

The standard provider flow, which also matches the desired "backend resolves the payment" model:

1. **Create a payment intent** per `(event, participant)` — amount + a `reference` / `external_id` (encodes the event id + user id).
2. The provider returns a **dynamic QR Ph** (the amount + reference are encoded in it) — nothing for hosts/users to generate or manage.
3. The participant scans it with **any** wallet (GCash / Maya / GoTyme / bank) and pays.
4. The provider fires a **signature-verified webhook** → the backend matches it to the reference → marks the participant **paid**, fully audited.

The `reference` embedded in the dynamic QR is the linchpin: it lets the webhook **auto-attribute** the payment to the exact participant.

## Why this removes the "upload receipt" step

Manual receipt upload exists today **only because a host's personal/static QR gives the app no programmatic confirmation** — a human has to eyeball a screenshot to verify payment. With a **provider-issued dynamic QR carrying a per-participant reference**, the provider sends an **authoritative webhook** ("reference X paid ₱Y"), so the backend *knows* the payment happened and attributes it automatically. No screenshot, no upload, no manual verification. The participant's wallet still gives *them* a receipt to keep; the app just doesn't need it.

### Non-negotiable dependency
Eliminating uploads **requires a provider/merchant-issued dynamic QR Ph** (e.g. PayMongo / Xendit / Maya Business). You **cannot** auto-reconcile against a host's plain **personal** GCash/Maya QR — a personal wallet emits no webhook and no per-payment reference back to the app, which is exactly why those setups are stuck with manual uploads. So **"no uploads" and "go through a payment-provider merchant rail" are the same requirement.**

## The pivotal decision: money-flow model

| Model | How money moves | Trade-off |
|---|---|---|
| **Platform-collects-and-remits** (marketplace) | Funds → Skopeo → payout to hosts | Powerful, but Skopeo now *handles other people's money* → heavier regulation (possibly BSP OPS territory) and needs a provider with **split-payments / sub-merchant payouts**. |
| **Host-connected accounts** | Funds → the host's own merchant account directly | Skopeo just orchestrates the QR + status; **much lighter compliance** (Skopeo never holds funds), but **each host must onboard** as a merchant. |

This choice drives the provider selection, the compliance surface, and the onboarding UX. **It must be settled before building.**

## The hard part is business/compliance, not code

- **Engineering: moderate.** A `payments` table, a create-intent endpoint, a provider client, an **idempotent, signature-verified webhook handler**, and wiring into event participation. Comparable to other event features.
- **The real work/risk (largely outside the codebase):**
  - **Merchant onboarding / KYB** — a registered business entity, KYB docs, and a settlement bank account with the provider. Biggest gate.
  - **Regulatory** — if Skopeo ever holds/routes funds, BSP oversight applies (OPS registration or riding entirely on a licensed provider's rails).
  - **Fees** — provider charges per QR Ph transaction (indicative ~1.5–2.5% or a flat fee; verify). Decide who absorbs it (host / participant / platform).

## Requirements

**Provider**
- A PH payment provider supporting **QR Ph dynamic QR + webhooks** (+ split payouts if marketplace). Candidates to evaluate: **PayMongo, Xendit, Maya Business, or a bank/GCash partner API** (PayMongo/Xendit are the most developer-friendly). A comparison spike should precede the build.
- A merchant legal entity + KYB + settlement account (per the money-flow decision).

**Backend**
- `payments` table: intent per event-participant — amount, currency, `reference`/`external_id`, status, provider payment id, QR payload/URL, created/paid/expired timestamps.
- Create-payment-intent endpoint (host requests an amount for a participant, or an event-wide fee).
- Provider client (create dynamic QR, query status).
- **Webhook endpoint** — signature-verified and **idempotent** — reconciles by reference, marks the participant paid, writes an audit entry.
- Hook into event participation (e.g. gate roster approval / a "paid" badge on the participant).

**Frontend**
- Render the provider's dynamic QR (image / deep-link) for the participant; show live payment status; host "who's paid" view. No upload control on the happy path.

**Ops / security**
- Sandbox testing, provider API keys as secrets, refund/cancellation flow, reconciliation reports, expiry handling.

## Exception handling (no upload on the happy path)

Webhooks cover the vast majority cleanly. For the rare unreconciled case (webhook delayed/lost, wrong amount, paid outside the flow), provide an **admin-only "mark paid / investigate"** action — **not** a routine upload. If an "attach receipt" affordance is ever wanted, it lives **only** in this exception path, never on the normal flow.

## Phased plan

- **v1 (lightest):** one provider, a **single merchant account** (the org's). Generate a dynamic QR Ph per event-participant, reconcile via webhook, mark participants paid — **no receipt uploads**. Remit to hosts **offline** for now (avoids split-payment engineering and marketplace compliance). Reconciliation is automatic regardless of how payout happens.
- **v2:** host-connected accounts or platform split-payouts, once the model is validated.

## Open decisions

- [ ] **Money-flow model:** platform-collects-and-remits vs host-connected accounts (drives everything).
- [ ] **Provider:** PayMongo vs Xendit vs Maya Business vs bank/GCash partner (spike to compare QR Ph + webhooks + payouts).
- [ ] **Merchant of record / legal entity** and settlement account.
- [ ] **Fees:** who absorbs the per-transaction cost.
- [ ] **v1 scope:** confirm single-merchant + offline remittance for the first cut.

## Non-goals (for now)

- Card payments, recurring billing, or non-QR rails.
- Holding balances / wallet features inside Skopeo.
- Auto-payouts / split settlement (deferred to v2).

## Related
- Event organization (fixtures, participants), `docs/product/POINTS_AWARDING_AND_BUDGET.md` (event economics).
