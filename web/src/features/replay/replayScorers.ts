import type { ReplayResponse } from '@/api/generated/model'

/** Who scored one step, as the replay's popover shows them (#1183). */
export type StepScorer = {
  /** The name to show, or a stand-in when there is none to show. */
  label: string
  isPlaceholder: boolean
  isDeleted: boolean
}

/**
 * Who scored step [index] of [replay], or null when the replay does not say — an older replay, or a
 * viewer the server did not send scorers to.
 *
 * Same display rules as a player: a deleted scorer reads "a deleted account" and is never named (the
 * server sends no name for one anyway), and a code that no longer resolves reads "Unknown scorer".
 */
export function scorerOf(replay: ReplayResponse, index: number): StepScorer | null {
  const code = replay.points[index]?.scorer
  if (!code) return null
  const scorer = replay.scorers?.[code]
  if (!scorer) return { label: 'Unknown scorer', isPlaceholder: false, isDeleted: false }
  if (scorer.isDeleted) return { label: 'a deleted account', isPlaceholder: false, isDeleted: true }
  return {
    label: scorer.displayName ?? 'Unknown scorer',
    isPlaceholder: scorer.isPlaceholder === true,
    isDeleted: false,
  }
}
