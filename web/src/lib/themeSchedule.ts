/**
 * The AUTO theme schedule (#1196): yearless rows of `{ name, theme, start, end }`, inclusive and repeating
 * every year. Rows are tested top to bottom and the first one containing the date wins; a date no row
 * covers shows Skopeo OG. An end before the start wraps past Dec 31, and a range ending Feb 28 also covers
 * Feb 29. The same rules are enforced on the server (`ThemeScheduleRules.kt`) — this copy is what lets the
 * editor show problems live. Pure: no clock reads. See docs/product/UI_SEASONAL_THEMING.md §6.
 */

/** A yearless date; month is 1-based (Jan = 1). */
export interface ScheduleDate {
  month: number
  day: number
}

/** One schedule row. `theme` is a backend theme enum name (e.g. `CLAY`), never `AUTO`. */
export interface ScheduleRow {
  name: string
  theme: string
  start: ScheduleDate
  end: ScheduleDate
}

/** An inclusive run of yearless dates. */
export interface ScheduleSpan {
  start: ScheduleDate
  end: ScheduleDate
}

/** One save error, shaped like the server's `ValidationProblem`: 1-based `rows`, with their names. */
export interface ScheduleProblem {
  code: 'OVERLAP' | 'DUPLICATE_NAME' | 'MISSING_NAME' | 'MISSING_THEME' | 'AUTO_THEME' | 'INVALID_DATE'
  message: string
  rows: number[]
  names: string[]
}

/** What the editor shows: errors block a save, gaps are only a warning (they show Skopeo OG). */
export interface ScheduleCheck {
  problems: ScheduleProblem[]
  gaps: ScheduleSpan[]
}

/**
 * The built-in schedule: the hard-coded rotation it replaced, as inclusive ranges with no gaps. The
 * server seeds the same list (V68); the web only uses this while the real schedule loads, or if the
 * request fails.
 */
export const DEFAULT_SCHEDULE: readonly ScheduleRow[] = [
  { name: 'Australian Open', theme: 'AO', start: { month: 1, day: 1 }, end: { month: 1, day: 31 } },
  { name: "Valentine's Day", theme: 'VALENTINES', start: { month: 2, day: 1 }, end: { month: 2, day: 14 } },
  { name: 'Spring', theme: 'SPRING', start: { month: 2, day: 15 }, end: { month: 3, day: 31 } },
  { name: 'Skopeo OG', theme: 'SKOPEO_OG', start: { month: 4, day: 1 }, end: { month: 4, day: 15 } },
  { name: 'Clay', theme: 'CLAY', start: { month: 4, day: 16 }, end: { month: 6, day: 10 } },
  { name: 'Grass', theme: 'GRASS', start: { month: 6, day: 11 }, end: { month: 7, day: 31 } },
  { name: 'US Open', theme: 'US_OPEN', start: { month: 8, day: 1 }, end: { month: 9, day: 15 } },
  { name: 'Rainy', theme: 'RAINY', start: { month: 9, day: 16 }, end: { month: 10, day: 16 } },
  { name: 'Off-Season', theme: 'OFF_SEASON', start: { month: 10, day: 17 }, end: { month: 10, day: 24 } },
  { name: 'Halloween', theme: 'HALLOWEEN', start: { month: 10, day: 25 }, end: { month: 11, day: 2 } },
  { name: 'Autumn', theme: 'AUTUMN', start: { month: 11, day: 3 }, end: { month: 12, day: 9 } },
  { name: 'Christmas', theme: 'CHRISTMAS', start: { month: 12, day: 10 }, end: { month: 12, day: 31 } },
]

export const MONTH_NAMES = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'] as const

/** Days per month on a leap-year calendar, so Feb 29 has a slot of its own. */
const DAYS_IN_MONTH = [31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]

/** Slots on the leap-year calendar: Jan 1 = 0, Feb 29 = 59, Dec 31 = 365. */
const SLOTS = 366
const FEB_29_SLOT = 59

/** True when the date exists in some year: month 1–12 and a day that month can have (Feb 29 included). */
export function isValidDate(date: ScheduleDate): boolean {
  return (
    Number.isInteger(date.month) &&
    Number.isInteger(date.day) &&
    date.month >= 1 &&
    date.month <= 12 &&
    date.day >= 1 &&
    date.day <= DAYS_IN_MONTH[date.month - 1]
  )
}

function slotOf(date: ScheduleDate): number {
  let slot = date.day - 1
  for (let m = 1; m < date.month; m += 1) slot += DAYS_IN_MONTH[m - 1]
  return slot
}

function dateOf(slot: number): ScheduleDate {
  let month = 1
  let remaining = slot
  while (remaining >= DAYS_IN_MONTH[month - 1]) {
    remaining -= DAYS_IN_MONTH[month - 1]
    month += 1
  }
  return { month, day: remaining + 1 }
}

/** The slot a range really ends on: a range ending Feb 28 also covers Feb 29 (decision 2). */
function endSlotOf(end: ScheduleDate): number {
  return end.month === 2 && end.day === 28 ? FEB_29_SLOT : slotOf(end)
}

/** True when the range wraps past Dec 31 — decided on the dates as written, before the Feb 29 extension. */
function wraps(row: ScheduleSpan): boolean {
  return slotOf(row.end) < slotOf(row.start)
}

/** The slots a range with valid dates covers. */
function coverage(row: ScheduleSpan): boolean[] {
  const from = slotOf(row.start)
  const to = endSlotOf(row.end)
  return Array.from({ length: SLOTS }, (_, slot) => (wraps(row) ? slot >= from || slot <= to : slot >= from && slot <= to))
}

/** True when `date` falls in the row's range. A row with an invalid date contains nothing. */
export function rowContains(row: ScheduleSpan, date: ScheduleDate): boolean {
  if (!isValidDate(row.start) || !isValidDate(row.end)) return false
  const slot = slotOf(date)
  const from = slotOf(row.start)
  const to = endSlotOf(row.end)
  return wraps(row) ? slot >= from || slot <= to : slot >= from && slot <= to
}

/** The yearless date of a local `Date`. */
export function partsOf(now: Date): ScheduleDate {
  return { month: now.getMonth() + 1, day: now.getDate() }
}

/** Index of the first row containing `now`, or -1 when no row does (the date shows Skopeo OG). */
export function findRowIndex(schedule: readonly ScheduleRow[], now: Date): number {
  const date = partsOf(now)
  return schedule.findIndex((row) => rowContains(row, date))
}

/**
 * Midnight on the first day of the period `now` falls in (#514's takeover boundary). Inside a row: that
 * row's start — in the previous year when `now` is in the January part of a range that wraps past
 * Dec 31. Inside a gap: the day after the previous row ends, so a gap counts as a season of its own. With
 * no rows at all, Jan 1 of `now`'s year. A start of Feb 29 in a common year lands on Mar 1, which is the
 * day that period really begins that year.
 */
export function resolvePeriodStart(schedule: readonly ScheduleRow[], now: Date): Date {
  const year = now.getFullYear()
  const index = findRowIndex(schedule, now)
  if (index >= 0) {
    const row = schedule[index]
    const inPreviousYear = wraps(row) && slotOf(partsOf(now)) < slotOf(row.start)
    return new Date(inPreviousYear ? year - 1 : year, row.start.month - 1, row.start.day)
  }
  for (let back = 1; back <= SLOTS; back += 1) {
    if (findRowIndex(schedule, new Date(year, now.getMonth(), now.getDate() - back)) >= 0) {
      return new Date(year, now.getMonth(), now.getDate() - back + 1)
    }
  }
  return new Date(year, 0, 1)
}

/**
 * The next day (after `now`) on which the resolved row changes — including moving into or out of a gap —
 * and the row it changes to (-1 for a gap). Null when nothing changes within a year (one row covers every
 * day, or the schedule is empty).
 */
export function nextChange(schedule: readonly ScheduleRow[], now: Date): { date: Date; index: number } | null {
  const current = findRowIndex(schedule, now)
  for (let ahead = 1; ahead <= SLOTS; ahead += 1) {
    const date = new Date(now.getFullYear(), now.getMonth(), now.getDate() + ahead)
    const index = findRowIndex(schedule, date)
    if (index !== current) return { date, index }
  }
  return null
}

/** "Apr 16", or "month 13, day 5" when there is no such month (the day is shown as given). */
export function formatDate(date: ScheduleDate): string {
  return date.month >= 1 && date.month <= 12
    ? `${MONTH_NAMES[date.month - 1]} ${date.day}`
    : `month ${date.month}, day ${date.day}`
}

/** "Apr 16 – Jun 10", or a single date when the span is one day. */
export function formatSpan(span: ScheduleSpan): string {
  const same = span.start.month === span.end.month && span.start.day === span.end.day
  return same ? formatDate(span.start) : `${formatDate(span.start)} – ${formatDate(span.end)}`
}

/**
 * Maximal runs of member slots in `order`, treated as a circle: a run touching both ends is one run (it
 * crosses New Year) and is listed last, where its start falls.
 */
function runs(order: number[], member: (slot: number) => boolean): ScheduleSpan[] {
  const found: Array<[number, number]> = []
  let runStart: number | null = null
  order.forEach((slot, index) => {
    if (member(slot)) {
      if (runStart === null) runStart = index
    } else if (runStart !== null) {
      found.push([runStart, index - 1])
      runStart = null
    }
  })
  if (runStart !== null) found.push([runStart, order.length - 1])
  const wrapsAround = found.length > 1 && found[0][0] === 0 && found[found.length - 1][1] === order.length - 1
  const merged = wrapsAround
    ? [...found.slice(1, -1), [found[found.length - 1][0], found[0][1]] as [number, number]]
    : found
  return merged.map(([first, last]) => ({ start: dateOf(order[first]), end: dateOf(order[last]) }))
}

const ALL_SLOTS = Array.from({ length: SLOTS }, (_, slot) => slot)
/** The gap check walks every slot but Feb 29, so that date alone is never reported (decision 2). */
const GAP_ORDER = ALL_SLOTS.filter((slot) => slot !== FEB_29_SLOT)

function label(index: number, name: string): string {
  return name === '' ? `Row ${index + 1}` : `Row ${index + 1} '${name}'`
}

function joinRows(rows: number[]): string {
  return `${rows.slice(0, -1).join(', ')} and ${rows[rows.length - 1]}`
}

function rowProblems(row: ScheduleRow, index: number): ScheduleProblem[] {
  const name = row.name.trim()
  const theme = row.theme.trim()
  const at = label(index, name)
  const one = (code: ScheduleProblem['code'], message: string): ScheduleProblem => ({
    code,
    message,
    rows: [index + 1],
    names: [name],
  })
  const problems: ScheduleProblem[] = []
  if (name === '') problems.push(one('MISSING_NAME', `${at} has no name`))
  if (theme === '') problems.push(one('MISSING_THEME', `${at} has no theme`))
  else if (theme.toUpperCase() === 'AUTO') problems.push(one('AUTO_THEME', `${at} can't use Auto as its theme`))
  for (const [which, date] of [
    ['start', row.start],
    ['end', row.end],
  ] as const) {
    if (!isValidDate(date)) problems.push(one('INVALID_DATE', `${at} has an invalid ${which} date (${formatDate(date)})`))
  }
  return problems
}

function duplicates(rows: readonly ScheduleRow[]): ScheduleProblem[] {
  const groups = new Map<string, Array<{ index: number; name: string }>>()
  rows.forEach((row, index) => {
    const name = row.name.trim()
    if (name === '') return
    const key = name.toLowerCase()
    groups.set(key, [...(groups.get(key) ?? []), { index, name }])
  })
  return [...groups.values()]
    .filter((group) => group.length > 1)
    .map((group) => {
      const numbers = group.map((entry) => entry.index + 1)
      const verb = numbers.length === 2 ? 'both' : 'all'
      return {
        code: 'DUPLICATE_NAME' as const,
        message: `Rows ${joinRows(numbers)} are ${verb} named '${group[0].name}'`,
        rows: numbers,
        names: group.map((entry) => entry.name),
      }
    })
}

function overlaps(rows: readonly ScheduleRow[]): ScheduleProblem[] {
  const dated = rows
    .map((row, index) => ({ row, index }))
    .filter(({ row }) => isValidDate(row.start) && isValidDate(row.end))
    .map((entry) => ({ ...entry, covered: coverage(entry.row) }))
  const problems: ScheduleProblem[] = []
  dated.forEach((a, position) => {
    for (const b of dated.slice(position + 1)) {
      const spans = runs(ALL_SLOTS, (slot) => a.covered[slot] && b.covered[slot])
      if (spans.length === 0) continue
      const aName = a.row.name.trim()
      const bName = b.row.name.trim()
      const bLabel = label(b.index, bName)
      problems.push({
        code: 'OVERLAP',
        message:
          `${label(a.index, aName)} (${formatSpan(a.row)}) overlaps ` +
          `${bLabel.charAt(0).toLowerCase()}${bLabel.slice(1)} (${formatSpan(b.row)}) ` +
          `on ${spans.map(formatSpan).join(', ')}`,
        rows: [a.index + 1, b.index + 1],
        names: [aName, bName],
      })
    }
  })
  return problems
}

/**
 * Check a schedule the way the server will on save: per-row errors (missing name or theme, AUTO as a
 * theme, an invalid date), then each set of duplicate names (case-insensitive, trimmed), then every
 * overlapping pair. `gaps` lists the uncovered dates, ignoring Feb 29 on its own.
 */
export function checkSchedule(rows: readonly ScheduleRow[]): ScheduleCheck {
  const problems = [...rows.flatMap(rowProblems), ...duplicates(rows), ...overlaps(rows)]
  const covered = Array.from({ length: SLOTS }, () => false)
  for (const row of rows) {
    if (!isValidDate(row.start) || !isValidDate(row.end)) continue
    coverage(row).forEach((hit, slot) => {
      if (hit) covered[slot] = true
    })
  }
  return { problems, gaps: runs(GAP_ORDER, (slot) => !covered[slot]) }
}

/** The rows ordered by start date (a stable sort, so rows starting the same day keep their order). */
export function sortByStart<T extends ScheduleRow>(rows: readonly T[]): T[] {
  return [...rows].sort((a, b) => a.start.month * 100 + a.start.day - (b.start.month * 100 + b.start.day))
}
