import type { ReplayDocument, ReplayPoint } from '@/api/generated/model'

/**
 * Replay features and the field each one needs (#1145/#1146).
 *
 * A replay's format grows additively: an older replay may lack a field a newer feature needs, and then
 * that feature is simply not offered. This table is the ONE place that decides it — never compare
 * version numbers in components.
 */
export type ReplayFeature = 'serve' | 'breaks' | 'breakPoints' | 'timing'

const REQUIRED_FIELD: Record<ReplayFeature, keyof ReplayPoint> = {
  serve: 'server',
  breaks: 'breakOfServe',
  breakPoints: 'breakPoint',
  timing: 'atMs',
}

/** Whether [replay] carries the data [feature] needs. */
export function hasReplayFeature(replay: ReplayDocument, feature: ReplayFeature): boolean {
  const field = REQUIRED_FIELD[feature]
  return replay.points.some((point) => point[field] !== undefined && point[field] !== null)
}

/** The selection before the first point: the 0-0 starting position. */
export const REPLAY_START = -1
