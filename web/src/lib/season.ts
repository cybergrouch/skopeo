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

/** Month is 1-based here (Jan = 1) to match how the windows read in the design doc. */
interface DateParts {
  month: number
  day: number
}

function partsOf(now: Date): DateParts {
  return { month: now.getMonth() + 1, day: now.getDate() }
}

/** True when (month, day) falls within [start, end] inclusive, on the same-year calendar. */
function inWindow(parts: DateParts, start: DateParts, end: DateParts): boolean {
  const value = parts.month * 100 + parts.day
  return value >= start.month * 100 + start.day && value <= end.month * 100 + end.day
}

/**
 * Resolve the active theme for `now` (inclusive windows, first-match-wins; default `offseason`):
 * Jan 1–Jan 31 → ao; Feb 1–Feb 14 → valentines; Feb 15–Mar 31 → spring; Apr 1–Jun 10 → clay;
 * Jun 11–Jul 31 → grass; Aug 1–Sep 15 → uso; Sep 16–Oct 16 → rainy; Oct 17–Oct 24 → offseason;
 * Oct 25–Nov 2 → halloween; Nov 3–Dec 9 → autumn; Dec 10–Dec 31 → christmas; else → offseason.
 */
export function resolveSeasonTheme(now: Date): ThemeName {
  const parts = partsOf(now)

  if (inWindow(parts, { month: 1, day: 1 }, { month: 1, day: 31 })) return 'ao'
  if (inWindow(parts, { month: 2, day: 1 }, { month: 2, day: 14 })) return 'valentines'
  if (inWindow(parts, { month: 2, day: 15 }, { month: 3, day: 31 })) return 'spring'
  if (inWindow(parts, { month: 4, day: 1 }, { month: 6, day: 10 })) return 'clay'
  if (inWindow(parts, { month: 6, day: 11 }, { month: 7, day: 31 })) return 'grass'
  if (inWindow(parts, { month: 8, day: 1 }, { month: 9, day: 15 })) return 'uso'
  if (inWindow(parts, { month: 9, day: 16 }, { month: 10, day: 16 })) return 'rainy'
  if (inWindow(parts, { month: 10, day: 17 }, { month: 10, day: 24 })) return 'offseason'
  if (inWindow(parts, { month: 10, day: 25 }, { month: 11, day: 2 })) return 'halloween'
  if (inWindow(parts, { month: 11, day: 3 }, { month: 12, day: 9 })) return 'autumn'
  if (inWindow(parts, { month: 12, day: 10 }, { month: 12, day: 31 })) return 'christmas'
  return 'offseason'
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
}

/** Resolve the setting value (poss. AUTO / unknown) to the active `data-theme` name for `now`. */
export function resolveActiveTheme(setting: string | undefined, now: Date): ThemeName {
  if (setting != null && setting !== 'AUTO' && setting in ENUM_TO_THEME) {
    return ENUM_TO_THEME[setting]
  }
  // AUTO, missing, or unknown → resolve by date.
  return resolveSeasonTheme(now)
}
