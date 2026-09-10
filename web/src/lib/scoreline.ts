/** The shape every scoreline needs: a set's games. Structurally satisfied by every set DTO we have. */
export interface ScorelineSet {
  team1Games: number
  team2Games: number
}

/**
 * How an abnormal ending is written into a scoreline (#954).
 *
 * Same vocabulary as the spectator card (#951/#953), on purpose: three surfaces inventing three
 * spellings of "retired" is how a product ends up describing the same fact differently depending on
 * where you look.
 */
const ENDING_SUFFIX: Record<string, string> = {
  RETIRED: '(ret)',
  DEFAULTED: '(def)',
}

/** What to say when there is no scoreline, which is not always "nothing happened". */
const NO_SCORE_LABEL: Record<string, string> = {
  RETIRED: 'Retired',
  DEFAULTED: 'Walkover',
}

/**
 * A match's scoreline, or null when there genuinely is not one yet (#954).
 *
 * **Returning null rather than a fallback string is the point.** The caller decides what "no score"
 * reads as, because it depends on something this function should not guess — a *scheduled* match has
 * not been played, while a *completed* one has, and conflating them is the bug this replaces: a
 * retirement with no recordable set rendered as "Not yet played" on a finished match.
 *
 * There can be no sets on a finished match, legitimately. A player retiring before any decisive set
 * leaves nothing to record: since #917 the set winner is derived from the games, so a level set (0-0,
 * 3-3) has no winner to derive, and #925 established it contributes no dominance anyway. A walkover has
 * no sets at all. In those cases the ending itself is the scoreline — "Retired", "Walkover" — because
 * something did happen and the page should say so.
 *
 * @param sets the recorded sets, possibly empty
 * @param completionReason `COMPLETED` / `RETIRED` / `DEFAULTED`; absent is treated as an ordinary end
 * @param isFinished whether the match has a result at all. Distinguishes "nothing to show yet" from
 *  "nothing to show, and that is itself the story".
 */
export function scoreline(
  sets: ScorelineSet[] | undefined,
  completionReason?: string | null,
  isFinished = true,
): string | null {
  const played = (sets ?? []).map((s) => `${s.team1Games}-${s.team2Games}`).join(' ')
  const suffix = completionReason ? ENDING_SUFFIX[completionReason] : undefined

  if (played) return suffix ? `${played} ${suffix}` : played
  if (!isFinished) return null
  // Finished with nothing recorded: the ending IS the scoreline.
  return (completionReason ? NO_SCORE_LABEL[completionReason] : undefined) ?? null
}
