import { describe, it, expect } from 'vitest'
import { scoreline } from './scoreline'

describe('scoreline', () => {
  it('joins the sets of an ordinary result', () => {
    expect(
      scoreline([
        { team1Games: 6, team2Games: 4 },
        { team1Games: 3, team2Games: 6 },
      ]),
    ).toBe('6-4 3-6')
  })

  it('marks a retirement, so it does not read as an ordinary loss', () => {
    // The gap this closes: a retirement at 1-3 rendered as a plain "1-3", which describes it as a
    // straightforward defeat rather than an abandoned match.
    expect(scoreline([{ team1Games: 1, team2Games: 3 }], 'RETIRED')).toBe('1-3 (ret)')
  })

  it('marks a default with its own suffix', () => {
    expect(scoreline([{ team1Games: 6, team2Games: 2 }], 'DEFAULTED')).toBe('6-2 (def)')
  })

  it('adds nothing for a match that was played out', () => {
    expect(scoreline([{ team1Games: 6, team2Games: 4 }], 'COMPLETED')).toBe('6-4')
  })

  it('returns null for a fixture that has not been played', () => {
    // Null rather than a string, so the CALLER decides the wording — "Not yet played" belongs to a
    // scheduled match and to nothing else.
    expect(scoreline([], undefined, false)).toBeNull()
    expect(scoreline(undefined, undefined, false)).toBeNull()
  })

  it('says what happened when a finished match has no sets at all (#954)', () => {
    // The reported bug: a player retiring before any decisive set leaves nothing recordable, and the
    // page said "Not yet played" about a match that was finished and had a winner.
    expect(scoreline([], 'RETIRED')).toBe('Retired')
    expect(scoreline([], 'DEFAULTED')).toBe('Walkover')
  })

  it('returns null for a finished match with no sets and no reason', () => {
    // Nothing truthful to say: it claims neither that the match was played nor that it was abandoned.
    expect(scoreline([], 'COMPLETED')).toBeNull()
    expect(scoreline([], undefined)).toBeNull()
  })

  it('ignores a completion reason it does not recognise', () => {
    // Forward-compatibility: a new reason on the server must not append "undefined" to every scoreline.
    expect(scoreline([{ team1Games: 6, team2Games: 4 }], 'SOMETHING_NEW')).toBe('6-4')
    expect(scoreline([], 'SOMETHING_NEW')).toBeNull()
  })
})
