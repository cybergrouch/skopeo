import { useId } from 'react'
import type { ReplayDocument } from '@/api/generated/model'
import { cn } from '@/lib/utils'
import {
  MOMENTUM_WINDOW,
  momentumOf,
  sampleAtOrBefore,
  setBoundariesOf,
  signed,
  type MomentumSample,
  type Side,
} from './replayCharts'

/** viewBox units. The SVG scales with `w-full h-auto`, so these are proportions, not pixels. */
const WIDTH = 320
const GUTTER_LEFT = 30
const GUTTER_RIGHT = 6
const SHARE_TOP = 12
const SHARE_HEIGHT = 70
const GAP = 16
const DIFF_TOP = SHARE_TOP + SHARE_HEIGHT + GAP
const DIFF_HEIGHT = 44
const HEIGHT = DIFF_TOP + DIFF_HEIGHT + 6
const PLOT_WIDTH = WIDTH - GUTTER_LEFT - GUTTER_RIGHT

const SIDE_COLOUR: Record<Side, string> = {
  TEAM1: 'var(--chart-1)',
  TEAM2: 'var(--chart-2)',
}

interface ReplayChartProps {
  replay: ReplayDocument
  /** Position in `replay.points`, or `REPLAY_START` for the opening 0-0. */
  selected: number
  onSelect: (index: number) => void
  team1Name?: string
  team2Name?: string
}

/** The share as a whole-percent figure, for labels and the table. */
const percent = (share: number) => `${Math.round(share * 100)}%`

/**
 * Who was on top, point by point (#1147): team 1's share of the last {@link MOMENTUM_WINDOW} points across
 * the whole match, with set boundaries marked, and the running point difference in a panel beneath it as
 * context. Two panels on one shared x, never two y-scales on one plot.
 *
 * Plain on purpose — no weighting of break, set or tiebreak points (deliberately left out of #1147).
 *
 * The drawing is `aria-hidden`. Its text twin is the readout for the selected point and the per-point table,
 * whose rows are buttons that select the point.
 */
export function ReplayMomentumChart({ replay, selected, onSelect, team1Name = 'Team 1', team2Name = 'Team 2' }: ReplayChartProps) {
  const clipId = useId()
  const samples = momentumOf(replay)
  if (samples.length === 0) {
    return <p className="text-sm text-muted-foreground">No points were played in this match.</p>
  }
  const names: Record<Side, string> = { TEAM1: team1Name, TEAM2: team2Name }
  const total = samples.length
  const xOf = (ordinal: number) => GUTTER_LEFT + (ordinal / total) * PLOT_WIDTH
  const shareY = (share: number) => SHARE_TOP + (1 - share) * SHARE_HEIGHT
  const maxSwing = Math.max(1, ...samples.map((s) => Math.abs(s.difference)))
  const diffY = (difference: number) => DIFF_TOP + DIFF_HEIGHT / 2 - (difference / maxSwing) * (DIFF_HEIGHT / 2)
  const midShare = shareY(0.5)
  const midDiff = diffY(0)

  // Both lines start at the 0-0 position: an even share and no difference.
  const shareLine = `M ${xOf(0)} ${midShare} ` + samples.map((s) => `L ${xOf(s.ordinal)} ${shareY(s.team1Share)}`).join(' ')
  const shareArea = `${shareLine} L ${xOf(total)} ${midShare} Z`
  const diffLine = `M ${xOf(0)} ${midDiff} ` + samples.map((s) => `L ${xOf(s.ordinal)} ${diffY(s.difference)}`).join(' ')

  const boundaries = setBoundariesOf(samples)
  const current = sampleAtOrBefore(samples, selected)
  const markerX = xOf(current?.ordinal ?? 0)
  const slot = PLOT_WIDTH / total

  return (
    <div className="space-y-2">
      <ul className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground" aria-label="Legend">
        {(['TEAM1', 'TEAM2'] as const).map((side) => (
          <li key={side} className="flex items-center gap-1">
            <span aria-hidden="true" className="size-2.5 rounded-sm" style={{ background: SIDE_COLOUR[side] }} />
            {names[side]} on top
          </li>
        ))}
      </ul>
      <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} className="h-auto w-full" aria-hidden="true">
        <defs>
          <clipPath id={`${clipId}-above`}>
            <rect x={0} y={0} width={WIDTH} height={midShare} />
          </clipPath>
          <clipPath id={`${clipId}-below`}>
            <rect x={0} y={midShare} width={WIDTH} height={HEIGHT - midShare} />
          </clipPath>
        </defs>

        <text x={2} y={SHARE_TOP + 4} fontSize={7} fill="var(--muted-foreground)">
          100%
        </text>
        <text x={2} y={midShare + 2} fontSize={7} fill="var(--muted-foreground)">
          50%
        </text>
        <text x={2} y={SHARE_TOP + SHARE_HEIGHT} fontSize={7} fill="var(--muted-foreground)">
          0%
        </text>
        <text x={GUTTER_LEFT} y={SHARE_TOP - 4} fontSize={7} fill="var(--muted-foreground)">
          {names.TEAM1}'s share of the last {MOMENTUM_WINDOW} points
        </text>
        <line x1={GUTTER_LEFT} x2={WIDTH - GUTTER_RIGHT} y1={midShare} y2={midShare} stroke="var(--border)" strokeDasharray="2 2" />
        <path d={shareArea} fill={SIDE_COLOUR.TEAM1} opacity={0.35} clipPath={`url(#${clipId}-above)`} />
        <path d={shareArea} fill={SIDE_COLOUR.TEAM2} opacity={0.35} clipPath={`url(#${clipId}-below)`} />
        <path d={shareLine} fill="none" stroke="var(--foreground)" strokeWidth={1.25} data-testid="momentum-share" />

        <text x={GUTTER_LEFT} y={DIFF_TOP - 4} fontSize={7} fill="var(--muted-foreground)">
          Point difference ({names.TEAM1} minus {names.TEAM2})
        </text>
        <text x={2} y={midDiff + 2} fontSize={7} fill="var(--muted-foreground)">
          0
        </text>
        <line x1={GUTTER_LEFT} x2={WIDTH - GUTTER_RIGHT} y1={midDiff} y2={midDiff} stroke="var(--border)" />
        <path d={diffLine} fill="none" stroke="var(--muted-foreground)" strokeWidth={1.25} data-testid="momentum-difference" />

        {boundaries.map((b) => (
          <g key={b.setNumber} data-testid="momentum-set-boundary">
            <line
              x1={xOf(b.afterOrdinal)}
              x2={xOf(b.afterOrdinal)}
              y1={SHARE_TOP}
              y2={DIFF_TOP + DIFF_HEIGHT}
              stroke="var(--muted-foreground)"
              strokeDasharray="3 2"
            />
            <text x={xOf(b.afterOrdinal) + 2} y={SHARE_TOP + 8} fontSize={7} fill="var(--muted-foreground)">
              Set {b.setNumber}
            </text>
          </g>
        ))}

        <line
          data-testid="momentum-selected"
          x1={markerX}
          x2={markerX}
          y1={SHARE_TOP}
          y2={DIFF_TOP + DIFF_HEIGHT}
          stroke="var(--foreground)"
          strokeWidth={1.5}
        />

        {/* One full-height hit target per point, centred on it, so a tap anywhere in its column selects it. */}
        {samples.map((s) => (
          <rect
            key={s.index}
            data-testid="momentum-hit"
            x={xOf(s.ordinal) - slot / 2}
            y={SHARE_TOP}
            width={slot}
            height={DIFF_TOP + DIFF_HEIGHT - SHARE_TOP}
            fill="transparent"
            className="cursor-pointer"
            onClick={() => onSelect(s.index)}
          />
        ))}
      </svg>

      <p className="text-sm" aria-live="polite">
        {current ? readout(current, names) : 'Start of the match: no points played yet.'}
      </p>

      <details className="text-sm">
        <summary className="cursor-pointer text-muted-foreground">Momentum as a table</summary>
        <table className="mt-2 w-full text-left text-xs tabular-nums">
          <thead>
            <tr className="text-muted-foreground">
              <th scope="col">Point</th>
              <th scope="col">Set</th>
              <th scope="col">Won by</th>
              <th scope="col">
                {names.TEAM1}, last {MOMENTUM_WINDOW}
              </th>
              <th scope="col">Difference</th>
            </tr>
          </thead>
          <tbody>
            {samples.map((s) => (
              <tr key={s.index} aria-current={s.index === selected ? 'true' : undefined} className={cn(s.index === selected && 'font-semibold')}>
                <td>
                  <button
                    type="button"
                    onClick={() => onSelect(s.index)}
                    className="rounded-sm underline decoration-dotted underline-offset-2 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                  >
                    {s.ordinal}
                  </button>
                </td>
                <td>{s.setNumber}</td>
                <td>{names[s.wonBy]}</td>
                <td>
                  {s.team1InWindow} of {s.windowSize} ({percent(s.team1Share)})
                </td>
                <td>{signed(s.difference)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
    </div>
  )
}

function readout(sample: MomentumSample, names: Record<Side, string>): string {
  const lead =
    sample.difference === 0
      ? 'level on points overall'
      : `${names[sample.difference > 0 ? 'TEAM1' : 'TEAM2']} ahead by ${Math.abs(sample.difference)} overall`
  return (
    `After point ${sample.ordinal} (set ${sample.setNumber}): ${names.TEAM1} won ${sample.team1InWindow} of the last ` +
    `${sample.windowSize} (${percent(sample.team1Share)}), ${lead}.`
  )
}
