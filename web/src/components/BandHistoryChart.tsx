import type { RatingHistoryResponse } from '@/api/generated/model'

/** viewBox units. The SVG scales uniformly with `w-full h-auto`, so these are proportions, not pixels. */
const WIDTH = 320
const HEIGHT = 110
const GUTTER_LEFT = 26
const GUTTER_RIGHT = 6
const GUTTER_TOP = 8
const GUTTER_BOTTOM = 18
/** Half a band of head-room above and below, so a line never sits on the frame. */
const BAND_PADDING = 0.25

interface Sample {
  /** Days since epoch — a plain number, so the x-scale is arithmetic rather than date arithmetic. */
  day: number
  band: number
}

const MS_PER_DAY = 86_400_000

function dayOf(isoDate: string): number {
  return Math.floor(Date.parse(isoDate) / MS_PER_DAY)
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

/**
 * Axis endpoints as "Jun 2026". Month precision is all a multi-month span can usefully label, and it
 * keeps an axis tick textually distinct from the ISO dates the surrounding rows show — otherwise the
 * chart's own labels read as history entries. Hand-formatted rather than via `Intl`, so the output does
 * not depend on the runtime's locale.
 */
function monthLabel(day: number): string {
  const date = new Date(day * MS_PER_DAY)
  return `${MONTHS[date.getUTCMonth()]} ${date.getUTCFullYear()}`
}

/** NTRP bands arrive as strings ("3.5"); anything unparseable is dropped rather than plotted as NaN. */
function bandOf(level: string | null | undefined): number | null {
  if (level == null) return null
  const value = Number(level)
  return Number.isFinite(value) ? value : null
}

/**
 * Reduce the history to the band the player held over time.
 *
 * Entries arrive newest-first and, for an admin viewer, include every rated match — most of which did not
 * move the band. That is fine: a repeated band simply extends the current run, which is exactly what a step
 * chart draws. The leading sample is the *previous* band of the oldest entry, so the player's first jump is
 * visible instead of starting mid-air; an initial assessment has no previous band and so contributes none.
 */
function samplesOf(entries: RatingHistoryResponse[], today: string, carryToToday: boolean): Sample[] {
  const oldestFirst = [...entries].sort((a, b) => a.calculatedAt.localeCompare(b.calculatedAt))
  const samples: Sample[] = []
  // Callers only ever pass a non-empty group, so there is no empty-input branch to guard here.
  const first = oldestFirst[0]
  const firstPrevious = bandOf(first.previousLevel)
  if (firstPrevious != null) {
    samples.push({ day: dayOf(first.calculatedAt), band: firstPrevious })
  }
  for (const entry of oldestFirst) {
    const band = bandOf(entry.newLevel)
    if (band != null) {
      samples.push({ day: dayOf(entry.calculatedAt), band })
    }
  }
  // A band the player still holds is not history that stopped — carry the last run forward to today, which
  // is what turns a lone entry into the horizontal line it should be rather than a single invisible point.
  //
  // Only for the account that is still current, though (#853). A merged-away account's band stopped being
  // current at the merge, so carrying its run to today would claim it still holds that band.
  const last = samples[samples.length - 1]
  if (last && carryToToday) {
    const now = dayOf(today)
    if (now > last.day) {
      samples.push({ day: now, band: last.band })
    }
  }
  return samples
}

/** One account's series: its own samples, its identity, and the colour that identifies it. */
interface Series {
  key: string
  code: string | null
  fromMergedAccount: boolean
  samples: Sample[]
  colour: string
}

/**
 * Colour per series. The survivor takes `--chart-1` because it is the trajectory that leads to the
 * player's current rating; merged-away accounts take the rest. The tokens also encode band relation in
 * the opponent-band donut — a different card, so reuse is fine, but this chart's legend carries its own
 * meaning rather than relying on one learned elsewhere.
 */
const SERIES_COLOURS = ['var(--chart-1)', 'var(--chart-2)', 'var(--chart-3)']

/**
 * Split the history into one series per source account (#853).
 *
 * Grouping by account rather than by time is the whole point: the trajectories **overlap**. Duplicate
 * accounts arise precisely because someone plays under both at once — in production two merge pairs have
 * the retired account's range nested inside the survivor's — so a single line, however many breaks it has,
 * cannot express it. The survivor is ordered first so it keeps the primary colour; the rest follow oldest
 * first, so a given account's colour does not shuffle between renders.
 */
function seriesOf(entries: RatingHistoryResponse[], today: string): Series[] {
  const groups = new Map<string, RatingHistoryResponse[]>()
  for (const entry of entries) {
    const key = entry.sourcePublicCode ?? ''
    const bucket = groups.get(key)
    if (bucket) bucket.push(entry)
    else groups.set(key, [entry])
  }
  const built = [...groups.entries()].map(([key, rows]) => {
    const fromMergedAccount = rows.every((row) => row.fromMergedAccount === true)
    return {
      key,
      code: rows[0].sourcePublicCode ?? null,
      fromMergedAccount,
      samples: samplesOf(rows, today, !fromMergedAccount),
      colour: '',
    }
  })
  // Drop empty series first, so the sort key below can read samples[0] without a guard.
  const drawable = built.filter((series) => series.samples.length > 0)
  return [
    ...drawable.filter((series) => !series.fromMergedAccount),
    ...drawable
      .filter((series) => series.fromMergedAccount)
      .sort((a, b) => a.samples[0].day - b.samples[0].day),
  ].map((series, index) => ({ ...series, colour: SERIES_COLOURS[index % SERIES_COLOURS.length] }))
}

/**
 * A player's NTRP band over time, as a **step** chart (#845).
 *
 * Steps, not a sloped line: a band is a discrete state a player either holds or does not, and a diagonal
 * between 3.0 and 3.5 would draw weeks of "3.2" that never existed. Horizontal runs plus vertical jumps say
 * the true thing — *held this band until this date, then moved* — and make the duration of each band, the
 * quantity a reader actually wants, the length of a line.
 *
 * Hand-rolled rather than a charting library, matching the donut and sparklines (#845): the whole drawing is
 * one `<path>` of `H`/`V` segments over a linear scale, which is less code than configuring a library to
 * stop interpolating.
 *
 * `aria-hidden`, because the card it sits in already lists every entry as text with its dates and bands —
 * the chart restates that list as a shape, and a screen reader gets the list.
 */
export function BandHistoryChart({
  entries,
  today,
}: {
  entries: RatingHistoryResponse[]
  /** Injectable "now" (yyyy-MM-dd) so the trailing run has a deterministic end in tests. */
  today?: string
}) {
  const now = today ?? new Date().toISOString().slice(0, 10)
  const series = seriesOf(entries, now)
  if (series.length === 0) {
    return null
  }

  // Shared axes across every series. Per-series scaling would make two accounts' bands incomparable,
  // which defeats the reason they are drawn together.
  const allSamples = series.flatMap((one) => one.samples)
  const bands = allSamples.map((sample) => sample.band)
  const minBand = Math.min(...bands) - BAND_PADDING
  const maxBand = Math.max(...bands) + BAND_PADDING
  const days = allSamples.map((sample) => sample.day)
  const firstDay = Math.min(...days)
  const lastDay = Math.max(...days)
  const spanDays = lastDay - firstDay

  const plotWidth = WIDTH - GUTTER_LEFT - GUTTER_RIGHT
  const plotHeight = HEIGHT - GUTTER_TOP - GUTTER_BOTTOM
  const right = WIDTH - GUTTER_RIGHT
  const y = (band: number) =>
    GUTTER_TOP + plotHeight - ((band - minBand) / (maxBand - minBand)) * plotHeight
  // Only called when there IS a span. A zero span — everything on one day, e.g. a player rated for the
  // first time today — would divide by zero, so both zero-span cases are handled in SeriesPath instead:
  // a lone sample becomes a full-width flat line, several are spread evenly by position.
  const x = (day: number) => GUTTER_LEFT + ((day - firstDay) / spanDays) * plotWidth

  // One gridline per band actually held — discrete data deserves a discrete axis, and there are only ever
  // a handful, so labelling every one costs nothing and beats interpolated tick marks.
  const heldBands = [...new Set(bands)].sort((a, b) => a - b)
  const multipleAccounts = series.length > 1

  return (
    <div className="space-y-1">
      <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} className="h-auto w-full" aria-hidden="true">
        {heldBands.map((band) => (
          <g key={band}>
            <line
              x1={GUTTER_LEFT}
              x2={right}
              y1={y(band)}
              y2={y(band)}
              stroke="currentColor"
              strokeWidth={0.4}
              className="text-border"
            />
            <text
              x={GUTTER_LEFT - 4}
              y={y(band) + 2.5}
              textAnchor="end"
              fontSize={7}
              fill="currentColor"
              className="text-muted-foreground"
            >
              {band.toFixed(1)}
            </text>
          </g>
        ))}
        {series.map((one) => (
          <SeriesPath
            key={one.key}
            series={one}
            spanDays={spanDays}
            x={x}
            y={y}
            left={GUTTER_LEFT}
            right={right}
            plotWidth={plotWidth}
          />
        ))}
        <text
          x={GUTTER_LEFT}
          y={HEIGHT - 4}
          fontSize={7}
          fill="currentColor"
          className="text-muted-foreground"
        >
          {monthLabel(firstDay)}
        </text>
        <text
          x={right}
          y={HEIGHT - 4}
          textAnchor="end"
          fontSize={7}
          fill="currentColor"
          className="text-muted-foreground"
        >
          {monthLabel(lastDay)}
        </text>
      </svg>
      {multipleAccounts ? (
        /* Only when there is more than one account. A single-account player — almost everyone — sees
           exactly what they saw before (#853). */
        <ul className="flex flex-wrap gap-x-4 gap-y-1 text-xs">
          {series.map((one) => (
            <li key={one.key} className="flex items-center gap-1.5">
              <span
                aria-hidden="true"
                className="inline-block h-0.5 w-3 shrink-0"
                style={{ background: one.colour }}
              />
              <span className="font-medium">{one.code ?? 'this account'}</span>
              <span className="text-muted-foreground">
                {one.fromMergedAccount ? 'merged in' : 'current account'}
              </span>
            </li>
          ))}
        </ul>
      ) : null}
      <p className="text-xs text-muted-foreground">
        {multipleAccounts
          ? 'Separate lines: ratings from merged accounts are not continuous with each other.'
          : heldBands.length === 1
            ? 'One band held throughout.'
            : `${heldBands.length} bands held over this period.`}
      </p>
    </div>
  )
}

/**
 * One account's line.
 *
 * A series with a single sample is drawn as a **marker, not a line** — a merged-away account with one
 * rating (two exist in production) has a band at a date and no duration, and a zero-length path is simply
 * invisible. Widening it into a run would invent a span that was never recorded.
 */
function SeriesPath({
  series,
  spanDays,
  x,
  y,
  left,
  right,
  plotWidth,
}: {
  series: Series
  spanDays: number
  x: (day: number) => number
  y: (band: number) => number
  left: number
  right: number
  plotWidth: number
}) {
  const { samples, colour } = series
  if (samples.length === 1) {
    const only = samples[0]
    // With no time span at all, the sole sample is the whole chart: draw it as the full-width flat line
    // it is. Otherwise it is a moment inside a wider span, so it stays a point.
    return spanDays === 0 ? (
      <path
        d={`M ${left} ${y(only.band)} H ${right} V ${y(only.band)}`}
        fill="none"
        stroke={colour}
        strokeWidth={1.6}
        strokeLinejoin="round"
      />
    ) : (
      <circle cx={x(only.day)} cy={y(only.band)} r={1.8} fill={colour} />
    )
  }
  // Even spacing when several samples share one day: the ordering is still true, only the spacing is not.
  const xs =
    spanDays === 0
      ? samples.map((_, index) => left + (index / Math.max(samples.length - 1, 1)) * plotWidth)
      : samples.map((sample) => x(sample.day))
  const path = samples
    .map((sample, index) =>
      index === 0 ? `M ${xs[index]} ${y(sample.band)}` : `H ${xs[index]} V ${y(sample.band)}`,
    )
    .join(' ')
  return <path d={path} fill="none" stroke={colour} strokeWidth={1.6} strokeLinejoin="round" />
}
