# UI Seasonal Theming — Design Discussion

**Status:** Built · **Tracking issues:** [#378](https://github.com/cybergrouch/skopeo/issues/378) (original palettes) · [#399](https://github.com/cybergrouch/skopeo/issues/399) (all-dark inversion)

Admin-controlled, tennis-season / court-surface **color themes** for the web UI, propagated live to all connected clients. This document captures the discussion, decisions, palettes (with visual swatches), and an implementation plan.

> **Inverted to all-dark (#399).** The original #378 design mixed four light themes (white cards) with two dark ones (US Open, Christmas). The page backgrounds read too light, so the whole system was **inverted into an immersive dark mode**: every season is now a **dark canvas + dark card + vibrant accent + near-white font**, keeping each season's mood but far moodier. The palettes and link colors below reflect that inversion (and **supersede the #394/#395 link palette**). A few passages marked *(historical)* preserve the original light-theme reasoning for context.

---

## 1. Problem & framing

The app is visually dominated by plain white and needs color/personality.

Under the all-dark inversion (#399) there are still **two surfaces** — the **page canvas** behind the cards and the **cards** themselves — but now **both are dark**. Font contrast therefore matters **everywhere**: light (near-white) text sits on dark cards in every theme. This actually *simplifies* the prior logic — the "US Open/Christmas are the only dark themes" special case is gone, so `--foreground`/`--card-foreground` are light in every block, and the header `BrandMark` (#398, `currentColor`=foreground) auto-adapts to light on every theme with no per-theme code.

> *(historical, #378)* The original framing treated font contrast as a non-issue for the four light themes (dark text on white cards) and a concern only for the two dark themes. The inversion makes it a uniform light-on-dark concern.

---

## 2. Design strategy — inverted 60-30-10 (immersive dark)

Apply the classic **60-30-10** rule, inverted into dark mode (#399):

- **60% — Background (page canvas):** a **deep, desaturated shadow shade** of the season color — dark, but still carrying the season's mood. This replaces the flat white / muted-pastel canvas.
- **30% — Cards:** a **rich neutral dark layer** (dark charcoal / midnight slate / season-tinted dark) so the light text is highly readable. Cards are themed via their **tint + border/accent**, not a white fill.
- **10% — Accents & borders:** the **vibrant, authentic** colors (court colors + optic tennis neons + lavender), reserved *strictly* for card rims, primary buttons, badges, links, and hover states — popping against the dark canvas.
- **Font:** a **high-contrast near-white** per theme.

> *(historical, #378)* The original "Muted Base" strategy used an ultra-muted pastel canvas with **pure-white cards** for the four light themes; only US Open/Christmas were dark. #399 inverts this everywhere.

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

Six themes give a cohesive **year-round rotation**. Under #399 **all six are dark** — a deep season-tinted canvas, a rich dark card carrying near-white text, and a vibrant 10% accent. Swatches below are illustrative.

> 🖼️ **Interactive preview:** [`ui-seasonal-theming-preview.html`](./ui-seasonal-theming-preview.html) renders all six dark themes (canvas + card + accented button/border/link) live. Open it in a browser, or view a branch copy via [htmlpreview.github.io](https://htmlpreview.github.io/). Its CSS is also the reference for the per-theme **muted body-text** color (a `--muted-foreground` token): Off-Season `#CED4DA`, Christmas `#E2ECE9`, AO `#E2E8F0`, Clay `#FDF6F0`, Grass `#F4F9F5`, US Open `#94A3B8`.

### ⚙️ Off-Season — *Sleek, structural, minimal*
Winter training / rest vibe (late Nov–Dec): premium sleek monochrome + concrete, evoking dimly lit indoor practice facilities and empty night stadiums.

| Role | Swatch | Color |
|---|---|---|
| Background (60%) | ![bg](https://placehold.co/90x24/1A1D20/1A1D20.png) | `#1A1D20` Ink Gray |
| Card (30%) | ![card](https://placehold.co/90x24/2B3035/F8F9FA.png?text=card) | `#2B3035` Steel Slate · font `#F8F9FA` Snow White · muted `#CED4DA` |
| Accent (10%) | ![neon](https://placehold.co/44x24/D2FE00/D2FE00.png) ![steel](https://placehold.co/44x24/495057/495057.png) | `#D2FE00` Neon Yellow (button, font `#212529`) + `#495057` Steel card rim |

**Border strategy:** a steel `#495057` card rim against the ink canvas; the neon `#D2FE00` is the button + link pop.

### 🎄 Christmas — *Festive, cozy, premium*
A rich, joyful holiday look: a bold velvet-wine canvas with a deep-spruce card anchor, crisp white text, a gold rim, and a champagne-gold call to action.

| Role | Swatch | Color |
|---|---|---|
| Background (60%) | ![bg](https://placehold.co/90x24/2B050B/2B050B.png) | `#2B050B` Velvet Wine |
| Card (30%) | ![card](https://placehold.co/90x24/1A2E26/FFFFFF.png?text=card) | `#1A2E26` Deep Spruce · font `#FFFFFF` Pure White · muted `#E2ECE9` |
| Accent (10%) | ![gold](https://placehold.co/44x24/E5B842/E5B842.png) | `#E5B842` Champagne Gold (button, font `#112A1F`; card rim + link) |

**Card fill note:** the #399 matrix specifies `#1A2E26` (Deep Spruce); the earlier HTML sample used `#164A35` (Pine). We ship the deeper **`#1A2E26`**. The gold rim/link + white text stay legible over the spruce (white 14.4:1, gold 7.7:1).

### 🔵 Australian Open — *Electric, cool, summer night*
| Role | Swatch | Color |
|---|---|---|
| Background (60%) | ![bg](https://placehold.co/90x24/081626/081626.png) | `#081626` Deep Abyss |
| Card (30%) | ![card](https://placehold.co/90x24/12253A/F0F6FA.png?text=card) | `#12253A` Oceanic Slate · font `#F0F6FA` Ice White · muted `#E2E8F0` |
| Accent (10%) | ![neon](https://placehold.co/44x24/CCFF00/CCFF00.png) ![blue](https://placehold.co/44x24/0080C8/0080C8.png) | `#CCFF00` Volt Yellow (button, font `#0A1D37`; link) + `#0080C8` AO Blue card rim |

**Border strategy:** AO Blue on card rims; volt yellow for the electric button + link pop.

### 🟠 Clay — *Warm, organic, earthy*
| Role | Swatch | Color |
|---|---|---|
| Background (60%) | ![bg](https://placehold.co/90x24/2B140C/2B140C.png) | `#2B140C` Burnt Umber |
| Card (30%) | ![card](https://placehold.co/90x24/3D2015/FDF6F0.png?text=card) | `#3D2015` Warm Espresso · font `#FDF6F0` Sand White · muted `#FDF6F0` |
| Accent (10%) | ![clay](https://placehold.co/44x24/C1522D/C1522D.png) ![terra](https://placehold.co/44x24/E07A5F/E07A5F.png) | `#C1522D` Clay Orange (button + card rim) + `#E07A5F` Light Terracotta link |

**Border strategy:** Clay Orange on card rims + the button; the lighter terracotta `#E07A5F` is the link so it clears AA on the dark espresso card.

### 🟢 Grass — *Classic, elegant, prestigious*
| Role | Swatch | Color |
|---|---|---|
| Background (60%) | ![bg](https://placehold.co/90x24/0B1F13/0B1F13.png) | `#0B1F13` Midnight Lawn |
| Card (30%) | ![card](https://placehold.co/90x24/183624/F4F9F5.png?text=card) | `#183624` English Ivy · font `#F4F9F5` Mint White · muted `#F4F9F5` |
| Accent (10%) | ![lavender](https://placehold.co/44x24/9362C4/9362C4.png) ![purple](https://placehold.co/44x24/452263/452263.png) | `#9362C4` Bright Lavender (button) + `#452263` Wimbledon Purple card rim + `#C5A3E8` link |

**Border strategy:** deep Wimbledon Purple `#452263` on card rims; bright lavender `#9362C4` on the button, and a lighter lavender `#C5A3E8` link for AA on the dark ivy card.

### 🌃 US Open — *High-octane, modern, night session*
| Role | Swatch | Color |
|---|---|---|
| Background (60%) | ![bg](https://placehold.co/90x24/0B0F19/0B0F19.png) | `#0B0F19` Midnight |
| Card (30%) | ![card](https://placehold.co/90x24/1E293B/FFFFFF.png?text=card) | `#1E293B` Slate Navy · font `#FFFFFF` Pure White · muted `#94A3B8` |
| Accent (10%) | ![blue](https://placehold.co/44x24/005DAA/005DAA.png) ![green](https://placehold.co/44x24/63B233/63B233.png) | `#005DAA` US Open Blue (button) + `#63B233` Apple Green card rim + link |

**Border strategy:** Apple Green as a thin glowing card rim + link; US Open Blue for the primary button. (US Open keeps the two-accent split — blue primary, green accent — from #378.)

---

## 5. Summary matrix

All six themes are dark (#399). Font = the near-white `--foreground`/`--card-foreground`; button = `--primary` (with `--primary-foreground` label); card rim = `--border`/`--ring`; pop accent = `--accent`.

| Theme | 60% Canvas | 30% Card + font | 10% Accent (pop) | Card rim | Mood |
|---|---|---|---|---|---|
| **Off-Season** | `#1A1D20` Ink Gray | `#2B3035` Steel Slate · `#F8F9FA` | `#D2FE00` Neon Yellow | `#495057` Steel | Sleek, structural, minimal |
| **Christmas** | `#2B050B` Velvet Wine | `#1A2E26` Deep Spruce · `#FFFFFF` | `#E5B842` Champagne Gold | `#E5B842` Gold | Festive, cozy, premium |
| **Australian Open** | `#081626` Deep Abyss | `#12253A` Oceanic Slate · `#F0F6FA` | `#CCFF00` Volt Yellow | `#0080C8` AO Blue | Electric, cool, summer night |
| **Clay** | `#2B140C` Burnt Umber | `#3D2015` Warm Espresso · `#FDF6F0` | `#C1522D` Clay Orange | `#C1522D` Clay | Warm, organic, earthy |
| **Grass** | `#0B1F13` Midnight Lawn | `#183624` English Ivy · `#F4F9F5` | `#9362C4` Bright Lavender | `#452263` Wimbledon Purple | Classic, elegant, prestigious |
| **US Open** | `#0B0F19` Midnight | `#1E293B` Slate Navy · `#FFFFFF` | `#63B233` Apple Green (+ `#005DAA` blue button) | `#63B233` Green | High-octane, modern, night session |

### 5.1 Content-link colors (#399, supersedes #394/#395)

Public-page / share links (the "Public page (QR)" anchors and similar) get an explicit **per-theme link color + underline**, not the generic primary color. The shared `.public-page-link` treatment (used by the `PublicPageLink` component) is always **bold + underlined**, pulls color from the `--link` / `--link-underline` / `--link-hover` tokens below, and brightens toward white on hover. Colors are read against each theme's **dark card surface** (the card fill above). All clear **WCAG AA-normal (≥4.5:1)** (measured, sRGB relative-luminance).

| Theme | `--link` (+ underline) | `--link-hover` | Contrast vs card |
|---|---|---|---|
| **Off-Season** | `#D2FE00` Neon Yellow | `#FFFFFF` White | 11.4:1 — AA ✅ |
| **Christmas** | `#E5B842` Champagne Gold | `#FFFFFF` White | 7.7:1 — AA ✅ |
| **Australian Open** | `#CCFF00` Volt Yellow | `#FFFFFF` White | 13.2:1 — AA ✅ |
| **Clay** | `#E07A5F` Light Terracotta | `#FFFFFF` White | 5.0:1 — AA ✅ |
| **Grass** | `#C5A3E8` Bright Lavender | `#FFFFFF` White | 6.1:1 — AA ✅ |
| **US Open** | `#63B233` Apple Green | `#FFFFFF` White | 5.5:1 — AA ✅ |

> **Clay/Grass link note:** the *accent* clay orange `#C1522D` (2.9:1 on the espresso card) and Wimbledon purple `#452263` (1.5:1 on the ivy card) are too dark for a link on a dark surface, so links use the lighter **terracotta `#E07A5F`** and **lavender `#C5A3E8`** — both clear AA-normal — while the darker accents stay for card rims/buttons where they sit against text or the canvas.

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

Under the all-dark inversion (#399) **every** theme is light-on-dark, so the checklist applies uniformly:

- **No dark-on-dark:** never put dark text on the dark cards — use the near-white `--foreground`/`--card-foreground` or a bright accent. Button *labels* stay dark only where the button fill is light (neon `#212529`, volt `#0A1D37`, gold `#112A1F`).
- **No vibrancy collisions:** don't place a vivid accent border directly against a vivid background. The deep, desaturated canvases are chosen so the vivid rims/accents/links pop cleanly.
- **Links use a lighter variant where the accent is too dark:** clay and grass links are `#E07A5F` / `#C5A3E8` (not the darker `#C1522D` / `#452263` accents) so they clear AA on the dark card.
- **Measured WCAG AA (sRGB relative-luminance) — all pass AA-normal (≥4.5:1) for font + link:**

  | Theme | Font vs card | Link vs card | Muted vs card | Button label vs button |
  |---|---|---|---|---|
  | Off-Season | 12.6:1 ✅ | 11.4:1 ✅ | 8.9:1 ✅ | 13.2:1 ✅ |
  | Christmas | 14.4:1 ✅ | 7.7:1 ✅ | 11.9:1 ✅ | 8.2:1 ✅ |
  | Australian Open | 14.3:1 ✅ | 13.2:1 ✅ | 12.6:1 ✅ | 14.4:1 ✅ |
  | Clay | 13.9:1 ✅ | 5.0:1 ✅ | 13.9:1 ✅ | 4.65:1 ✅ |
  | Grass | 12.4:1 ✅ | 6.1:1 ✅ | 12.4:1 ✅ | 4.39:1 — AA-large ✅ (14px+ bold button) |
  | US Open | 14.6:1 ✅ | 5.5:1 ✅ | 5.7:1 ✅ | 6.7:1 ✅ |

  > **Grass button:** white on lavender `#9362C4` is 4.39:1 — below AA-normal but above **AA-large (≥3.0:1)**, which the bold ≥14px button label qualifies for. All font/link/muted pairs clear AA-normal.

---

## 8. Implementation plan

### Web
- Each theme is an override of the existing CSS custom properties in `web/src/index.css`. Map 60/30/10 onto the shadcn tokens: `--background` (canvas), `--card` / `--card-foreground` (cards), `--primary` / `--border` / `--ring` / `--accent` (accents), plus `--link` / `--link-underline` / `--link-hover` for content links. **No per-component color hard-coding.**
- Under #399 **every theme is a full dark token set** (dark canvas/card + light `--foreground`/`--card-foreground` + sensible dark `--popover`/`--secondary`/`--muted`/`--input` neutrals). The prior split — four light themes with white cards vs. two dark ones — is gone; the header `BrandMark` (#398, `currentColor`) and `PublicPageLink` (#395, `--link*`) auto-adapt with no component changes. The favicon accents (#396) are reconciled to the new palette (grass → lavender `#9362C4`; other tints kept pragmatic for 16px tab legibility).
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

- **Interactive preview:** [`ui-seasonal-theming-preview.html`](./ui-seasonal-theming-preview.html) (this folder).
- Tracking issue [#378](https://github.com/cybergrouch/skopeo/issues/378).
- `web/src/index.css` (theme tokens / dark-mode block), Tailwind config, shadcn UI under `web/src/components/ui/`.
- Admin tab: `web/src/routes/dashboard/AdminTab.tsx`.
- Web UI architecture: `docs/engineering/architecture/WEB_UI_ARCHITECTURE.md`.
