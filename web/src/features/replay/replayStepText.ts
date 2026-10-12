import type { ReplayPoint } from "@/api/generated/model";
import { formatSet, type Side } from "./replayCharts";

/** How an ending reads when one side conceded. */
export const OUTCOME_TEXT: Record<string, string> = {
  RETIRED: "retired",
  DEFAULTED: "defaulted",
};

/**
 * How a step reads, short (the timeline button) and long (its accessible name and the announcement).
 *
 * Every word here is read off the step's own flags — `setWon`, `gameWon`, `breakOfServe`, `outcome` —
 * so nothing in the web decides what a point meant. The point label is the score the step produced.
 */
export function stepText(
  point: ReplayPoint,
  name: (side: Side) => string,
  showBreaks: boolean,
): { short: string; long: string } {
  const winner = name(point.wonBy);
  if (point.kind === "END") {
    const conceded = point.outcome ? OUTCOME_TEXT[point.outcome] : undefined;
    if (conceded) {
      const loser = name(point.wonBy === "TEAM1" ? "TEAM2" : "TEAM1");
      return {
        short: conceded === "retired" ? "Retired" : "Defaulted",
        long: `${loser} ${conceded}, ${winner} wins the match`,
      };
    }
    return { short: "Final", long: `${winner} wins the match` };
  }
  if (point.setWon) {
    // The set just won is the last one banked in the score after this step.
    const set = point.score.sets[point.score.sets.length - 1];
    return {
      short: point.tiebreak ? "Tiebreak" : "Set",
      long: `Set ${winner}, ${formatSet(set)}`,
    };
  }
  const games = `${point.score.gamesTeam1}-${point.score.gamesTeam2}`;
  if (point.gameWon) {
    if (showBreaks && point.breakOfServe === true) {
      return { short: "Break", long: `Break of serve, ${winner}, games ${games}` };
    }
    return { short: "Game", long: `Game ${winner}, games ${games}` };
  }
  const points = `${point.score.pointsTeam1}-${point.score.pointsTeam2}`;
  return {
    short: points,
    long: `${point.tiebreak ? "Tiebreak " : ""}${points}, point ${winner}`,
  };
}

/** Visual weight of a milestone: a game reads apart from a point, a break apart from a hold. */
export function milestoneClass(point: ReplayPoint, showBreaks: boolean): string {
  if (point.kind === "END") {
    return point.outcome && OUTCOME_TEXT[point.outcome]
      ? "border-destructive/60 text-destructive font-semibold"
      : "bg-primary text-primary-foreground font-semibold hover:bg-primary/90";
  }
  if (point.setWon)
    return "bg-primary text-primary-foreground font-semibold hover:bg-primary/90";
  if (point.gameWon && showBreaks && point.breakOfServe === true)
    return "border-link text-link font-semibold border-2";
  if (point.gameWon) return "bg-secondary text-secondary-foreground font-semibold";
  return point.tiebreak ? "italic" : "";
}
