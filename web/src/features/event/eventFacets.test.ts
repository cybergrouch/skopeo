import { describe, it, expect } from 'vitest'
import { eventTypeLabel, participantMeta } from './eventFacets'

describe('eventTypeLabel', () => {
  it('maps a known event class to its label (#403)', () => {
    expect(eventTypeLabel('OPEN_PLAY')).toBe('Open play')
    expect(eventTypeLabel('TOURNAMENT')).toBe('Tournament')
  })

  it('falls back to the raw value for a class this client does not know', () => {
    // A server enum can outrun a deploy; showing the raw name beats showing nothing.
    expect(eventTypeLabel('LADDER')).toBe('LADDER')
  })

  it('is empty when the payload omits the class, so the header drops the separator', () => {
    expect(eventTypeLabel(undefined)).toBe('')
    expect(eventTypeLabel('')).toBe('')
  })
})

describe('participantMeta', () => {
  it('joins sex, age and the NTRP band with confidence', () => {
    expect(
      participantMeta({
        userId: 'u1',
        sex: 'Female',
        age: 34,
        rating: { value: '4.0', level: '4.0', confidence: '0.8' },
      }),
    ).toBe('Female · 34 · NTRP 4.0 · 80%')
  })

  it('omits whatever is missing', () => {
    expect(participantMeta({ userId: 'u1', age: 34 })).toBe('34')
  })

  // A non-manager viewer's payload carries none of these facets (#741), so the roster shows the
  // name alone — the gate is the absent data, not a conditional in the component.
  it('is empty for a participant with no facets at all', () => {
    expect(participantMeta({ userId: 'u1' })).toBe('')
  })
})
