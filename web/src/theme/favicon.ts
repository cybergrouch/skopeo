/**
 * Theme-aware favicon (#386). The tab favicon recolors to match the active seasonal theme so the
 * browser tab reflects the season, in lockstep with `data-theme` (see ThemeProvider).
 *
 * The shipped `public/favicon.svg` embeds a raster PNG of the brand mark, so it can't be live
 * recolored. Instead we render a small, purpose-built *vector* mark here — a rounded tile with a
 * tennis-ball seam — as an inline SVG string, tint it with the theme's accent color, and swap it
 * into the `<link rel="icon">` via a fresh `data:` URI on every change (a new href sidesteps stale
 * favicon caching). The tab favicon is SVG-only (no .ico/.png tab fallback in index.html), so it's
 * fully theme-driven. The iOS home-screen (`apple-touch-icon.png`) and PWA installed-app icons
 * (`site.webmanifest`) require PNG and remain the static, unthemed brand mark.
 */
import type { ThemeName } from "@/lib/season";

/**
 * Per-theme favicon tint (#386; reconciled with the vibrant-depth palette in #409). The mark is a
 * white tennis ball on an accent-tinted tile, so the tint must keep the *white ball* legible at 16px
 * (WCAG ~3:1+). We take each vibrant theme's court tone, dropping to a still-on-brand color where the
 * raw accent would wash the ball out:
 * - offseason: dark slate (the neon `#D2FE00` washes out on light tabs). [unchanged, #402]
 * - christmas: Santa Crimson `#CE2029` (the canvas). The gold accent `#FFD700` requested in #409 is
 *   only 1.40:1 against the white ball — invisible at 16px — so we tint with the vibrant crimson
 *   canvas instead (5.44:1), which still reads unmistakably festive.
 * - ao: Vivid Stadium Blue `#0080C8` (canvas; 4.27:1). The volt `#CCFF00` accent washes out.
 * - clay: Brick Terracotta `#C1522D` (canvas; 4.65:1; deeper/steadier than the `#E07A5F` accent).
 * - grass: Lush Lawn Green `#00703C` (canvas; 6.21:1) — grass is green again under #409.
 * - uso: apple green. [unchanged, #402]
 */
const THEME_ACCENT: Record<ThemeName, string> = {
  offseason: "#212529",
  christmas: "#CE2029",
  ao: "#0080C8",
  clay: "#C1522D",
  grass: "#00703C",
  uso: "#63B233",
};

/** Neutral (no `data-theme`) tint — the default dark-slate mark. */
const NEUTRAL_ACCENT = "#212529";

/**
 * The scalable favicon mark, parameterized by accent color. A rounded tile with a white ball bearing
 * the tennis seam, tinted by `accent`. Kept intentionally simple so it stays legible at 16×16.
 */
export function faviconSvg(accent: string): string {
  return (
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">` +
    `<rect width="64" height="64" rx="14" fill="${accent}"/>` +
    `<circle cx="32" cy="32" r="17" fill="#ffffff"/>` +
    `<path d="M20 20a24 24 0 0 1 0 24" fill="none" stroke="${accent}" stroke-width="3.5" stroke-linecap="round"/>` +
    `<path d="M44 20a24 24 0 0 0 0 24" fill="none" stroke="${accent}" stroke-width="3.5" stroke-linecap="round"/>` +
    `</svg>`
  );
}

/** Build a `data:` URI for the favicon SVG. Encoded so `#` in colors doesn't truncate the URI. */
export function faviconDataUri(accent: string): string {
  return `data:image/svg+xml,${encodeURIComponent(faviconSvg(accent))}`;
}

/** The accent color used for a given theme name (or the neutral default when `theme` is absent). */
export function accentFor(theme: ThemeName | undefined): string {
  return theme != null ? THEME_ACCENT[theme] : NEUTRAL_ACCENT;
}

/**
 * Set the tab favicon to a variant tinted for `theme` (or the neutral mark when undefined).
 * Reuses the single dynamic `<link rel="icon" data-theme-favicon>` element — updating its href
 * rather than piling up new links — and creates it once on first call.
 */
export function applyThemeFavicon(theme: ThemeName | undefined): void {
  // document.head is always present client-side (this runs from the ThemeProvider effect).
  const head = document.head;
  let link = head.querySelector<HTMLLinkElement>("link[data-theme-favicon]");
  if (link == null) {
    link = document.createElement("link");
    link.setAttribute("rel", "icon");
    link.setAttribute("type", "image/svg+xml");
    link.setAttribute("data-theme-favicon", "");
    head.appendChild(link);
  }
  link.setAttribute("href", faviconDataUri(accentFor(theme)));
}
