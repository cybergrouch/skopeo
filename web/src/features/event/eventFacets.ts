import type { EventParticipantResponse } from '@/api/generated/model'
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
 *
 * These facets are for match managers only (#741): the server withholds them from the public event
 * payload entirely, so for a non-manager viewer this returns an empty string and the roster renders
 * the name alone.
 */
export function participantMeta(p: EventParticipantResponse): string {
  const parts: string[] = []
  if (p.sex) parts.push(p.sex)
  if (p.age != null) parts.push(String(p.age))
  if (p.rating) {
    const pct = formatConfidence(p.rating.confidence)
    parts.push(`NTRP ${p.rating.level ?? p.rating.value}${pct ? ` · ${pct}` : ''}`)
  }
  return parts.join(' · ')
}
