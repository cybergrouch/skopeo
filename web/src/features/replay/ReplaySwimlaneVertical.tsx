import { useState } from 'react'
import { ChevronDown, ChevronRight } from 'lucide-react'
import { Popover, PopoverAnchor } from '@/components/ui/popover'
import { SIDE_COLOUR, type LaneStep, type SetLanes, type Side } from './replayCharts'
import { locate, type SetGroup } from './replayGames'
import { ReplayMarkShape } from './ReplayMarkShape'
import type { StepScorer } from './replayScorers'
import { ScorerPopoverContent } from './ScorerPopover'
import { useFollowedDisclosure } from './useFollowedDisclosure'

/**
 * viewBox units. The SVG scales with `w-full h-auto` inside a phone-width cap, so it looks the same on every
 * device and grows only downward (#1156): one row per point, however long the set.
 */
const WIDTH = 300
const HEAD = 20
const ROW = 22
const RULE = 18
const LANE_X: Record<Side, number> = { TEAM1: 52, TEAM2: 202 }
const LABEL_GAP = 12

type Item =
  | { kind: 'start'; y: number }
  | { kind: 'step'; y: number; step: LaneStep }
  | { kind: 'rule'; y: number; text: string; dashed: boolean }

/**
 * A set laid out top to bottom: its 0-0 start, then each game's points one per row, each game closed by a rule
 * carrying the score after it. The grouping is `setGroupsOf`'s — the timeline's — so both agree where every
 * game ends; only the drawn steps (`setLanesOf`'s) take a row, so the ending takes none.
 */
function itemsOf(group: SetGroup, lane: SetLanes): { items: Item[]; height: number } {
  const byIndex = new Map(lane.steps.map((step) => [step.index, step]))
  const items: Item[] = [{ kind: 'start', y: HEAD + ROW / 2 }]
  let y = HEAD + ROW
  let inTiebreak = false
  for (const row of group.rows) {
    if (row.kind === 'tiebreak' && !inTiebreak) {
      inTiebreak = true
      items.push({ kind: 'rule', y: y + RULE / 2, text: 'Tiebreak', dashed: true })
      y += RULE
    }
    for (const index of row.indices) {
      const step = byIndex.get(index)
      if (!step) continue
      items.push({ kind: 'step', y: y + ROW / 2, step })
      y += ROW
    }
    if (row.scoreAfter) {
      items.push({ kind: 'rule', y: y + RULE / 2, text: row.scoreAfter, dashed: false })
      y += RULE
    }
  }
  return { items, height: y + 4 }
}

interface VerticalSwimlanesProps {
  lanes: SetLanes[]
  groups: SetGroup[]
  names: Record<Side, string>
  selected: number
  highlighted: number | null
  onSelect: (index: number) => void
  /**
   * Who scored a step (#1183), or null when the replay carries no scorers for this viewer. When set,
   * clicking a mark also opens a small popover naming them; otherwise marks behave as before.
   */
  scorerOf?: ((index: number) => StepScorer | null) | null
}

/**
 * The swimlanes, at every screen size (#1156): time runs top to bottom in two columns, one per side, and each set is a
 * collapsible block. Only the set holding the selected point is open by default, as in the timeline, and
 * stepping into another set opens it.
 */
export function VerticalSwimlanes({ lanes, groups, names, selected, highlighted, onSelect, scorerOf = null }: VerticalSwimlanesProps) {
  const focus = locate(groups, selected).setNumber
  const sets = useFollowedDisclosure(focus === null ? null : String(focus), selected)
  const groupBySet = new Map(groups.map((group) => [group.setNumber, group]))
  return (
    <div className="space-y-2" data-orientation="vertical">
      {lanes.map((lane) => {
        const group = groupBySet.get(lane.setNumber)
        if (!group) return null
        const key = String(lane.setNumber)
        const open = sets.isOpen(key)
        const panelId = `swimlane-set-${lane.setNumber}-points`
        const Chevron = open ? ChevronDown : ChevronRight
        return (
          <figure key={key} aria-label={`Set ${lane.setNumber}, ${lane.finalScore}`} data-testid={`swimlane-set-${lane.setNumber}`}>
            <figcaption>
              <button
                type="button"
                aria-expanded={open}
                aria-controls={panelId}
                onClick={() => sets.toggle(key)}
                className="flex w-full items-center gap-1 rounded py-1 text-left text-sm font-medium hover:bg-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                <Chevron aria-hidden="true" className="size-4 shrink-0" />
                Set {lane.setNumber} <span className="font-normal text-muted-foreground tabular-nums">· {lane.finalScore}</span>
              </button>
            </figcaption>
            <div id={panelId} hidden={!open}>
              {open ? (
                <SetColumns
                  group={group}
                  lane={lane}
                  names={names}
                  selected={selected}
                  highlighted={highlighted}
                  onSelect={onSelect}
                  scorerOf={scorerOf}
                />
              ) : null}
            </div>
          </figure>
        )
      })}
    </div>
  )
}

function SetColumns({
  group,
  lane,
  names,
  selected,
  highlighted,
  onSelect,
  scorerOf,
}: {
  group: SetGroup
  lane: SetLanes
  names: Record<Side, string>
  selected: number
  highlighted: number | null
  onSelect: (index: number) => void
  scorerOf: ((index: number) => StepScorer | null) | null
}) {
  const { items, height } = itemsOf(group, lane)
  // The mark whose scorer popover is open (#1183), with where to anchor it in viewBox units.
  const [popover, setPopover] = useState<{ index: number; x: number; y: number; label: string } | null>(null)
  const popoverScorer = popover && scorerOf ? scorerOf(popover.index) : null
  const startSelected = selected === lane.startIndex
  // The run of play: the 0-0 start, then every mark in the order it happened, so a streak reads as a line held
  // in one column and a swing as it crossing to the other.
  const path = items
    .flatMap((item) =>
      item.kind === 'start' ? [`${WIDTH / 2},${item.y}`] : item.kind === 'step' ? [`${LANE_X[item.step.side]},${item.y}`] : [],
    )
    .join(' ')
  const svg = (
    <svg viewBox={`0 0 ${WIDTH} ${height}`} className="block h-auto w-full" aria-hidden="true" data-testid="swimlane-vertical">
      {(['TEAM1', 'TEAM2'] as const).map((side) => (
        <text key={side} x={LANE_X[side]} y={13} textAnchor="middle" fontSize={10} fontWeight={600} fill={SIDE_COLOUR[side]}>
          {names[side]}
        </text>
      ))}
      <line x1={WIDTH / 2} x2={WIDTH / 2} y1={HEAD} y2={height} stroke="var(--border)" />
      {/* Drawn first, so every mark and label sits on top of it; the row highlights are translucent so it shows through. */}
      <polyline
        data-testid="swimlane-path"
        points={path}
        fill="none"
        stroke="var(--muted-foreground)"
        strokeOpacity={0.5}
        strokeWidth={1}
        strokeLinejoin="round"
      />
      {items.map((item) => {
        if (item.kind === 'start') {
          return (
            <g
              key="start"
              data-testid="swimlane-start"
              data-selected={startSelected || undefined}
              className="cursor-pointer"
              onClick={() => onSelect(lane.startIndex)}
            >
              <rect x={0} y={item.y - ROW / 2} width={WIDTH} height={ROW} fill={startSelected ? 'var(--muted)' : 'transparent'} fillOpacity={0.7} />
              <circle cx={WIDTH / 2} cy={item.y} r={3} fill="var(--muted-foreground)" stroke={startSelected ? 'var(--foreground)' : 'none'} strokeWidth={2} />
              <text x={WIDTH / 2 + LABEL_GAP} y={item.y + 3} fontSize={9} fill="var(--muted-foreground)">
                0-0
              </text>
            </g>
          )
        }
        if (item.kind === 'rule') {
          return (
            <g key={`rule-${item.y}`} data-testid="swimlane-game-rule">
              <line
                x1={8}
                x2={WIDTH - 8}
                y1={item.y}
                y2={item.y}
                stroke={item.dashed ? 'var(--muted-foreground)' : 'var(--border)'}
                strokeDasharray={item.dashed ? '3 2' : undefined}
              />
              {/* Painted over a stroke in the card's colour, so the rule reads as broken behind the score. */}
              <text
                x={WIDTH / 2}
                y={item.y + 3}
                textAnchor="middle"
                fontSize={9}
                fontWeight={600}
                fill="var(--foreground)"
                stroke="var(--card)"
                strokeWidth={4}
                paintOrder="stroke"
              >
                {item.text}
              </text>
            </g>
          )
        }
        const { step } = item
        const isHighlighted = step.index === highlighted
        const x = LANE_X[step.side]
        const big = step.mark !== 'point'
        return (
          <g
            key={step.index}
            data-testid="swimlane-step"
            data-index={step.index}
            data-mark={step.mark}
            data-side={step.side}
            data-selected={isHighlighted || undefined}
            className="cursor-pointer"
            onClick={() => {
              onSelect(step.index)
              if (scorerOf) setPopover({ index: step.index, x, y: item.y, label: step.label })
            }}
          >
            {/* A full-width hit target: a whole row is far easier to tap than a 3px dot. */}
            <rect x={0} y={item.y - ROW / 2} width={WIDTH} height={ROW} fill={isHighlighted ? 'var(--muted)' : 'transparent'} fillOpacity={0.7} />
            <ReplayMarkShape mark={step.mark} x={x} y={item.y} colour={SIDE_COLOUR[step.side]} highlighted={isHighlighted} />
            <text
              x={x + LABEL_GAP}
              y={item.y + 3}
              fontSize={9}
              fontWeight={big ? 600 : 400}
              fontStyle={step.tiebreak ? 'italic' : undefined}
              fill={big ? 'var(--foreground)' : 'var(--muted-foreground)'}
            >
              {step.label}
            </text>
          </g>
        )
      })}
    </svg>
  )
  if (!scorerOf) return svg
  // The SVG scales with its width, so the anchor is placed in percentages of the viewBox and lands on
  // the clicked mark whatever size the chart is drawn at.
  return (
    <Popover open={popoverScorer !== null} onOpenChange={(open) => {
        if (!open) setPopover(null)
      }}>
      <div className="relative">
        {svg}
        {popover ? (
          <PopoverAnchor asChild>
            <span
              aria-hidden="true"
              className="pointer-events-none absolute size-0"
              style={{ left: `${(popover.x / WIDTH) * 100}%`, top: `${(popover.y / height) * 100}%` }}
            />
          </PopoverAnchor>
        ) : null}
      </div>
      {popover && popoverScorer ? (
        <ScorerPopoverContent scorer={popoverScorer} title={`Point ${popover.index + 1}: ${popover.label}`} />
      ) : null}
    </Popover>
  )
}
