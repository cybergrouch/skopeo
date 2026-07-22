/**
 * AUTO season resolver (#378). Maps today's date to a court-surface theme using a small, tunable
 * window table (the single source of truth). Pure — no clock reads inside — so it's fully testable.
 * See docs/product/UI_SEASONAL_THEMING.md §6.
 */

/** The concrete theme names, matching the `[data-theme="…"]` blocks in index.css. */
export type ThemeName =
  | 'offseason'
  | 'christmas'
  | 'ao'
  | 'clay'
  | 'grass'
  | 'uso'
  | 'valentines'
  | 'spring'
  | 'rainy'
  | 'halloween'
  | 'autumn'
  // Skopeo OG (#512): the original all-white look. Manually-selectable only — deliberately NOT
  // produced by resolveSeasonTheme (it's not part of the AUTO rotation).
  | 'og'

/** Month is 1-based here (Jan = 1) to match how the windows read in the design doc. */
interface DateParts {
  month: number
  day: number
}

function partsOf(now: Date): DateParts {
  return { month: now.getMonth() + 1, day: now.getDate() }
}

/** Ordinal (month*100 + day) used to compare (month, day) pairs on the same-year calendar. */
function ordinal(parts: DateParts): number {
  return parts.month * 100 + parts.day
}

/**
 * The single source of truth for the seasonal windows (#378, #514): each entry BEGINS on (month, day)
 * and runs up to (but not including) the next entry's start. Ordered by start; contiguous and covering
 * the whole year (Jan 1 → Dec 31), so every date falls in exactly one window. Both
 * {@link resolveSeasonTheme} (which theme) and {@link resolveSeasonStart} (which start boundary) derive
 * from this table.
 */
const SEASON_WINDOWS: Array<{ start: DateParts; theme: ThemeName }> = [
  { start: { month: 1, day: 1 }, theme: 'ao' },
  { start: { month: 2, day: 1 }, theme: 'valentines' },
  { start: { month: 2, day: 15 }, theme: 'spring' },
  { start: { month: 4, day: 1 }, theme: 'clay' },
  { start: { month: 6, day: 11 }, theme: 'grass' },
  { start: { month: 8, day: 1 }, theme: 'uso' },
  { start: { month: 9, day: 16 }, theme: 'rainy' },
  { start: { month: 10, day: 17 }, theme: 'offseason' },
  { start: { month: 10, day: 25 }, theme: 'halloween' },
  { start: { month: 11, day: 3 }, theme: 'autumn' },
  { start: { month: 12, day: 10 }, theme: 'christmas' },
]

/** The index of the window `now` falls in: the last window whose start is on/before `now`. */
function windowIndexOf(now: Date): number {
  const value = ordinal(partsOf(now))
  let index = 0
  for (let i = 0; i < SEASON_WINDOWS.length; i += 1) {
    if (value >= ordinal(SEASON_WINDOWS[i].start)) index = i
  }
  return index
}

/**
 * Resolve the active theme for `now` (the last window whose start is on/before `now` wins):
 * Jan 1 → ao; Feb 1 → valentines; Feb 15 → spring; Apr 1 → clay; Jun 11 → grass; Aug 1 → uso;
 * Sep 16 → rainy; Oct 17 → offseason; Oct 25 → halloween; Nov 3 → autumn; Dec 10 → christmas. The
 * table is Jan-1-anchored, so any date before Feb 1 lands on the first (ao) window.
 */
export function resolveSeasonTheme(now: Date): ThemeName {
  return SEASON_WINDOWS[windowIndexOf(now)].theme
}

/**
 * The start boundary (midnight, `now`'s year) of the seasonal window `now` falls in (#514) — from the
 * same window table {@link resolveSeasonTheme} uses. Pure: no clock reads. The effective-theme
 * precedence compares this against when the user set their local theme to detect a new-season takeover.
 */
export function resolveSeasonStart(now: Date): Date {
  const { start } = SEASON_WINDOWS[windowIndexOf(now)]
  return new Date(now.getFullYear(), start.month - 1, start.day)
}

/**
 * Maps the backend theme enum to a concrete `data-theme` name. `AUTO` (and any missing/unknown
 * value) is resolved by date; the others map 1:1. See docs/product/UI_SEASONAL_THEMING.md.
 */
const ENUM_TO_THEME: Record<string, ThemeName> = {
  GRASS: 'grass',
  CLAY: 'clay',
  AO: 'ao',
  US_OPEN: 'uso',
  OFF_SEASON: 'offseason',
  CHRISTMAS: 'christmas',
  VALENTINES: 'valentines',
  SPRING: 'spring',
  RAINY: 'rainy',
  HALLOWEEN: 'halloween',
  AUTUMN: 'autumn',
  // Skopeo OG (#512) is manual-only, so it appears here (explicit picks map 1:1) but not in
  // resolveSeasonTheme (the AUTO date rotation).
  SKOPEO_OG: 'og',
}

/** Resolve the setting value (poss. AUTO / unknown) to the active `data-theme` name for `now`. */
export function resolveActiveTheme(setting: string | undefined, now: Date): ThemeName {
  if (setting != null && setting !== 'AUTO' && setting in ENUM_TO_THEME) {
    return ENUM_TO_THEME[setting]
  }
  // AUTO, missing, or unknown → resolve by date.
  return resolveSeasonTheme(now)
}

/** Inputs to the per-user effective-theme precedence (#514). */
export interface EffectiveThemeInputs {
  /** The global theme setting name (e.g. AUTO, GRASS); undefined while it loads → treated as AUTO. */
  global: string | undefined
  /** The caller's local theme name, or null/undefined when they follow the global theme. */
  localTheme: string | null | undefined
  /** When the local theme was last set (ISO string or Date), or null/undefined when unset. */
  localSetAt: string | Date | null | undefined
}

/**
 * Resolve the EFFECTIVE `data-theme` for a logged-in user (#514), applying the per-user local theme
 * over the global one with the finalized precedence:
 *
 *  1. no local theme → the global path ({@link resolveActiveTheme}), unchanged.
 *  2. local set + a fixed (non-AUTO, known) global → local wins (a fixed global never overrides local).
 *  3. local set + global AUTO → compare the current season's start (for `now`) against `localSetAt`:
 *     the season started AFTER the user set local → the seasonal theme wins (new-season takeover);
 *     otherwise → local wins. Re-setting local re-stamps `localSetAt`, reclaiming the choice until the
 *     next season boundary.
 *
 * Pure: `now` is passed in, never read from the clock.
 */
export function resolveEffectiveTheme(inputs: EffectiveThemeInputs, now: Date): ThemeName {
  const { global, localTheme, localSetAt } = inputs

  // Branch 1: no local choice → follow the global path exactly.
  if (localTheme == null || !(localTheme in ENUM_TO_THEME)) {
    return resolveActiveTheme(global, now)
  }

  const localName = ENUM_TO_THEME[localTheme]
  const globalIsAuto = global == null || global === 'AUTO' || !(global in ENUM_TO_THEME)

  // Branch 2: a fixed global never overrides a local choice.
  if (!globalIsAuto) {
    return localName
  }

  // Branch 3: global AUTO — a season that began after the user set local takes over.
  const setAt = localSetAt == null ? null : new Date(localSetAt)
  const seasonStart = resolveSeasonStart(now)
  if (setAt != null && seasonStart.getTime() > setAt.getTime()) {
    return resolveSeasonTheme(now)
  }
  return localName
}
