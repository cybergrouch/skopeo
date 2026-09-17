// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

/**
 * Where a viewer entered the public pages from (#1027), so "← Back" can return them there.
 *
 * ## Why not `navigate(-1)`
 *
 * The previous behaviour (#323) stepped back one history entry. That is "where they came from" only
 * for a single hop. Follow `dashboard → player → match → player` and Back walks the chain one page at
 * a time, so returning to the dashboard takes three presses. Worse, browser history includes entries
 * this app did not create, so stepping back can leave the app entirely — `location.key === 'default'`
 * detects only the *first* entry of the session, not "the previous entry is external".
 *
 * Recording the **entry point** instead means one Back returns to wherever the public-page excursion
 * started, however many public hops deep the viewer went.
 *
 * ## Why `sessionStorage`
 *
 * Per-tab, so two tabs exploring different players do not overwrite each other's origin — which
 * `localStorage` would. And it survives a reload, so refreshing a public page (or opening one, then
 * hard-refreshing) does not lose the way back. It is cleared when the tab closes, which is the right
 * lifetime: a new tab genuinely has no origin and should fall back.
 */

const ORIGIN_KEY = 'skopeo.navOrigin'

/**
 * The shareable, public-by-code pages, plus `/about`. These are the pages that *render* the Back
 * control, so they are never themselves an origin — otherwise Back from a match page would return to
 * the player page it came from and the excursion would never end.
 *
 * `/matches/:code/score` is deliberately absent: live scoring is not a public page and owns its own
 * exit controls (#986).
 */
const PUBLIC_PATH_PATTERNS = [
  /^\/players\/[^/]+$/,
  /^\/players\/[^/]+\/matches$/,
  /^\/matches\/[^/]+$/,
  /^\/events\/[^/]+$/,
  /^\/clubs\/[^/]+$/,
  /^\/about$/,
]

/**
 * Auth and onboarding routes. Excluded as origins because "returning" to them is never what a viewer
 * wants — sending someone back to `/login` after they signed in, or to `/complete-profile` after they
 * completed it, would be a trap rather than a courtesy.
 */
const NON_ORIGIN_PATHS = ['/login', '/signup', '/invite', '/complete-profile', '/']

/** True for the public-by-code pages (and `/about`) that render the Back control. */
export function isPublicPath(pathname: string): boolean {
  return PUBLIC_PATH_PATTERNS.some((pattern) => pattern.test(pathname))
}

/** True when a location is somewhere Back could sensibly return a viewer to. */
export function isOriginCandidate(pathname: string): boolean {
  return !isPublicPath(pathname) && !NON_ORIGIN_PATHS.includes(pathname)
}

/**
 * Record `pathWithQuery` as the place the viewer would return to.
 *
 * The query string is kept on purpose: the dashboard syncs its active tab into `?tab=`, so storing it
 * is what makes Back land on the tab the viewer was actually on rather than resetting to Profile —
 * the behaviour #323 wanted, reached more directly than by replaying history.
 */
export function rememberOrigin(pathWithQuery: string): void {
  try {
    sessionStorage.setItem(ORIGIN_KEY, pathWithQuery)
  } catch {
    // A private-mode or quota-exhausted sessionStorage must not break navigation. Back then falls
    // back to /dashboard, which is merely less precise, not broken.
  }
}

/** The recorded origin, or `null` when there is none (cold open, new tab, storage unavailable). */
export function readOrigin(): string | null {
  try {
    const stored = sessionStorage.getItem(ORIGIN_KEY)
    // Guard against a stored value that is no longer a sensible destination — e.g. an origin written
    // by an older build, or a path that has since become public.
    return stored && isOriginCandidate(stored.split('?')[0]) ? stored : null
  } catch {
    return null
  }
}

/** Test seam: drop any recorded origin. */
export function clearOrigin(): void {
  try {
    sessionStorage.removeItem(ORIGIN_KEY)
  } catch {
    // Nothing to do — an unavailable sessionStorage already has no origin to clear.
  }
}
