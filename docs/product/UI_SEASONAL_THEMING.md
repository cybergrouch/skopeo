# UI Seasonal Theming — Design Discussion

**Status:** Design discussion (not yet built) · **Tracking issue:** [#378](https://github.com/cybergrouch/skopeo/issues/378)

Admin-controlled, tennis-season / court-surface **color themes** for the web UI, propagated live to all connected clients. This document captures the discussion, decisions, palettes (with visual swatches), and an implementation plan.

---

## 1. Problem & corrected framing

The app is visually dominated by plain white and needs color/personality.

> **Correction to the original framing.** An earlier take worried about the *page background* and *font contrast*. On closer look there are **two surfaces**: the **page canvas behind the cards**, and the **cards themselves (also white)**. Font contrast is therefore a **non-issue for the light themes** (dark text on white cards) — it only matters for the **dark themes (US Open, Christmas)**, where text goes light on dark cards.

---

## 2. Design strategy — 60-30-10 with a "Muted Base"

Rather than flooding the UI with a saturated court color, apply the classic **60-30-10** rule:

- **60% — Background (page canvas):** an **ultra-muted, tinted** version of the theme color (or a dark desaturated slate for the dark themes). This replaces the flat white canvas.
- **30% — Cards:** a **clean neutral** — pure white (light themes) or deep pine/slate (dark themes) — giving text a safe, high-contrast surface. Cards are themed via their **border/accent**, not their fill.
- **10% — Accents & borders:** the **vibrant, authentic** colors (court colors + optic tennis yellow), used *strictly* for card borders, primary buttons, links, icons, and hover states.

---

## 3. Admin control & propagation

### Model
- A **single global theme setting**, controlled from the **Admin tab**.
- Value is either a **specific theme** (`GRASS` / `CLAY` / `AO` / `US_OPEN` / `OFF_SEASON` / `CHRISTMAS`) or **`AUTO`** (resolve by date — see [§6](#6-auto-season-resolver)).
- **Source of truth: backend / Postgres** — a small `app_settings` row, `GET /api/v1/theme` (public read) + admin-gated `PUT`.

### Decision: **Poll + live token swap**

When the Admin switches the theme, every open client should reflect it. We chose **polling** for propagation and a **live CSS-token swap** for applying it.

- Clients re-fetch `GET /api/v1/theme` on a timer (~30–60s) **and on tab-focus**, comparing a `version` / `updatedAt`.
- On change, **live-swap the CSS custom-property set** — the whole UI recolors instantly, **no reload / no flash**.
- Works across all Cloud Run instances automatically: every client just reads the current persisted value, so there is **no server-push fan-out problem**.

### Options considered

| Option | Pros | Cons |
|---|---|---|
| **Poll** *(chosen)* | Simplest; one endpoint + one row; fits existing REST + Postgres SoT; works across Cloud Run instances; no new infra; cheap & stateless (scale-to-zero friendly). | Not instant — latency ≈ poll interval (tab-focus refetch hides this); minor periodic traffic; "refresh everyone right now" is really "within the poll window". |
| **Firestore realtime** (`onSnapshot`) | Genuinely instant (~1s) fan-out across all instances (Firestore *is* the broker); reuses existing Firebase; push, not poll. | Splits source-of-truth into Firestore (or requires a Postgres→Firestore mirror); new surface area (rules, SDK, subscription lifecycle); minor ongoing cost. |
| **SSE from Ktor** | Real-time; keeps everything in our backend + Postgres. | **Cloud Run killer:** an SSE connection lives on one instance, so an admin write on instance A can't notify clients on instance B without a shared broker (Pub/Sub / Redis); long-lived connections fight Cloud Run timeouts / scale-to-zero; most effort for a feature that changes a handful of times a year. |

**Why Poll wins here:** theme changes are **rare**, we value a **single Postgres source-of-truth** and **minimal infra**, and polling sidesteps the Cloud Run multi-instance fan-out problem entirely. **Firestore realtime** is the documented fallback if truly-instant push ever becomes a hard requirement (we already depend on Firebase). **SSE** is ruled out on Cloud Run unless a broker exists for other reasons.

### Applying the change: **live token swap** (not a hard reload)
- **Live token swap** *(chosen):* replace the CSS custom-property set on `:root` — UI recolors instantly, no reload flash. Best UX.
- **Soft `location.reload()`** *(rejected):* simplest and guarantees a clean re-render, but users see a reload flash.

---

## 4. Theme catalog

Six themes give a cohesive **year-round rotation**. Two are dark (**US Open**, **Christmas**). Swatches below are illustrative.

### ⚙️ Off-Season — *Sleek, structural, minimal*
Winter training / rest vibe (late Nov–Dec): premium sleek monochrome + concrete, evoking indoor practice facilities and empty stadiums.

| Role | Swatch | Color |
|---|---|---|
| Background (60%) | ![bg](https://placehold.co/90x24/F1F3F5/F1F3F5.png) | `#F1F3F5` Fog Gray |
| Card (30%) | ![card](https://placehold.co/90x24/FFFFFF/E0E0E0.png?text=card) | `#FFFFFF` White · font `#212529` Dark Slate |
| Accent (10%) | ![steel](https://placehold.co/44x24/495057/495057.png) ![neon](https://placehold.co/44x24/D2FE00/D2FE00.png) | `#495057` Steel + `#D2FE00` Tennis Neon |

**Border strategy:** thin, sharp Steel Gray border `#CED4DA` on cards; use neon `#D2FE00` **only** for hover states / primary buttons.

### 🎄 Christmas — *Festive, cozy, premium* · **dark**
"Crisp Winter Night" — festive colors act as glowing decorations against an elegant green-black.

| Role | Swatch | Color |
|---|---|---|
| Background (60%) | ![bg](https://placehold.co/90x24/0B1A14/0B1A14.png) | `#0B1A14` Midnight Spruce |
| Card (30%) | ![card](https://placehold.co/90x24/162E25/162E25.png) | `#162E25` Muted Pine · font `#F8F9FA` Off-White |
| Accent (10%) | ![red](https://placehold.co/44x24/D92B34/D92B34.png) ![gold](https://placehold.co/44x24/E5B842/E5B842.png) | `#D92B34` Ribbon Red + `#E5B842` Champagne Gold |

**Border strategy:** thin **Champagne Gold** border on cards; use **Ribbon Red** exclusively for main CTAs / festive badge icons.

### 🔵 Australian Open — *Electric, cool, summer*
| Role | Swatch | Color |
|---|---|---|
| Background (60%) | ![bg](https://placehold.co/90x24/F0F6FA/F0F6FA.png) | `#F0F6FA` Ice Blue |
| Card (30%) | ![card](https://placehold.co/90x24/FFFFFF/E0E0E0.png?text=card) | `#FFFFFF` White · font `#0A1D37` Midnight Blue |
| Accent (10%) | ![blue](https://placehold.co/44x24/0080C8/0080C8.png) ![neon](https://placehold.co/44x24/CCFF00/CCFF00.png) | `#0080C8` AO Blue + `#CCFF00` Highlighter Neon |

**Border strategy:** AO Blue on card rims / key actions; neon yellow for electric contrast on buttons.

### 🟠 Clay — *Warm, organic, earthy*
| Role | Swatch | Color |
|---|---|---|
| Background (60%) | ![bg](https://placehold.co/90x24/FDF6F0/FDF6F0.png) | `#FDF6F0` Warm Sand |
| Card (30%) | ![card](https://placehold.co/90x24/FFFFFF/E0E0E0.png?text=card) | `#FFFFFF` White · font `#2B1A12` Dark Chocolate |
| Accent (10%) | ![clay](https://placehold.co/90x24/C1522D/C1522D.png) | `#C1522D` Classic Clay Orange |

**Border strategy:** Clay Orange on card borders + key action items.

### 🟢 Grass — *Classic, elegant, prestigious*
| Role | Swatch | Color |
|---|---|---|
| Background (60%) | ![bg](https://placehold.co/90x24/F4F9F5/F4F9F5.png) | `#F4F9F5` Mint Cream |
| Card (30%) | ![card](https://placehold.co/90x24/FFFFFF/E0E0E0.png?text=card) | `#FFFFFF` White · font `#1B3B2B` Dark Green |
| Accent (10%) | ![purple](https://placehold.co/44x24/452263/452263.png) ![green](https://placehold.co/44x24/00703C/00703C.png) | `#452263` Wimbledon Purple + `#00703C` Lawn Green |

**Border strategy:** court color (purple or lawn green) on card borders, primary links, buttons.

### 🌃 US Open — *High-octane, modern, night session* · **dark**
| Role | Swatch | Color |
|---|---|---|
| Background (60%) | ![bg](https://placehold.co/90x24/121824/121824.png) | `#121824` Midnight Slate |
| Card (30%) | ![card](https://placehold.co/90x24/1E293B/1E293B.png) | `#1E293B` Deep Slate · font `#FFFFFF` White |
| Accent (10%) | ![blue](https://placehold.co/44x24/005DAA/005DAA.png) ![green](https://placehold.co/44x24/63B233/63B233.png) | `#005DAA` US Open Blue + `#63B233` Apple Green |

**Border strategy:** Apple Green as a thin glowing card border; US Open Blue for primary nav.

---

## 5. Summary matrix

| Theme | 60% Background | 30% Card fill | 10% Accents & borders | Mood |
|---|---|---|---|---|
| **Off-Season** | `#F1F3F5` Fog Gray | `#FFFFFF` White | `#495057` & `#D2FE00` (Steel / Neon) | Sleek, structural, minimal |
| **Christmas** *(dark)* | `#0B1A14` Spruce | `#162E25` Pine | `#D92B34` & `#E5B842` (Red / Gold) | Festive, cozy, premium |
| **Australian Open** | `#F0F6FA` Ice Blue | `#FFFFFF` White | `#0080C8` & `#CCFF00` (Blue / Neon) | Electric, cool, summer |
| **Clay** | `#FDF6F0` Sand | `#FFFFFF` White | `#C1522D` (Clay Orange) | Warm, organic, earthy |
| **Grass** | `#F4F9F5` Mint Cream | `#FFFFFF` White | `#452263` & `#00703C` (Purple / Green) | Classic, elegant, prestigious |
| **US Open** *(dark)* | `#121824` Midnight | `#1E293B` Slate | `#005DAA` & `#63B233` (Blue / Green) | High-octane, modern, night session |

---

## 6. AUTO season resolver

When the setting is `AUTO`, a small data-driven table maps today's date → theme (single source of truth, tunable). Proposed windows:

| Window (approx) | Theme |
|---|---|
| January | Australian Open |
| February – March | *off-swing — Off-Season, or carry AO (TBD)* |
| April – early June | Clay |
| late June – July | Grass |
| August – early September | US Open |
| September – late November | *indoor / Asian swing — Off-Season (TBD)* |
| late November – December (excl. holidays) | Off-Season |
| ~Dec 10 – Jan 1 | Christmas |

*Windows and the Feb–Mar / Sep–Nov "swing" fills are open for tuning; Christmas is a sub-window carved out of the December off-season.*

---

## 7. Contrast checklist

- **No dark-on-dark:** in the dark themes (US Open, Christmas) never use dark-gray text on the dark cards — use the off-white/white font (`#F8F9FA` / `#FFFFFF`) or a bright neon accent.
- **No vibrancy collisions:** never place a vivid accent border directly against a vivid background. The muted pastel / dark canvases above are chosen so the vivid card borders/accents pop cleanly.
- Verify each theme's card-font + accent tokens meet **WCAG AA** against the card fill — automatic for the light themes; the real work is the two dark themes.

---

## 8. Implementation plan

### Web
- Each theme is an override of the existing CSS custom properties in `web/src/index.css`. Map 60/30/10 onto the shadcn tokens: `--background` (canvas), `--card` / `--card-foreground` (cards), `--primary` / `--border` / `--ring` / `--accent` (accents). **No per-component color hard-coding.**
- The two **dark themes reuse the existing dark-mode token path**; the four light themes retint the canvas + recolor accents/borders while cards stay white.
- **Live swap:** a small theme provider applies the active theme's token set to `:root` (or a `data-theme` attribute); switching = swapping that set, no reload.
- **Poll:** a lightweight hook re-fetching `GET /api/v1/theme` on an interval + `visibilitychange`, re-applying on `version` change.

### Backend
- `app_settings` row (or dedicated `theme` setting) + `GET /api/v1/theme` (public) and admin-gated `PUT`.
- Audit the change (ADMINISTRATOR capability), consistent with other admin actions.
- Hand-maintained OpenAPI (`documentation.yaml`) + `OpenAPIIntegrationTest` updated for the new endpoints.

### Admin tab
- A theme picker (the 6 themes + `AUTO`) writing the setting; reflects the current value.

---

## 9. Open questions

- **AUTO vs. pinned default** on first launch (lean: ship `AUTO`).
- **Exact season windows**, incl. the Feb–Mar and Sep–Nov swing fills and the Christmas carve-out dates.
- **Accent choices** where two are offered (Wimbledon purple vs. lawn green; AO blue vs. neon) — primary vs. secondary.
- **Poll cadence** (30s vs 60s) and whether tab-focus refetch is enough to make it feel responsive.

---

## 10. References

- Tracking issue [#378](https://github.com/cybergrouch/skopeo/issues/378).
- `web/src/index.css` (theme tokens / dark-mode block), Tailwind config, shadcn UI under `web/src/components/ui/`.
- Admin tab: `web/src/routes/dashboard/AdminTab.tsx`.
- Web UI architecture: `docs/engineering/architecture/WEB_UI_ARCHITECTURE.md`.
