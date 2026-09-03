import { useId } from 'react'
import type { OpponentBandSeries, OpponentBandSeriesRelation } from '@/api/generated/model'
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '@/components/ui/popover'
import { RELATION_HUES, RELATION_LABELS } from '@/components/opponentBands'

const RADIUS = 40
const STROKE = 16
const CIRCUMFERENCE = 2 * Math.PI * RADIUS

interface Arc {
  relation: OpponentBandSeriesRelation
  outcome: 'win' | 'loss'
  /** The whole series behind this arc, so a click can show the band's detail without a second lookup. */
  series: OpponentBandSeries
  colour: string
  /** `stroke-dasharray`: one visible dash sized to this arc, then a gap covering the rest of the circle. */
  dash: string
  dashOffset: number
}

/**
 * Turn the series into one arc per non-empty segment, in draw order: each relation's win arc immediately
 * followed by its loss arc, so a relation's two shades always touch.
 *
 * The running offset is accumulated here rather than during render — a chart is a pure function of its
 * data, and React's compiler rightly rejects a counter mutated inside `map`.
 */
function arcsOf(series: OpponentBandSeries[], played: number): Arc[] {
  const counted = series.flatMap((s) => [
    { relation: s.relation, outcome: 'win' as const, count: s.totals.wins, series: s, colour: RELATION_HUES[s.relation].win },
    { relation: s.relation, outcome: 'loss' as const, count: s.totals.losses, series: s, colour: RELATION_HUES[s.relation].loss },
  ])
  const arcs: Arc[] = []
  let consumed = 0
  for (const segment of counted) {
    if (segment.count === 0) continue
    const length = (segment.count / played) * CIRCUMFERENCE
    arcs.push({
      relation: segment.relation,
      outcome: segment.outcome,
      series: segment.series,
      colour: segment.colour,
      dash: `${length} ${CIRCUMFERENCE - length}`,
      // Negative because the arcs are laid out clockwise from the rotated start point.
      dashOffset: -consumed,
    })
    consumed += length
  }
  return arcs
}

/**
 * The detail both the arcs and the legend rows reveal. One component, so the two entry points into the same
 * datum can never say different things.
 */
function BandDetail({ series, played }: { series: OpponentBandSeries; played: number }) {
  const label = RELATION_LABELS[series.relation]
  // `played` is > 0 here by construction: the chart renders its empty state instead of a legend when the
  // total is zero, so there is nothing to divide by zero. No guard, rather than an untestable one.
  const share = Math.round((series.totals.played / played) * 100)
  return (
    <>
      <p className="font-medium">{label}</p>
      <p className="text-muted-foreground">
        {series.totals.played} of your rated singles matches ({share}%), against opponents{' '}
        {series.relation === 'SAME' ? 'in your own band' : `in a ${series.relation.toLowerCase()} band`}.{' '}
        {series.totals.wins} won, {series.totals.losses} lost
        {series.totals.winRate == null ? '' : ` — a ${series.totals.winRate}% win rate`}.
      </p>
    </>
  )
}

/**
 * A player's rated **singles** record by opponent band, as a donut (#845).
 *
 * Hue identifies the band relation and lightness the outcome, so six segments read as three groups —
 * which only holds because each relation's win/loss pair is drawn adjacently (see {@link segmentsOf}).
 *
 * Hand-rolled rather than pulled from a charting library: each segment is one `<circle>` whose stroke is a
 * single dash sized to its share of the circumference, so there is no arc geometry to get wrong.
 *
 * Clicking an arc reveals that band's detail. The arcs are **not** the accessible route to it, though — an
 * SVG shape is a poor focus stop, win and loss differ only in lightness, which fails in greyscale and for
 * low-vision readers, and a reader who cannot tell two shades apart cannot tell which arc to click either.
 * So the drawing is `aria-hidden` and every arc has a twin in the legend: a real `<button>` carrying the
 * same counts as text and opening the same popover. Mouse users get the slice, everyone gets the row.
 */
export function OpponentBandChart({ series }: { series: OpponentBandSeries[] }) {
  const played = series.reduce((sum, s) => sum + s.totals.played, 0)
  if (played === 0) {
    return <p className="text-sm text-muted-foreground">No rated singles matches yet.</p>
  }

  const arcs = arcsOf(series, played)

  return (
    <div className="flex flex-wrap items-center gap-4">
      <svg viewBox="0 0 100 100" className="size-28 shrink-0 -rotate-90" aria-hidden="true">
        {arcs.map((arc) => (
          <Popover key={`${arc.relation}-${arc.outcome}`}>
            <PopoverTrigger asChild>
              <circle
                cx={50}
                cy={50}
                r={RADIUS}
                fill="none"
                stroke={arc.colour}
                strokeWidth={STROKE}
                strokeDasharray={arc.dash}
                strokeDashoffset={arc.dashOffset}
                className="cursor-pointer"
              />
            </PopoverTrigger>
            <PopoverContent>
              <BandDetail series={arc.series} played={played} />
            </PopoverContent>
          </Popover>
        ))}
      </svg>
      <ul className="min-w-0 flex-1 space-y-1 text-sm">
        {series.map((s) => (
          <LegendRow key={s.relation} series={s} played={played} />
        ))}
      </ul>
    </div>
  )
}

/**
 * One legend row: the relation, its counts, and its win rate — the accessible route to the same data the
 * donut encodes. The row is the click target that reveals the detail, so the information is reachable
 * without perceiving the shades and without hitting a thin arc on a phone.
 */
function LegendRow({ series, played }: { series: OpponentBandSeries; played: number }) {
  const descriptionId = useId()
  const label = RELATION_LABELS[series.relation]
  return (
    <li className="flex items-center gap-2">
      <span
        aria-hidden="true"
        className="size-3 shrink-0 rounded-sm"
        style={{ background: RELATION_HUES[series.relation].win }}
      />
      <Popover>
        <PopoverTrigger asChild>
          <button
            type="button"
            aria-describedby={descriptionId}
            className="min-w-0 flex-1 text-left underline decoration-dotted underline-offset-2 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring rounded-sm"
          >
            <span className="font-medium">{label}</span>{' '}
            <span className="tabular-nums text-muted-foreground">
              {series.totals.wins}W / {series.totals.losses}L
              {series.totals.winRate == null ? '' : ` · ${series.totals.winRate}%`}
            </span>
          </button>
        </PopoverTrigger>
        <PopoverContent id={descriptionId} role="tooltip">
          <BandDetail series={series} played={played} />
        </PopoverContent>
      </Popover>
    </li>
  )
}
