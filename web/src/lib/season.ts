/**
 * AUTO season resolver (#378). Maps today's date to a theme using the admin-editable schedule (#1196,
 * rules in ./themeSchedule.ts). Pure — no clock reads inside — so it's fully testable.
 * See docs/product/UI_SEASONAL_THEMING.md §6.
 */
import { DEFAULT_SCHEDULE, findRowIndex, resolvePeriodStart, type ScheduleRow } from './themeSchedule'

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
  // Skopeo OG (#512): the original all-white look. Selectable by hand, in the default AUTO schedule for
  // Apr 1–15 (#1194), and what AUTO shows on any date the schedule leaves uncovered (#1196).
  | 'og'

/**
 * Resolve the AUTO theme for `now` from `schedule` (#1196): the theme of the first row containing the
 * date, or Skopeo OG when no row does (or the row's theme is one this build doesn't know). The schedule
 * comes from `GET /api/v1/theme`; {@link DEFAULT_SCHEDULE} only stands in while it loads or if it fails.
 */
export function resolveSeasonTheme(now: Date, schedule: readonly ScheduleRow[] = DEFAULT_SCHEDULE): ThemeName {
  const index = findRowIndex(schedule, now)
  return (index >= 0 ? ENUM_TO_THEME[schedule[index].theme] : undefined) ?? 'og'
}

/**
 * The start boundary (midnight) of the period `now` falls in (#514), from the same schedule
 * {@link resolveSeasonTheme} uses: a row's start, or the day after the previous row ends when `now` is
 * uncovered. The effective-theme precedence compares this against when the user set their local theme to
 * detect a new-season takeover. Pure: no clock reads.
 */
export function resolveSeasonStart(now: Date, schedule: readonly ScheduleRow[] = DEFAULT_SCHEDULE): Date {
  return resolvePeriodStart(schedule, now)
}

/**
 * Maps the backend theme enum to a concrete `data-theme` name. `AUTO` (and any missing/unknown
 * value) is resolved by date; the others map 1:1. See docs/product/UI_SEASONAL_THEMING.md.
 */
const ENUM_TO_THEME: Partial<Record<string, ThemeName>> = {
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
  // Skopeo OG (#512): an explicit pick maps 1:1 like the others; AUTO also produces it (#1194, #1196).
  SKOPEO_OG: 'og',
}

/**
 * Resolve the setting value (poss. AUTO / unknown) to the active `data-theme` name for `now`; AUTO
 * resolves from `schedule`.
 */
export function resolveActiveTheme(
  setting: string | undefined,
  now: Date,
  schedule: readonly ScheduleRow[] = DEFAULT_SCHEDULE,
): ThemeName {
  const pinned = setting != null && setting !== 'AUTO' ? ENUM_TO_THEME[setting] : undefined
  // AUTO, missing, or unknown → resolve by date.
  return pinned ?? resolveSeasonTheme(now, schedule)
}

/** Inputs to the per-user effective-theme precedence (#514). */
export interface EffectiveThemeInputs {
  /** The global theme setting name (e.g. AUTO, GRASS); undefined while it loads → treated as AUTO. */
  global: string | undefined
  /** The caller's local theme name, or null/undefined when they follow the global theme. */
  localTheme: string | null | undefined
  /** When the local theme was last set (ISO string or Date), or null/undefined when unset. */
  localSetAt: string | Date | null | undefined
  /** The AUTO schedule (#1196); undefined while it loads → {@link DEFAULT_SCHEDULE}. */
  schedule?: readonly ScheduleRow[]
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
  const { global, localTheme, localSetAt, schedule = DEFAULT_SCHEDULE } = inputs
  const localName = localTheme == null ? undefined : ENUM_TO_THEME[localTheme]

  // Branch 1: no local choice → follow the global path exactly.
  if (localName === undefined) {
    return resolveActiveTheme(global, now, schedule)
  }

  const globalIsAuto = global == null || global === 'AUTO' || ENUM_TO_THEME[global] === undefined

  // Branch 2: a fixed global never overrides a local choice.
  if (!globalIsAuto) {
    return localName
  }

  // Branch 3: global AUTO — a season that began after the user set local takes over.
  const setAt = localSetAt == null ? null : new Date(localSetAt)
  const seasonStart = resolveSeasonStart(now, schedule)
  if (setAt != null && seasonStart.getTime() > setAt.getTime()) {
    return resolveSeasonTheme(now, schedule)
  }
  return localName
}
