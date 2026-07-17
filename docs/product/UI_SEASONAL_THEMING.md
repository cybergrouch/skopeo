# UI Seasonal Theming — Design Discussion

**Status:** Built · **Tracking issues:** [#378](https://github.com/cybergrouch/skopeo/issues/378) (original palettes) · [#399](https://github.com/cybergrouch/skopeo/issues/399) (all-dark inversion) · [#409](https://github.com/cybergrouch/skopeo/issues/409) (vibrant-depth — shipped)

Admin-controlled, tennis-season / court-surface **color themes** for the web UI, propagated live to all connected clients. This document captures the discussion, decisions, palettes (with visual swatches), and an implementation plan.

> **Revised to "Vibrant Depth" (#409).** The all-dark inversion (#399/#402) went **too dark/desaturated** — the court identities got muted (Christmas read *royal* not *festive*; Wimbledon/Roland-Garros/Melbourne colors muted). The four **court seasons** (Christmas, Australian Open, Clay, Grass) are now **Vibrant Depth**: a **saturated court-tone canvas (60%)** + a **deep same-hue card (30%)** with **white text + a 2px white rim** + a **vibrant accent (10%)**. **Off-Season and US Open stay all-dark** (US Open = intended night-session look). The palettes and link colors below reflect this revision; the four court rows changed, Off-Season/US Open are unchanged. This still **supersedes the #394/#395 link palette**. Passages marked *(historical)* preserve the original light-theme reasoning for context.

---

## 1. Problem & framing

The app is visually dominated by plain white and needs color/personality.

Under the all-dark inversion (#399) there are still **two surfaces** — the **page canvas** behind the cards and the **cards** themselves — but now **both are dark**. Font contrast therefore matters **everywhere**: light (near-white) text sits on dark cards in every theme. This actually *simplifies* the prior logic — the "US Open/Christmas are the only dark themes" special case is gone, so `--foreground`/`--card-foreground` are light in every block, and the header `BrandMark` (#398, `currentColor`=foreground) auto-adapts to light on every theme with no per-theme code.

> *(historical, #378)* The original framing treated font contrast as a non-issue for the four light themes (dark text on white cards) and a concern only for the two dark themes. The inversion makes it a uniform light-on-dark concern.

---

## 2. Design strategy — 60-30-10, "Vibrant Depth" for the court seasons

Apply the classic **60-30-10** rule. The all-dark inversion (#399) made every block dark; #409 **lifts the four court seasons out of muddy blacks** while keeping Off-Season/US Open dark:

- **60% — Background (page canvas):** *court seasons (#409)* a **saturated court-tone** canvas (Santa Crimson / Vivid Stadium Blue / Brick Terracotta / Lush Lawn Green) so the season identity is unmistakable. *Off-Season/US Open* keep a deep desaturated shadow shade.
- **30% — Cards:** a **deep same-hue** card (Holiday Evergreen / Melbourne Twilight / Earth Chocolate / Forest Green) giving depth under white text; a **2px white rim** crisply breaks it off the saturated canvas. *Off-Season/US Open* keep a rich neutral dark card with a 1px accent rim.
- **10% — Accents & borders:** the **vibrant, authentic** colors, reserved *strictly* for buttons, badges, links, and hover states — popping against the deep card.
- **Font:** **white / high-contrast near-white** per theme.

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

Six themes give a cohesive **year-round rotation**. Under #409 the four **court seasons** use Vibrant Depth (saturated court-tone canvas + deep same-hue card + white text + 2px white rim + vibrant accent); **Off-Season and US Open stay all-dark**. Swatches below are illustrative.

> 🖼️ **Interactive preview:** [`ui-seasonal-theming-preview.html`](./ui-seasonal-theming-preview.html) renders all six themes (canvas + card + accented button/border/link) live. Open it in a browser, or view a branch copy via [htmlpreview.github.io](https://htmlpreview.github.io/). Its CSS is also the reference for the per-theme **muted body-text** color (a `--muted-foreground` token): Off-Season `#CED4DA`, Christmas `#FFFFFF` (≈0.9 alpha), AO `#E2E8F0`, Clay `#FDF6F0`, Grass `#F4F9F5`, US Open `#94A3B8`.

### ⚙️ Off-Season — *Sleek, structural, minimal*
Winter training / rest vibe (late Nov–Dec): premium sleek monochrome + concrete, evoking dimly lit indoor practice facilities and empty night stadiums.

| Role | Swatch | Color |
|---|---|---|
| Background (60%) | ![bg](https://placehold.co/90x24/1A1D20/1A1D20.png) | `#1A1D20` Ink Gray |
| Card (30%) | ![card](https://placehold.co/90x24/2B3035/F8F9FA.png?text=card) | `#2B3035` Steel Slate · font `#F8F9FA` Snow White · muted `#CED4DA` |
| Accent (10%) | ![neon](https://placehold.co/44x24/D2FE00/D2FE00.png) ![steel](https://placehold.co/44x24/495057/495057.png) | `#D2FE00` Neon Yellow (button, font `#212529`) + `#495057` Steel card rim |

**Border strategy:** a steel `#495057` card rim against the ink canvas; the neon `#D2FE00` is the button + link pop.

### 🎄 Christmas — *Festive, cozy, premium* · **Vibrant Depth (#409)**
Unmistakable holiday cheer: a bold **Santa Crimson** canvas with a **Holiday Evergreen** card, crisp white text, a **2px white rim**, and an **Elfie Gold** call to action. Badge = white background, evergreen text.

| Role | Swatch | Color |
|---|---|---|
| Background (60%) | ![bg](https://placehold.co/90x24/CE2029/CE2029.png) | `#CE2029` Santa Crimson |
| Card (30%) | ![card](https://placehold.co/90x24/0B6623/FFFFFF.png?text=card) | `#0B6623` Holiday Evergreen · font `#FFFFFF` Pure White · muted `#FFFFFF` (≈0.9) |
| Accent (10%) | ![gold](https://placehold.co/44x24/FFD700/FFD700.png) ![white](https://placehold.co/44x24/FFFFFF/FFFFFF.png) | `#FFD700` Elfie Gold (button, font `#063B14`; link) + `#FFFFFF` white 2px card rim |

**Border strategy:** a crisp **2px white rim** breaks the evergreen card off the crimson canvas; gold is the button + link pop. White text 7.15:1 and gold link 5.09:1 both clear AA-normal on the evergreen card; the gold button's `#063B14` label is 9.11:1.

### 🔵 Australian Open — *Electric, cool, summer night* · **Vibrant Depth (#409)**
| Role | Swatch | Color |
|---|---|---|
| Background (60%) | ![bg](https://placehold.co/90x24/0080C8/0080C8.png) | `#0080C8` Vivid Stadium Blue |
| Card (30%) | ![card](https://placehold.co/90x24/0A1D37/FFFFFF.png?text=card) | `#0A1D37` Melbourne Twilight · font `#FFFFFF` Pure White · muted `#E2E8F0` |
| Accent (10%) | ![neon](https://placehold.co/44x24/CCFF00/CCFF00.png) ![white](https://placehold.co/44x24/FFFFFF/FFFFFF.png) | `#CCFF00` Volt Yellow (button, font `#0A1D37`; link) + `#FFFFFF` white 2px card rim |

**Border strategy:** a 2px white rim off the vivid stadium-blue canvas; volt yellow for the electric button + link pop.

### 🟠 Clay — *Warm, organic, earthy* · **Vibrant Depth (#409)**
| Role | Swatch | Color |
|---|---|---|
| Background (60%) | ![bg](https://placehold.co/90x24/C1522D/C1522D.png) | `#C1522D` Brick Terracotta |
| Card (30%) | ![card](https://placehold.co/90x24/2B1A12/FFFFFF.png?text=card) | `#2B1A12` Earth Chocolate · font `#FFFFFF` Pure White · muted `#FDF6F0` |
| Accent (10%) | ![terra](https://placehold.co/44x24/E07A5F/E07A5F.png) ![white](https://placehold.co/44x24/FFFFFF/FFFFFF.png) | `#E07A5F` Terracotta (button + link) + `#FFFFFF` white 2px card rim |

**Border strategy:** a 2px white rim off the brick-terracotta canvas; the terracotta `#E07A5F` is the button + link. **Button-label deviation:** white on `#E07A5F` is only 2.95:1 (fails AA, below AA-large), so the button *label* uses the dark earth `#2B1A12` (5.65:1) rather than the matrix's `#FFFFFF`. The `#E07A5F` link on the Earth Chocolate card is 5.65:1 (AA).

### 🟢 Grass — *Classic, elegant, prestigious* · **Vibrant Depth (#409)**
| Role | Swatch | Color |
|---|---|---|
| Background (60%) | ![bg](https://placehold.co/90x24/00703C/00703C.png) | `#00703C` Lush Lawn Green |
| Card (30%) | ![card](https://placehold.co/90x24/1B3B2B/FFFFFF.png?text=card) | `#1B3B2B` Forest Green · font `#FFFFFF` Pure White · muted `#F4F9F5` |
| Accent (10%) | ![purple](https://placehold.co/44x24/452263/452263.png) ![neon](https://placehold.co/44x24/CCFF00/CCFF00.png) ![white](https://placehold.co/44x24/FFFFFF/FFFFFF.png) | `#452263` Wimbledon Purple (button) + `#CCFF00` Optic Tennis Yellow link (#417) + `#FFFFFF` white 2px card rim |

**Border strategy:** a 2px white rim off the lush-lawn-green canvas; deep Wimbledon Purple `#452263` on the button (white label 12.64:1), and **Optic Tennis Yellow `#CCFF00`** for inline links (10.47:1) — the "Balanced Wimbledon" accent (#417), a brighter jump off the forest-green card than the earlier lavender `#C5A3E8` (5.73:1).

### 🌃 US Open — *High-octane, modern, night session*
| Role | Swatch | Color |
|---|---|---|
| Background (60%) | ![bg](https://placehold.co/90x24/0B0F19/0B0F19.png) | `#0B0F19` Midnight |
| Card (30%) | ![card](https://placehold.co/90x24/1E293B/FFFFFF.png?text=card) | `#1E293B` Slate Navy · font `#FFFFFF` Pure White · muted `#94A3B8` |
| Accent (10%) | ![blue](https://placehold.co/44x24/005DAA/005DAA.png) ![green](https://placehold.co/44x24/63B233/63B233.png) | `#005DAA` US Open Blue (button) + `#63B233` Apple Green card rim + link |

**Border strategy:** Apple Green as a thin glowing card rim + link; US Open Blue for the primary button. (US Open keeps the two-accent split — blue primary, green accent — from #378.)

---

## 5. Summary matrix

The four court seasons are Vibrant Depth (#409); Off-Season + US Open stay all-dark. Font = the white/near-white `--foreground`/`--card-foreground`; button = `--primary` (with `--primary-foreground` label); card rim = `--border`/`--ring` (white + 2px for the court seasons); pop accent = `--accent`.

| Theme | 60% Canvas | 30% Card + font | 10% Accent (pop) | Card rim | Mood |
|---|---|---|---|---|---|
| **Off-Season** *(dark)* | `#1A1D20` Ink Gray | `#2B3035` Steel Slate · `#F8F9FA` | `#D2FE00` Neon Yellow | `#495057` Steel (1px) | Sleek, structural, minimal |
| **Christmas** *(vibrant)* | `#CE2029` Santa Crimson | `#0B6623` Holiday Evergreen · `#FFFFFF` | `#FFD700` Elfie Gold | `#FFFFFF` White (2px) | Festive, cozy, premium |
| **Australian Open** *(vibrant)* | `#0080C8` Vivid Stadium Blue | `#0A1D37` Melbourne Twilight · `#FFFFFF` | `#CCFF00` Volt Yellow | `#FFFFFF` White (2px) | Electric, cool, summer night |
| **Clay** *(vibrant)* | `#C1522D` Brick Terracotta | `#2B1A12` Earth Chocolate · `#FFFFFF` | `#E07A5F` Terracotta | `#FFFFFF` White (2px) | Warm, organic, earthy |
| **Grass** *(vibrant)* | `#00703C` Lush Lawn Green | `#1B3B2B` Forest Green · `#FFFFFF` | `#452263` Wimbledon Purple (+ `#CCFF00` Optic Tennis Yellow link) | `#FFFFFF` White (2px) | Classic, elegant, prestigious |
| **US Open** *(dark)* | `#0B0F19` Midnight | `#1E293B` Slate Navy · `#FFFFFF` | `#63B233` Apple Green (+ `#005DAA` blue button) | `#63B233` Green (1px) | High-octane, modern, night session |

### 5.1 Content-link colors (#399, supersedes #394/#395)

Public-page / share links (the "Public page (QR)" anchors and similar) get an explicit **per-theme link color + underline**, not the generic primary color. The shared `.public-page-link` treatment (used by the `PublicPageLink` component) is always **bold + underlined**, pulls color from the `--link` / `--link-underline` / `--link-hover` tokens below, and brightens toward white on hover. **Widened in #417:** the same token-based `.content-link` treatment (shared `ContentLink` component) now also styles **inline in-app anchors** — Activity Log Who/Target cells, Reports player rows, Profile "view all matches" — so they no longer fall back to the generic primary color (which failed AA on some cards). Colors are read against each theme's **card surface** (the card fill above). All clear **WCAG AA-normal (≥4.5:1)** (measured, sRGB relative-luminance).

| Theme | `--link` (+ underline) | `--link-hover` | Contrast vs card |
|---|---|---|---|
| **Off-Season** | `#D2FE00` Neon Yellow | `#FFFFFF` White | 11.4:1 — AA ✅ |
| **Christmas** | `#FFD700` Elfie Gold | `#FFFFFF` White | 5.09:1 — AA ✅ (on `#0B6623`) |
| **Australian Open** | `#CCFF00` Volt Yellow | `#FFFFFF` White | 14.37:1 — AA ✅ (on `#0A1D37`) |
| **Clay** | `#E07A5F` Terracotta | `#FFFFFF` White | 5.65:1 — AA ✅ (on `#2B1A12`) |
| **Grass** | `#CCFF00` Optic Tennis Yellow | `#FFFFFF` White | 10.47:1 — AA ✅ (on `#1B3B2B`) |
| **US Open** | `#63B233` Apple Green | `#FFFFFF` White | 5.5:1 — AA ✅ |

> **Clay/Grass link note:** clay `#E07A5F` still doubles as the button, and Wimbledon purple `#452263` is the grass button — too dark to double as a link on the deep card. Clay reuses `#E07A5F` for both the link and the button fill (the button *label* is the dark `#2B1A12`, see §5/§7). Grass's link is the "Balanced Wimbledon" **Optic Tennis Yellow `#CCFF00`** (#417) — it jumps off the forest-green card at 10.47:1 and stays distinct from the purple action button (it replaced the earlier lavender `#C5A3E8`, which passed AA at 5.73:1 but read faint).

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

Under Vibrant Depth (#409) the four court cards carry **white** text; Off-Season/US Open keep near-white on their dark cards. The checklist:

- **No low-contrast text on card:** white `--card-foreground` + the vibrant `--link`/`--muted-foreground` all clear AA-normal on each card. Button *labels* stay dark where the button fill is light (neon `#212529`, gold `#063B14`, volt `#0A1D37`, clay `#2B1A12`).
- **White 2px card rim (court seasons):** the crisp white rim breaks the deep card off the saturated canvas (a scoped 2px `border-width` on `.bg-card` under christmas/ao/clay/grass; `--border` is already white). Off-Season/US Open keep their 1px accent rims.
- **Clay button-label deviation:** the matrix gives clay's button `#E07A5F` / `#FFFFFF`, but white on `#E07A5F` is 2.95:1 (fails AA, below AA-large too), so the label uses the dark earth `#2B1A12` (5.65:1). Grass links use **Optic Tennis Yellow `#CCFF00`** (10.47:1, #417) — the `#452263` button color is too dark for a link on the card.
- **Measured WCAG AA (sRGB relative-luminance) — all pass AA-normal (≥4.5:1):**

  | Theme | Font vs card | Link vs card | Muted vs card | Button label vs button |
  |---|---|---|---|---|
  | Off-Season | 12.6:1 ✅ | 11.4:1 ✅ | 8.9:1 ✅ | 13.2:1 ✅ |
  | Christmas | 7.15:1 ✅ | 5.09:1 ✅ | ~6.0:1 ✅ (white @0.9) | 9.11:1 ✅ |
  | Australian Open | 16.88:1 ✅ | 14.37:1 ✅ | 13.70:1 ✅ | 14.37:1 ✅ |
  | Clay | 16.68:1 ✅ | 5.65:1 ✅ | 15.58:1 ✅ | 5.65:1 ✅ (`#2B1A12` label) |
  | Grass | 12.30:1 ✅ | 10.47:1 ✅ (`#CCFF00` link, #417) | 11.55:1 ✅ | 12.64:1 ✅ |
  | US Open | 14.6:1 ✅ | 5.5:1 ✅ | 5.7:1 ✅ | 6.7:1 ✅ |

  > **All pairs clear AA-normal.** The clay button meets AA only because its label is the dark `#2B1A12` (not `#FFFFFF`); white on `#E07A5F` would be 2.95:1.

---

## 8. Implementation plan

### Web
- Each theme is an override of the existing CSS custom properties in `web/src/index.css`. Map 60/30/10 onto the shadcn tokens: `--background` (canvas), `--card` / `--card-foreground` (cards), `--primary` / `--border` / `--ring` / `--accent` (accents), plus `--link` / `--link-underline` / `--link-hover` for content links. **No per-component color hard-coding.**
- Under #409 the four court seasons are **Vibrant Depth** (saturated canvas + deep same-hue card + white text + 2px white rim); Off-Season/US Open stay full-dark. Every block is still a full token set (canvas/card + `--foreground`/`--card-foreground` + sensible `--popover`/`--secondary`/`--muted`/`--input` neutrals). The header `BrandMark` (#398, `currentColor`) and `PublicPageLink` (#395, `--link*`) auto-adapt with no component changes. The favicon accents (#396) are reconciled to the vibrant palette (christmas → Santa Crimson `#CE2029`, ao → `#0080C8`, clay → `#C1522D`, grass → `#00703C`); the gold `#FFD700` christmas accent is only 1.40:1 against the mark's white ball at 16px, so the favicon uses the crimson canvas instead. Off-Season/US Open favicon tints unchanged.
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

---

## Color-scheme decision log & rationale

How the seasonal palette evolved, and **why** — so the reasoning isn't lost across revisions.

### 1. Light canvas + white cards — *initial* (#378)
Ultra-muted **pastel canvas** (60%), **white cards** (30%), vibrant court-color **accents** (10%) — the "Muted Base" 60-30-10. Clean and legible, but it read as *light mode with color accents*; the ask was for a **darker, more dramatic** feel.

### 2. All-dark inversion (#399 / #402, shipped)
Flipped to **immersive dark mode**: deep, desaturated **shadow canvases**, dark neutral cards, light text, vibrant accents. It delivered the drama — **but the deep/desaturated canvases muted the very court colors that give each season its identity.** In practice:
- **Christmas** read *royal / luxury* (deep wine), **not festive**.
- **Wimbledon** lush green, **Roland-Garros** terracotta, and **Melbourne (AO)** blue all came out **muted**.
- **US Open** read correctly — the dark night-session mood is *intended* there.

### 3. Vibrant Depth — *current target* (#409)
Keep the dark, dramatic base **only for US Open** (night session) and **Off-Season** (neutral training/rest). For the other seasons, apply a **Vibrant Depth** twist on the inverted 60-30-10:
- **60% canvas** → a **saturated, lighter court tone** (the real surface color) so the season's identity pops.
- **30% card** → a **deep version of the same hue** (depth) with **white text** and a **crisp 2px white border** that breaks cleanly off the saturated canvas.
- **10% accent** → stays vibrant (buttons/links).

**Per-theme rationale (target values live in #409):**
- **Christmas** — deep wine → **Santa Crimson** canvas (`#CE2029`) + **Holiday Evergreen** card (`#0B6623`) + **Snowflake White** text/border + **Elfie Gold** button (`#FFD700`, dark-green text). Reads as unmistakable holiday cheer, not "royal."
- **Australian Open** — ink-black → **Vivid Stadium Blue** canvas (`#0080C8`) + Melbourne-twilight card; neon-yellow buttons cut through.
- **Clay (Roland-Garros)** — dark espresso → **Brick Terracotta** canvas (`#C1522D`) + earth-chocolate card; the clay red becomes the hero.
- **Grass (Wimbledon)** — black-green → **Lush Lawn Green** canvas (`#00703C`) + forest-green card + tournament-purple button.
- **US Open** — *unchanged* (`#0B0F19` night canvas); the dark mood is the point.
- **Off-Season** — *unchanged*; the neutral dark suits the training/rest vibe.

**Principle carried forward:** the 60% canvas should carry the *season's mood at full saturation*; darkness for its own sake (step 2) traded identity for drama. Vibrant Depth keeps the drama (deep cards, white-on-dark text) while restoring the court-color identity.

### 4. Inline-link tokens widened; grass link → Optic Tennis Yellow (#417)
The per-theme `--link` tokens (#394) were only consumed by `.public-page-link` (the shared `PublicPageLink`). **Inline in-app anchors** — Activity Log Who/Target cells, Reports player rows, the Profile "view all matches" link — used a plain `text-primary` treatment, so on the grass card `#1b3b2b` they fell back to the Wimbledon-purple `--primary` `#452263`, which is nearly invisible (fails WCAG AA). Fix: those anchors now use a shared `.content-link` / `ContentLink` component that pulls the same `--link` / `--link-underline` / `--link-hover` tokens, so they're theme-aware everywhere (not a blanket `a {}` — nav links and shadcn button-anchors are untouched). For grass the "Balanced Wimbledon" decision switched `--link` from the lavender `#C5A3E8` (5.73:1, read faint) to **Optic Tennis Yellow `#CCFF00`** (10.47:1 on `#1b3b2b`) — a brighter jump that stays distinct from the purple action button (button + 2px white rim unchanged). Every theme's `--link` was re-checked against its card and all still clear AA-normal, so no other theme's link token changed.

