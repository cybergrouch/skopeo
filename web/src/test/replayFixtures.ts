import type {
  ReplayDocument,
  ReplayPoint,
  ReplayScore,
} from "@/api/generated/model";

/** A score, defaulting to 0-0 in the first set — tests spell out only what they care about. */
export function replayScore(partial: Partial<ReplayScore> = {}): ReplayScore {
  return {
    sets: [],
    gamesTeam1: 0,
    gamesTeam2: 0,
    pointsTeam1: "0",
    pointsTeam2: "0",
    ...partial,
  };
}

/** One step. `index` is set by [replayOf] from its position, as the server does. */
export function replayPoint(
  partial: Partial<ReplayPoint> & Pick<ReplayPoint, "wonBy">,
): ReplayPoint {
  return {
    index: 0,
    kind: "POINT",
    atMs: 0,
    setNumber: 1,
    tiebreak: false,
    breakPoint: false,
    gameWon: false,
    breakOfServe: false,
    setWon: false,
    score: replayScore(),
    ...partial,
  };
}

/** A document over [points], each re-indexed by position. */
export function replayOf(
  points: ReplayPoint[],
  result: ReplayDocument["result"] = { sets: [], winner: "TEAM1" },
): ReplayDocument {
  return {
    version: 1,
    result,
    events: [],
    points: points.map((point, index) => ({ ...point, index })),
  };
}

const SET1 = { team1: 6, team2: 0 };
const SET2 = { team1: 6, team2: 7, tiebreakTeam1: 5, tiebreakTeam2: 7 };

/**
 * A short, three-set match that exercises every kind of milestone: points, a hold, a break (reached
 * from a break point), an awarded set, a tiebreak won by the other side, and a retirement in set 3.
 * Team 1 (Ana) serves first; Team 2 (Bob) retires, so Ana wins.
 */
export const RETIRED_REPLAY: ReplayDocument = replayOf(
  [
    // Set 1, game 1: Ana holds.
    replayPoint({ wonBy: "TEAM1", server: "TEAM1", atMs: 5_000, score: replayScore({ pointsTeam1: "15" }) }),
    replayPoint({ wonBy: "TEAM2", server: "TEAM1", atMs: 35_000, score: replayScore({ pointsTeam1: "15", pointsTeam2: "15" }) }),
    replayPoint({ wonBy: "TEAM1", server: "TEAM1", atMs: 65_000, score: replayScore({ pointsTeam1: "30", pointsTeam2: "15" }) }),
    replayPoint({ wonBy: "TEAM1", server: "TEAM1", atMs: 95_000, score: replayScore({ pointsTeam1: "40", pointsTeam2: "15" }) }),
    replayPoint({ wonBy: "TEAM1", server: "TEAM1", atMs: 125_000, gameWon: true, score: replayScore({ gamesTeam1: 1 }) }),
    // Game 2: Ana breaks Bob, the last point a break point.
    replayPoint({ wonBy: "TEAM1", server: "TEAM2", atMs: 155_000, score: replayScore({ gamesTeam1: 1, pointsTeam1: "15" }) }),
    replayPoint({ wonBy: "TEAM1", server: "TEAM2", atMs: 185_000, score: replayScore({ gamesTeam1: 1, pointsTeam1: "30" }) }),
    replayPoint({ wonBy: "TEAM1", server: "TEAM2", atMs: 215_000, score: replayScore({ gamesTeam1: 1, pointsTeam1: "40" }) }),
    replayPoint({
      wonBy: "TEAM1",
      server: "TEAM2",
      atMs: 245_000,
      breakPoint: true,
      gameWon: true,
      breakOfServe: true,
      score: replayScore({ gamesTeam1: 2 }),
    }),
    // The umpire awards the rest of the set.
    replayPoint({
      kind: "SET",
      wonBy: "TEAM1",
      server: "TEAM1",
      atMs: 275_000,
      gameWon: true,
      setWon: true,
      score: replayScore({ sets: [SET1] }),
    }),
    // Set 2 goes to a tiebreak, which Bob wins.
    replayPoint({
      wonBy: "TEAM2",
      server: "TEAM1",
      atMs: 3_605_000,
      setNumber: 2,
      tiebreak: true,
      score: replayScore({ sets: [SET1], gamesTeam1: 6, gamesTeam2: 6, pointsTeam2: "1" }),
    }),
    replayPoint({
      wonBy: "TEAM2",
      server: "TEAM2",
      atMs: 3_635_000,
      setNumber: 2,
      tiebreak: true,
      gameWon: true,
      setWon: true,
      score: replayScore({ sets: [SET1, SET2] }),
    }),
    // Set 3: one point, then Bob retires.
    replayPoint({
      wonBy: "TEAM1",
      server: "TEAM1",
      atMs: 3_665_000,
      setNumber: 3,
      score: replayScore({ sets: [SET1, SET2], pointsTeam1: "15" }),
    }),
    replayPoint({
      kind: "END",
      wonBy: "TEAM1",
      server: "TEAM1",
      atMs: 3_700_000,
      setNumber: 3,
      outcome: "RETIRED",
      score: replayScore({ sets: [SET1, SET2], pointsTeam1: "15" }),
    }),
  ],
  { sets: [SET1, SET2], winner: "TEAM1" },
);

/**
 * The same match recorded before `server`, `breakOfServe` and `breakPoint` existed: an older replay
 * lacks those fields entirely, so the features that need them must not be offered.
 */
export const OLD_FORMAT_REPLAY: ReplayDocument = {
  ...RETIRED_REPLAY,
  points: RETIRED_REPLAY.points.map((point) => {
    const copy = { ...point };
    delete copy.server;
    delete copy.breakOfServe;
    delete copy.breakPoint;
    return copy;
  }),
};
