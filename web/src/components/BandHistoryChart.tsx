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
function samplesOf(entries: RatingHistoryResponse[], today: string): Sample[] {
  const oldestFirst = [...entries].sort((a, b) => a.calculatedAt.localeCompare(b.calculatedAt))
  const samples: Sample[] = []
  const first = oldestFirst[0]
  const firstPrevious = first ? bandOf(first.previousLevel) : null
  if (first && firstPrevious != null) {
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
  const last = samples[samples.length - 1]
  if (last) {
    const now = dayOf(today)
    if (now > last.day) {
      samples.push({ day: now, band: last.band })
    }
  }
  return samples
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
  const samples = samplesOf(entries, now)
  if (samples.length === 0) {
    return null
  }

  const bands = samples.map((s) => s.band)
  const minBand = Math.min(...bands) - BAND_PADDING
  const maxBand = Math.max(...bands) + BAND_PADDING
  const firstDay = samples[0].day
  const lastDay = samples[samples.length - 1].day
  const spanDays = lastDay - firstDay

  const plotWidth = WIDTH - GUTTER_LEFT - GUTTER_RIGHT
  const plotHeight = HEIGHT - GUTTER_TOP - GUTTER_BOTTOM
  const right = WIDTH - GUTTER_RIGHT
  const y = (band: number) =>
    GUTTER_TOP + plotHeight - ((band - minBand) / (maxBand - minBand)) * plotHeight

  // A run needs two endpoints to be drawn at all. When every sample lands on the same day — a player
  // rated for the first time today, or two changes in one sitting — there is no time span to place them
  // along, and scaling by date would collapse the whole chart onto one invisible point. Spread them
  // evenly across the width instead: the ordering is still true, only the spacing is not to scale.
  const xs =
    spanDays === 0
      ? samples.map((_, index) => GUTTER_LEFT + (index / Math.max(samples.length - 1, 1)) * plotWidth)
      : samples.map((sample) => GUTTER_LEFT + ((sample.day - firstDay) / spanDays) * plotWidth)

  const path =
    samples.length === 1
      ? // One band, one day: all that is known is the band held, so draw it as the full-width flat line
        // it is rather than a dot at the origin.
        `M ${GUTTER_LEFT} ${y(samples[0].band)} H ${right} V ${y(samples[0].band)}`
      : samples
          .map((sample, index) =>
            index === 0 ? `M ${xs[index]} ${y(sample.band)}` : `H ${xs[index]} V ${y(sample.band)}`,
          )
          .join(' ')

  // One gridline per band actually held — discrete data deserves a discrete axis, and there are only ever
  // a handful, so labelling every one costs nothing and beats interpolated tick marks.
  const heldBands = [...new Set(bands)].sort((a, b) => a - b)

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
        <path
          d={path}
          fill="none"
          stroke="var(--chart-1)"
          strokeWidth={1.6}
          strokeLinejoin="round"
        />
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
      <p className="text-xs text-muted-foreground">
        {heldBands.length === 1
          ? 'One band held throughout.'
          : `${heldBands.length} bands held over this period.`}
      </p>
    </div>
  )
}
