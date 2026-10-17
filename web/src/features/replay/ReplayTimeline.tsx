import type { ReactNode } from "react";
import { ChevronDown, ChevronRight } from "lucide-react";
import type { ReplayResponse, ReplaySet } from "@/api/generated/model";
import { Popover, PopoverTrigger } from "@/components/ui/popover";
import { cn } from "@/lib/utils";
import { formatSet, type Side } from "./replayCharts";
import { hasReplayFeature, REPLAY_START } from "./replayFeatures";
import { scorerOf, type StepScorer } from "./replayScorers";
import { ScorerPopoverContent } from "./ScorerPopover";
import { locate, setGroupsOf, type GameRow, type SetGroup } from "./replayGames";
import { milestoneClass, stepText } from "./replayStepText";
import { useFollowedDisclosure } from "./useFollowedDisclosure";

export type ReplayTimelineProps = {
  replay: ReplayResponse;
  /** The clamped selection: an index into `replay.points`, or `REPLAY_START`. */
  selected: number;
  onSelect: (index: number) => void;
  name: (side: Side) => string;
  showBreaks: boolean;
  showBreakPoints: boolean;
  showServe: boolean;
};

/** "G3", "Tiebreak point 1", "Tiebreak points 2-3": what a row is, before what happened in it. */
function rowTitle(row: GameRow): string {
  if (row.kind === "tiebreak" && row.tiebreakPoints) {
    const [from, to] = row.tiebreakPoints;
    return from === to ? `Tiebreak point ${from}` : `Tiebreak points ${from}-${to}`;
  }
  return `G${row.gameNumber}`;
}

/** How a row finished, in words — read off the row's outcome, never worked out here. */
function outcomeText(row: GameRow, name: (side: Side) => string): string | null {
  if (row.kind === "tiebreak") return row.wonBy ? `Set ${name(row.wonBy)}` : null;
  switch (row.outcome) {
    case "hold":
      return "Hold";
    case "break":
      return "Break";
    case "won":
      return row.wonBy ? `Game ${name(row.wonBy)}` : null;
    case "awarded":
      return row.wonBy ? `Awarded to ${name(row.wonBy)}` : null;
    case "open":
      return "Unfinished";
  }
}

/**
 * A game row's one-line summary, e.g. "G3 · Bob serving · Hold, 40-15". It is also the row's toggle's
 * accessible name, so a collapsed game still says what happened in it.
 */
function rowSummary(row: GameRow, name: (side: Side) => string): string {
  const outcome = [outcomeText(row, name), row.lastScore].filter(Boolean).join(", ");
  return [rowTitle(row), row.server ? `${name(row.server)} serving` : null, outcome || null]
    .filter(Boolean)
    .join(" · ");
}

/**
 * The replay's timeline (#1146, regrouped in #1156): one collapsible section per set, one row per game.
 *
 * A phone has little width and plenty of height, so the timeline grows downward: each game is a row of its
 * own, and a tiebreak is split into serving pairs. Only the set and game holding the selected point open by
 * default, and stepping into another set opens it. The grouping is `setGroupsOf`'s, shared with the
 * vertical swimlane so the two agree on where every game starts.
 */
export function ReplayTimeline({
  replay,
  selected,
  onSelect,
  name,
  showBreaks,
  showBreakPoints,
  showServe,
}: ReplayTimelineProps) {
  const groups = setGroupsOf(replay, showServe, showBreaks);
  const location = locate(groups, selected);
  const sets = useFollowedDisclosure(
    location.setNumber === null ? null : String(location.setNumber),
    selected,
  );
  const games = useFollowedDisclosure(location.rowKey, selected);
  const firstSet = groups[0]?.setNumber;
  // Per-point scorers (#1183) arrive only for a viewer the server entitles to them.
  const showScorers = hasReplayFeature(replay, "scorers");

  const milestone = (index: number) => {
    const p = replay.points[index];
    const text = stepText(p, name, showBreaks);
    const breakPoint = showBreakPoints && p.breakPoint === true;
    return (
      <li key={index}>
        <Milestone
          label={text.short}
          description={`Point ${index + 1}: ${text.long}${breakPoint ? ", break point" : ""}`}
          selected={selected === index}
          onClick={() => onSelect(index)}
          className={milestoneClass(p, showBreaks)}
          breakPoint={breakPoint}
          scorer={showScorers ? scorerOf(replay, index) : null}
        />
      </li>
    );
  };

  return (
    <nav aria-label="Replay timeline" className="space-y-2">
      {groups.map((set) => {
        const key = String(set.setNumber);
        const open = sets.isOpen(key);
        return (
          <SetSection
            key={key}
            set={set}
            result={replay.result.sets[set.setNumber - 1]}
            open={open}
            onToggle={() => sets.toggle(key)}
          >
            <ol className="space-y-1.5">
              {set.setNumber === firstSet ? (
                <li>
                  <ol className="flex flex-wrap items-center gap-1">
                    <li>
                      <Milestone
                        label="0-0"
                        description="Start of the match, 0-0"
                        selected={selected === REPLAY_START}
                        onClick={() => onSelect(REPLAY_START)}
                      />
                    </li>
                  </ol>
                </li>
              ) : null}
              {set.rows.map((row) =>
                row.kind === "game" || row.kind === "tiebreak" ? (
                  <GameRowItem
                    key={row.key}
                    row={row}
                    summary={rowSummary(row, name)}
                    pinned={row.key === location.rowKey}
                    expanded={row.key === location.rowKey || games.isOpen(row.key)}
                    onToggle={() => games.toggle(row.key)}
                  >
                    {row.indices.map(milestone)}
                  </GameRowItem>
                ) : (
                  // An awarded set and the ending are single steps, not games: always shown, never folded.
                  <li key={row.key} data-testid={`replay-row-${row.key}`}>
                    <ol className="flex flex-wrap items-center gap-1">{row.indices.map(milestone)}</ol>
                  </li>
                ),
              )}
            </ol>
          </SetSection>
        );
      })}
    </nav>
  );
}

/** A set's collapsible section, headed by its number and — once banked — its score. */
function SetSection({
  set,
  result,
  open,
  onToggle,
  children,
}: {
  set: SetGroup;
  result: ReplaySet | undefined;
  open: boolean;
  onToggle: () => void;
  children: ReactNode;
}) {
  const headingId = `replay-set-${set.setNumber}`;
  const panelId = `replay-set-${set.setNumber}-games`;
  const Chevron = open ? ChevronDown : ChevronRight;
  return (
    <section aria-labelledby={headingId}>
      <h3 id={headingId} className="text-sm font-medium">
        <button
          type="button"
          aria-expanded={open}
          aria-controls={panelId}
          onClick={onToggle}
          className="flex w-full items-center gap-1 rounded py-1 text-left hover:bg-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          <Chevron aria-hidden="true" className="size-4 shrink-0" />
          Set {set.setNumber}
          {result ? (
            <>
              {" "}
              <span className="font-normal text-muted-foreground tabular-nums">
                {`· ${formatSet(result)}`}
              </span>
            </>
          ) : null}
        </button>
      </h3>
      <div id={panelId} hidden={!open} className="pl-5 pt-1">
        {open ? children : null}
      </div>
    </section>
  );
}

/**
 * One game, or one tiebreak serving pair. Collapsed, it is its one-line summary; expanded, its points too.
 * The game holding the selected point is pinned open, so its toggle is disabled rather than lying.
 */
function GameRowItem({
  row,
  summary,
  pinned,
  expanded,
  onToggle,
  children,
}: {
  row: GameRow;
  summary: string;
  pinned: boolean;
  expanded: boolean;
  onToggle: () => void;
  children: ReactNode;
}) {
  const panelId = `replay-game-${row.key}`;
  const Chevron = expanded ? ChevronDown : ChevronRight;
  return (
    <li
      data-testid={`replay-row-${row.key}`}
      data-outcome={row.outcome}
      className={cn(
        "border-l-2 pl-2",
        row.outcome === "break" ? "border-link" : "border-border",
      )}
    >
      <button
        type="button"
        aria-expanded={expanded}
        aria-controls={panelId}
        disabled={pinned}
        title={pinned ? "Holds the selected point" : undefined}
        onClick={onToggle}
        className={cn(
          "flex items-center gap-1 rounded text-left text-xs tabular-nums hover:bg-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:hover:bg-transparent",
          row.outcome === "break" ? "text-link font-medium" : "text-muted-foreground",
        )}
      >
        <Chevron aria-hidden="true" className="size-3.5 shrink-0" />
        {summary}
      </button>
      <ol id={panelId} hidden={!expanded} className="mt-1 flex flex-wrap items-center gap-1">
        {expanded ? children : null}
      </ol>
    </li>
  );
}

/** One step on the timeline: a real button, `aria-current` when it is the selected one. */
function Milestone({
  label,
  description,
  selected,
  onClick,
  className,
  breakPoint = false,
  scorer = null,
}: {
  label: string;
  description: string;
  selected: boolean;
  onClick: () => void;
  className?: string;
  breakPoint?: boolean;
  /** Who scored this step (#1183), when the viewer may see it: clicking then also opens a popover naming them. */
  scorer?: StepScorer | null;
}) {
  const badge = (
    <button
      type="button"
      aria-label={description}
      title={description}
      aria-current={selected ? "step" : undefined}
      onClick={() => onClick()}
      className={cn(
        "relative rounded border px-1.5 py-0.5 text-xs tabular-nums transition-colors hover:bg-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
        className,
        selected && "ring-2 ring-ring ring-offset-1",
      )}
    >
      {label}
      {breakPoint ? (
        <span
          aria-hidden="true"
          className="absolute -right-1 -top-1 size-1.5 rounded-full bg-destructive"
        />
      ) : null}
    </button>
  );
  if (!scorer) return badge;
  // Selecting still happens on click; the trigger only adds the popover on top (Radix composes the two).
  return (
    <Popover>
      <PopoverTrigger asChild>{badge}</PopoverTrigger>
      <ScorerPopoverContent scorer={scorer} title={description} />
    </Popover>
  );
}
