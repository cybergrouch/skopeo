import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
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
  // Returns nodes rather than a string since #842 (the NTRP term is a disclaimer trigger), so assert on
  // what a user reads: render it and read the container's text.
  const rendered = (p: Parameters<typeof participantMeta>[0]) => {
    const { container } = render(<>{participantMeta(p)}</>)
    return container.textContent
  }

  it('joins sex, age and the NTRP band with confidence', () => {
    expect(
      rendered({
        userId: 'u1',
        sex: 'Female',
        age: 34,
        rating: { value: '4.0', level: '4.0', confidence: '0.8' },
      }),
    ).toBe('Female · 34 · NTRP 4.0 · 80%')
  })

  it('omits whatever is missing', () => {
    expect(rendered({ userId: 'u1', age: 34 })).toBe('34')
  })

  it('makes the NTRP term a disclaimer trigger (#842)', () => {
    render(<>{participantMeta({ userId: 'u1', rating: { value: '4.0', level: '4.0' } })}</>)
    expect(screen.getByRole('button', { name: /about this rating framework/i })).toBeInTheDocument()
  })

  // A non-manager viewer's payload carries none of these facets (#741), so the roster shows the
  // name alone — the gate is the absent data, not a conditional in the component. Null rather than an
  // empty string so callers can keep testing truthiness.
  it('is null for a participant with no facets at all', () => {
    expect(participantMeta({ userId: 'u1' })).toBeNull()
  })
})
