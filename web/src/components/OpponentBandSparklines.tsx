import type { OpponentBandSeries } from '@/api/generated/model'
import { RELATION_HUES, RELATION_LABELS } from '@/components/opponentBands'

/** One bar per month, sized in the viewBox' own units — the SVG is scaled by CSS, not by these. */
const BAR_WIDTH = 6
const BAR_GAP = 2
const HEIGHT = 20

/**
 * Monthly play per opponent band, as three sparklines (#845).
 *
 * These are **small multiples**: the same tiny chart repeated once per band relation, which is only
 * readable as a comparison because all three share one y-scale. That scale is [PlayerResultsSummary.monthlyMax],
 * computed server-side — scaling each panel to its own maximum would draw a busy month against same-band
 * opponents and a single match against higher-band ones at exactly the same height, which is the one
 * thing the reader is here to compare.
 *
 * Each month is a single bar split by outcome in the donut's encoding — hue for the band relation, the
 * muted shade for losses — so the two charts teach one vocabulary rather than two.
 *
 * The drawings are `aria-hidden` and carry no interaction: a bar is a few pixels wide, so it is neither a
 * usable hit target nor a sensible focus stop. The band totals and win rates are already reachable as text
 * in the donut's legend; what a sparkline adds is shape over time, and shape is what a screen reader cannot
 * consume from an SVG anyway. The row labels stay real text.
 */
export function OpponentBandSparklines({
  series,
  monthsWindow,
  monthlyMax,
}: {
  series: OpponentBandSeries[]
  monthsWindow: number
  monthlyMax: number
}) {
  // Nothing decided in the window: every bar would be zero height, which reads as a rendering fault
  // rather than as "no play". Say it instead.
  if (monthlyMax === 0) {
    return (
      <p className="text-sm text-muted-foreground">
        No rated singles matches in the last {monthsWindow} months.
      </p>
    )
  }

  return (
    <div className="space-y-2">
      <p className="text-xs text-muted-foreground">Last {monthsWindow} months, oldest first</p>
      <ul className="space-y-1">
        {series.map((s) => (
          <li key={s.relation} className="flex items-center gap-3">
            <span className="w-24 shrink-0 text-xs" style={{ color: RELATION_HUES[s.relation].win }}>
              {RELATION_LABELS[s.relation]}
            </span>
            <Sparkline series={s} monthlyMax={monthlyMax} />
            <span className="shrink-0 text-xs tabular-nums text-muted-foreground">
              {s.totals.played}
            </span>
          </li>
        ))}
      </ul>
    </div>
  )
}

function Sparkline({ series, monthlyMax }: { series: OpponentBandSeries; monthlyMax: number }) {
  const width = series.monthly.length * (BAR_WIDTH + BAR_GAP)
  const hues = RELATION_HUES[series.relation]
  return (
    <svg
      viewBox={`0 0 ${width} ${HEIGHT}`}
      className="h-5 min-w-0 flex-1"
      preserveAspectRatio="none"
      aria-hidden="true"
    >
      {series.monthly.map((bucket, index) => {
        const x = index * (BAR_WIDTH + BAR_GAP)
        const winHeight = (bucket.wins / monthlyMax) * HEIGHT
        const lossHeight = (bucket.losses / monthlyMax) * HEIGHT
        return (
          <g key={bucket.period}>
            {/* Wins sit on the baseline so the win series is the one the eye tracks along the bottom. */}
            <rect x={x} y={HEIGHT - winHeight} width={BAR_WIDTH} height={winHeight} fill={hues.win} />
            <rect
              x={x}
              y={HEIGHT - winHeight - lossHeight}
              width={BAR_WIDTH}
              height={lossHeight}
              fill={hues.loss}
            />
          </g>
        )
      })}
    </svg>
  )
}
