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

  it('marks the side that retired, tight to their number (#987)', () => {
    // The gap this closes twice over: a retirement at 1-3 first rendered as a plain "1-3", which
    // describes an abandoned match as a straightforward defeat; then as "1-3 (ret)", which put the
    // mark beside the WINNER's figure and read as though they were the one who quit.
    expect(
      scoreline([{ team1Games: 1, team2Games: 3, abandoned: true }], 'RETIRED', true, 'TEAM1'),
    ).toBe('1(R)-3')
  })

  it('marks the other side when they are the one who conceded (#987)', () => {
    expect(
      scoreline([{ team1Games: 4, team2Games: 0, abandoned: true }], 'RETIRED', true, 'TEAM2'),
    ).toBe('4-0(R)')
  })

  it('marks a default with its own letter (#987)', () => {
    expect(
      scoreline([{ team1Games: 6, team2Games: 2, abandoned: true }], 'DEFAULTED', true, 'TEAM2'),
    ).toBe('6-2(D)')
  })

  it('marks only the abandoned set, leaving completed ones alone (#987)', () => {
    // A set played to a finish is not qualified by what happened after it.
    expect(
      scoreline(
        [
          { team1Games: 6, team2Games: 4 },
          { team1Games: 1, team2Games: 3, abandoned: true },
        ],
        'RETIRED',
        true,
        'TEAM1',
      ),
    ).toBe('6-4 1(R)-3')
  })

  it('marks nothing when the concession ended BETWEEN sets (#987)', () => {
    // No set is flagged, so none is marked. "The last set is the abandoned one" would have marked a
    // set that was played to a conclusion — which is why the flag is carried rather than inferred.
    expect(
      scoreline([{ team1Games: 6, team2Games: 4 }], 'RETIRED', true, 'TEAM1'),
    ).toBe('6-4')
  })

  it('omits the mark when nobody is known to have conceded (#987)', () => {
    // Without a side, a mark could only be guessed onto one. No mark beats a wrong one.
    expect(scoreline([{ team1Games: 1, team2Games: 3, abandoned: true }], 'RETIRED')).toBe('1-3')
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
