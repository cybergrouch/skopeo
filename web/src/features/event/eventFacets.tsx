import type { ReactNode } from 'react'
import type { EventParticipantResponse } from '@/api/generated/model'
import { NtrpLabel } from '@/components/NtrpLabel'
import { formatConfidence } from '@/lib/confidence'

/** Human labels for the event's class (#403). */
export const EVENT_TYPE_LABELS: Record<string, string> = {
  OPEN_PLAY: 'Open play',
  FULL_MATCH: 'Full match',
  TOURNAMENT: 'Tournament',
}

/**
 * The event's class as a display label. Falls back to the raw value for a class this client doesn't
 * know yet (a server enum can outrun a deploy), and to an empty string when the payload omits it —
 * `type` is optional on the public DTO, so the header decides whether to render the separator.
 */
export function eventTypeLabel(type?: string): string {
  if (!type) return ''
  return EVENT_TYPE_LABELS[type] ?? type
}

/** Human labels for the event's organizing format (#720). */
export const EVENT_FORMAT_LABELS: Record<string, string> = {
  SINGLES: 'Singles',
  DOUBLES: 'Doubles',
  MIXED_DOUBLES: 'Mixed doubles',
}

/**
 * "Female · 34 · NTRP 4.0" — a participant's sex, age, and NTRP band, omitting whatever is missing.
 * Returns a node, not a string: the NTRP term carries the USTA disclaimer (#842).
 *
 * These facets are for match managers only (#741): the server withholds them from the public event
 * payload entirely, so for a non-manager viewer this returns an empty string and the roster renders
 * the name alone.
 */
export function participantMeta(p: EventParticipantResponse): ReactNode {
  const parts: ReactNode[] = []
  if (p.sex) parts.push(p.sex)
  if (p.age != null) parts.push(String(p.age))
  if (p.rating) {
    const pct = formatConfidence(p.rating.confidence)
    parts.push(
      <>
        <NtrpLabel value={p.rating.level ?? p.rating.value} />
        {pct ? ` · ${pct}` : ''}
      </>,
    )
  }
  // A node rather than a string since #842 made the NTRP term a disclaimer trigger. Returns null when
  // empty so callers can keep testing truthiness — an empty array would be truthy.
  if (parts.length === 0) return null
  return parts.map((part, i) => (
    <span key={i}>
      {i > 0 ? ' · ' : ''}
      {part}
    </span>
  ))
}
