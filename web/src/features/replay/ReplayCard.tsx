import type { KeyboardEvent } from "react";
import {
  ChevronLeft,
  ChevronRight,
  ChevronsLeft,
  ChevronsRight,
} from "lucide-react";
import type { ReplayDocument, ReplayScore } from "@/api/generated/model";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { concessionMark } from "@/lib/concession";
import { cn } from "@/lib/utils";
import { formatElapsed } from "@/features/livematch/useMatchClock";
import { hasReplayFeature, REPLAY_START } from "./replayFeatures";
import { formatSet, type Side } from "./replayCharts";
import { stepText } from "./replayStepText";
import { ReplayTimeline } from "./ReplayTimeline";

export type ReplayCardProps = {
  replay: ReplayDocument;
  /** An index into `replay.points`, or `REPLAY_START` for the 0-0 opening position. */
  selected: number;
  onSelect: (index: number) => void;
  team1Name: string;
  team2Name: string;
};

/** The opening position, before any step: nothing banked, nothing played. */
const START_SCORE: ReplayScore = {
  sets: [],
  gamesTeam1: 0,
  gamesTeam2: 0,
  pointsTeam1: "0",
  pointsTeam2: "0",
};

/** One side's line of the scoreboard: name, banked sets, the set in progress, and the point score. */
function SideRow({
  name,
  side,
  score,
  showGames,
  showPoints,
  serving,
  isWinner,
  conceded,
}: {
  name: string;
  side: Side;
  score: ReplayScore;
  showGames: boolean;
  showPoints: boolean;
  serving: boolean;
  isWinner: boolean;
  conceded?: string;
}) {
  const team1 = side === "TEAM1";
  return (
    <div
      className="flex items-baseline justify-between gap-4"
      data-testid={`replay-side-${side}`}
    >
      <span className={cn("min-w-0", isWinner && "font-semibold")}>
        {name}
        {conceded ? (
          <span className="ml-1 font-normal text-muted-foreground">{conceded}</span>
        ) : null}
        {serving ? (
          <span className="ml-1" title="To serve">
            <span aria-hidden="true">🎾</span>
            <span className="sr-only"> to serve</span>
          </span>
        ) : null}
      </span>
      <span className="flex items-baseline gap-3 tabular-nums">
        {score.sets.map((set, i) => (
          <span key={i} className="text-muted-foreground">
            {team1 ? set.team1 : set.team2}
            {set.tiebreakTeam1 != null && set.tiebreakTeam2 != null ? (
              <sup className="ml-0.5 text-[0.65rem]">
                {team1 ? set.tiebreakTeam1 : set.tiebreakTeam2}
              </sup>
            ) : null}
          </span>
        ))}
        {showGames ? (
          <span>{team1 ? score.gamesTeam1 : score.gamesTeam2}</span>
        ) : null}
        {showPoints ? (
          <span className="w-10 text-right text-2xl font-bold">
            {team1 ? score.pointsTeam1 : score.pointsTeam2}
          </span>
        ) : null}
      </span>
    </div>
  );
}

/**
 * A finished live-scored match, replayed point by point (#1146) — the card that stands where the live
 * scoreboard stood once the match is over.
 *
 * **Reads only `points` and `result`.** Each step carries the score after it and flags saying what it
 * was (a game, a break, a set, the ending), all derived on the server from the same engine the umpire
 * scored with (#1145). The web holds no tennis logic, so the replay cannot disagree with the match.
 *
 * **Selection is owned by the caller** (`MatchReplaySection`), so the charts (#1147) can share it.
 *
 * Accessible by construction: the score at the selected step is announced as text, every control is a
 * real button, arrow keys step, and the timeline marks the selected step with `aria-current` — but
 * Previous / Next always works without it.
 */
export function ReplayCard({
  replay,
  selected,
  onSelect,
  team1Name,
  team2Name,
}: ReplayCardProps) {
  const points = replay.points;
  const last = points.length - 1;
  const current = Math.min(Math.max(selected, REPLAY_START), last);
  const point = current === REPLAY_START ? undefined : points[current];
  const score = point?.score ?? START_SCORE;

  const name = (side: Side) => (side === "TEAM1" ? team1Name : team2Name);
  const showServe = hasReplayFeature(replay, "serve");
  const showBreaks = hasReplayFeature(replay, "breaks");
  const showBreakPoints = hasReplayFeature(replay, "breakPoints");
  const showTiming = hasReplayFeature(replay, "timing");

  const ended = point?.kind === "END";
  const atFinal = current === last && last >= 0;
  // Who serves next is simply the next step's recorded server — nothing is worked out here.
  const next = points[current + 1];
  const nextServer =
    showServe && next && next.kind !== "END" ? next.server : undefined;
  const winner = atFinal ? replay.result.winner : undefined;
  const concededLabel = ended ? concessionMark(point?.outcome) : undefined;
  const concededSide: Side | undefined =
    concededLabel && point ? (point.wonBy === "TEAM1" ? "TEAM2" : "TEAM1") : undefined;
  const showGames = !(
    ended &&
    score.gamesTeam1 === 0 &&
    score.gamesTeam2 === 0
  );

  const step = point ? stepText(point, name, showBreaks) : undefined;
  const position =
    point === undefined
      ? "Start of the match"
      : `Point ${current + 1} of ${points.length}, set ${point.setNumber}`;
  const banked = score.sets.map(formatSet).join(", ");
  const announcement = [
    `${position}${step ? `: ${step.long}` : ""}.`,
    banked ? `Sets ${banked}.` : "",
    showGames ? `Games ${score.gamesTeam1}-${score.gamesTeam2}.` : "",
    ended
      ? ""
      : `Points ${team1Name} ${score.pointsTeam1}, ${team2Name} ${score.pointsTeam2}.`,
    nextServer ? `${name(nextServer)} to serve.` : "",
  ]
    .filter(Boolean)
    .join(" ");

  function go(index: number) {
    const target = Math.min(Math.max(index, REPLAY_START), last);
    if (target !== current) onSelect(target);
  }

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    const moves: Record<string, number> = {
      ArrowLeft: current - 1,
      ArrowRight: current + 1,
      Home: REPLAY_START,
      End: last,
    };
    if (!(event.key in moves)) return;
    event.preventDefault();
    go(moves[event.key]);
  }

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center justify-between gap-3">
          <CardTitle>Replay</CardTitle>
          {atFinal ? <Badge variant="secondary">Final</Badge> : null}
        </div>
        <CardDescription>
          Step through the match point by point, as it was scored live.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {/* Focusable so the arrow keys work before any button has been tabbed to. */}
        <div
          role="group"
          aria-label="Replay controls"
          tabIndex={0}
          onKeyDown={onKeyDown}
          className="space-y-3 rounded-md focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          <p className="text-sm text-muted-foreground">
            {position}
            {step ? ` · ${step.short}` : ""}
            {showTiming && point ? (
              <span title="Time since scoring began">
                {" "}
                · {formatElapsed(point.atMs / 1000)}
              </span>
            ) : null}
          </p>
          <SideRow
            name={team1Name}
            side="TEAM1"
            score={score}
            showGames={showGames}
            showPoints={!ended}
            serving={nextServer === "TEAM1"}
            isWinner={winner === "TEAM1"}
            conceded={concededSide === "TEAM1" ? concededLabel : undefined}
          />
          <SideRow
            name={team2Name}
            side="TEAM2"
            score={score}
            showGames={showGames}
            showPoints={!ended}
            serving={nextServer === "TEAM2"}
            isWinner={winner === "TEAM2"}
            conceded={concededSide === "TEAM2" ? concededLabel : undefined}
          />
          {/* The score as words, for a screen reader: the board above is only a picture of it. */}
          <p aria-live="polite" className="sr-only" data-testid="replay-announcement">
            {announcement}
          </p>
          <div className="flex flex-wrap gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => go(REPLAY_START)}
              disabled={current === REPLAY_START}
            >
              <ChevronsLeft aria-hidden="true" />
              First
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={() => go(current - 1)}
              disabled={current === REPLAY_START}
            >
              <ChevronLeft aria-hidden="true" />
              Previous
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={() => go(current + 1)}
              disabled={current === last}
            >
              Next
              <ChevronRight aria-hidden="true" />
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={() => go(last)}
              disabled={current === last}
            >
              Last
              <ChevronsRight aria-hidden="true" />
            </Button>
          </div>
        </div>

        <ReplayTimeline
          replay={replay}
          selected={current}
          onSelect={go}
          name={name}
          showBreaks={showBreaks}
          showBreakPoints={showBreakPoints}
          showServe={showServe}
        />
      </CardContent>
    </Card>
  );
}
