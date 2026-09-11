import { concessionMark } from "@/lib/concession";

/** The shape every scoreline needs: a set's games, and whether play stopped during it (#987). */
export interface ScorelineSet {
  team1Games: number;
  team2Games: number;
  /** True when a retirement or default ended play during this set (#972). */
  abandoned?: boolean;
}

/** What to say when there is no scoreline, which is not always "nothing happened". */
const NO_SCORE_LABEL: Record<string, string> = {
  RETIRED: "Retired",
  DEFAULTED: "Walkover",
};

/** Which side conceded. A player who concedes always loses, so it is whichever side did not win. */
export type ConcededSide = "TEAM1" | "TEAM2" | undefined;

/**
 * A match's scoreline, or null when there genuinely is not one yet (#954).
 *
 * **Returning null rather than a fallback string is the point.** The caller decides what "no score"
 * reads as, because it depends on something this function should not guess — a *scheduled* match has
 * not been played, while a *completed* one has, and conflating them is the bug this replaces: a
 * retirement with no recordable set rendered as "Not yet played" on a finished match.
 *
 * **Where the concession mark goes** (#987). It binds to the conceding side's number on the set play
 * stopped during — `0(R)-4`, not `0-4 (R)`. The trailing form put the mark beside the *winner's*
 * figure on a single-set line, which read as though they were the one who retired. Sets completed
 * before the concession render untouched: a set played to a finish is not qualified by what came after.
 *
 * The abandoned set comes from the record, not from assuming "the last one" — that assumption is wrong
 * when a player retires *between* sets, where the final recorded set was played to a conclusion.
 *
 * A finished match with no sets at all still falls back to a whole-match label ("Retired",
 * "Walkover"): there is no set to mark, and the ending is then the entire story.
 *
 * @param sets the recorded sets, possibly empty
 * @param completionReason `COMPLETED` / `RETIRED` / `DEFAULTED`; absent is treated as an ordinary end
 * @param isFinished whether the match has a result at all. Distinguishes "nothing to show yet" from
 *  "nothing to show, and that is itself the story".
 * @param concededSide which side gave up — the opposite of the match winner. Without it the mark has
 *  no side to attach to, so it is omitted rather than guessed onto one.
 */
export function scoreline(
  sets: ScorelineSet[] | undefined,
  completionReason?: string | null,
  isFinished = true,
  concededSide: ConcededSide = undefined,
): string | null {
  const mark = concessionMark(completionReason);
  const concession =
    mark && concededSide ? { mark, side: concededSide } : undefined;
  const played = (sets ?? [])
    .map((set) => renderSet(set, concession))
    .join(" ");

  if (played) return played;
  if (!isFinished) return null;
  // Finished with nothing recorded: the ending IS the scoreline.
  return (
    (completionReason ? NO_SCORE_LABEL[completionReason] : undefined) ?? null
  );
}

/** One set as `6-4`, or `0(R)-4` when it is the set a concession ended. */
function renderSet(
  set: ScorelineSet,
  concession: { mark: string; side: "TEAM1" | "TEAM2" } | undefined,
): string {
  const marked = concession && set.abandoned ? concession : undefined;
  const one = `${set.team1Games}${marked?.side === "TEAM1" ? marked.mark : ""}`;
  const two = `${set.team2Games}${marked?.side === "TEAM2" ? marked.mark : ""}`;
  return `${one}-${two}`;
}
