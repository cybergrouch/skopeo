import type { ReplayDocument } from '@/api/generated/model'
import { cn } from '@/lib/utils'
import { VerticalMomentum } from './ReplayMomentumVertical'
import {
  MOMENTUM_WINDOW,
  SIDE_COLOUR,
  momentumOf,
  sampleAtOrBefore,
  signed,
  type MomentumSample,
  type Side,
} from './replayCharts'

/** What the plot draws: the samples, and the one the selection lands on. */
export interface MomentumPlotProps {
  samples: MomentumSample[]
  names: Record<Side, string>
  current: MomentumSample | null
  onSelect: (index: number) => void
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
 * context. The two panels run top to bottom, side by side on one shared y, at every screen size (#1156): the
 * chart's height grows with the match and its width stays a phone's. Never two scales on one plot.
 *
 * Plain on purpose — no weighting of break, set or tiebreak points (deliberately left out of #1147).
 *
 * The drawing is `aria-hidden`. Its text twin is the readout for the selected point and the per-point table,
 * whose rows are buttons that select the point.
 */
export function ReplayMomentumChart({ replay, selected, onSelect, team1Name = 'Team 1', team2Name = 'Team 2' }: ReplayChartProps) {
  const samples = momentumOf(replay)
  if (samples.length === 0) {
    return <p className="text-sm text-muted-foreground">No points were played in this match.</p>
  }
  const names: Record<Side, string> = { TEAM1: team1Name, TEAM2: team2Name }
  const current = sampleAtOrBefore(samples, selected)
  const plot = { samples, names, current, onSelect }

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
      {/* Capped at a phone's width and centred, like the swimlanes: the plot scales with its width. */}
      <div className="mx-auto w-full max-w-md">
        <VerticalMomentum {...plot} />
      </div>

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
                  {s.team1InWindow} of {s.windowSize} ({percent(s.team1Share)}
                  {s.windowSize < MOMENTUM_WINDOW ? '*' : ''})
                </td>
                <td>{signed(s.difference)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <p className="mt-1 text-xs text-muted-foreground">
          * Fewer than {MOMENTUM_WINDOW} points played yet: the rest of the window counts as even, half to each side.
        </p>
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
    `After point ${sample.ordinal} (set ${sample.setNumber}): ${names.TEAM1} won ${sample.team1InWindow} of ${windowText(sample)} ` +
    `(${percent(sample.team1Share)}${evenNote(sample)}), ${lead}.`
  )
}

/** "the last 8", or, before the window has filled, "the 3 played so far". */
function windowText(sample: MomentumSample): string {
  if (sample.windowSize >= MOMENTUM_WINDOW) return `the last ${sample.windowSize}`
  return sample.windowSize === 1 ? 'the 1 played so far' : `the ${sample.windowSize} played so far`
}

/** Says why the plotted share is not the raw ratio while the window is still filling (#1156). */
function evenNote(sample: MomentumSample): string {
  return sample.windowSize < MOMENTUM_WINDOW ? `, counting the rest of the ${MOMENTUM_WINDOW} as even` : ''
}
