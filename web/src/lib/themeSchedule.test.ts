import { describe, it, expect } from 'vitest'
import {
  DEFAULT_SCHEDULE,
  checkSchedule,
  findRowIndex,
  formatDate,
  formatSpan,
  isValidDate,
  nextChange,
  resolvePeriodStart,
  rowContains,
  sortByStart,
  type ScheduleRow,
} from './themeSchedule'

const row = (name: string, start: [number, number], end: [number, number], theme = 'CLAY'): ScheduleRow => ({
  name,
  theme,
  start: { month: start[0], day: start[1] },
  end: { month: end[0], day: end[1] },
})
const span = (start: [number, number], end: [number, number]) => ({
  start: { month: start[0], day: start[1] },
  end: { month: end[0], day: end[1] },
})

describe('checkSchedule — the same rules the server enforces (#1196)', () => {
  it('the default schedule has no problems and no gaps', () => {
    expect(checkSchedule(DEFAULT_SCHEDULE)).toEqual({ problems: [], gaps: [] })
  })

  it('an overlap names both rows, their ranges and the shared dates', () => {
    const { problems } = checkSchedule([row('Clay', [4, 16], [6, 10]), row('Grass', [6, 1], [7, 31])])
    expect(problems).toEqual([
      {
        code: 'OVERLAP',
        message: "Row 1 'Clay' (Apr 16 – Jun 10) overlaps row 2 'Grass' (Jun 1 – Jul 31) on Jun 1 – Jun 10",
        rows: [1, 2],
        names: ['Clay', 'Grass'],
      },
    ])
  })

  it('reports every overlapping pair', () => {
    const { problems } = checkSchedule([
      row('A', [1, 1], [1, 31]),
      row('B', [1, 15], [2, 15]),
      row('C', [1, 20], [1, 20]),
    ])
    expect(problems.map((p) => p.rows)).toEqual([
      [1, 2],
      [1, 3],
      [2, 3],
    ])
  })

  it('an overlap across New Year is one span, and an unnamed row is labelled by number', () => {
    const { problems } = checkSchedule([row('A', [12, 1], [1, 31]), row('', [12, 25], [1, 2])])
    expect(problems.map((p) => p.message)).toEqual([
      'Row 2 has no name',
      "Row 1 'A' (Dec 1 – Jan 31) overlaps row 2 (Dec 25 – Jan 2) on Dec 25 – Jan 2",
    ])
  })

  it('an overlap in two places lists both', () => {
    const { problems } = checkSchedule([row('Wrap', [11, 1], [2, 1]), row('Whole', [1, 15], [11, 15])])
    expect(problems[0].message).toContain('on Jan 15 – Feb 1, Nov 1 – Nov 15')
  })

  it('a range ending Feb 28 covers Feb 29, so a row starting Feb 29 overlaps it', () => {
    const { problems } = checkSchedule([row('Feb', [2, 1], [2, 28]), row('Leap', [2, 29], [3, 31])])
    expect(problems[0].message).toContain('on Feb 29')
  })

  it('duplicate names clash ignoring case and surrounding spaces', () => {
    const { problems } = checkSchedule([
      row('Clay', [1, 1], [1, 2]),
      row(' clay ', [2, 1], [2, 2]),
      row('CLAY', [3, 1], [3, 2]),
      row('Grass', [4, 1], [4, 2]),
      row('grass', [5, 1], [5, 2]),
    ])
    expect(problems.map((p) => p.message)).toEqual([
      "Rows 1, 2 and 3 are all named 'Clay'",
      "Rows 4 and 5 are both named 'Grass'",
    ])
    expect(problems[0].names).toEqual(['Clay', 'clay', 'CLAY'])
  })

  it('a missing theme, AUTO as a theme and impossible dates are errors', () => {
    const { problems } = checkSchedule([
      row('None', [1, 1], [1, 2], ''),
      row('Auto', [2, 1], [2, 2], 'auto'),
      row('April', [4, 1], [4, 31]),
      row('Thirteenth', [13, 1], [1, 2]),
    ])
    expect(problems.map((p) => [p.code, p.message])).toEqual([
      ['MISSING_THEME', "Row 1 'None' has no theme"],
      ['AUTO_THEME', "Row 2 'Auto' can't use Auto as its theme"],
      ['INVALID_DATE', "Row 3 'April' has an invalid end date (Apr 31)"],
      ['INVALID_DATE', "Row 4 'Thirteenth' has an invalid start date (month 13, day 1)"],
    ])
  })

  it('lists gaps in calendar order, keeping one across New Year whole', () => {
    const { gaps } = checkSchedule([row('A', [1, 10], [10, 16]), row('B', [10, 25], [12, 20])])
    expect(gaps).toEqual([span([10, 17], [10, 24]), span([12, 21], [1, 9])])
  })

  it('a range ending Feb 28 followed by Mar 1 leaves no gap', () => {
    expect(checkSchedule([row('A', [1, 1], [2, 28]), row('B', [3, 1], [12, 31])]).gaps).toEqual([])
  })

  it('skips Feb 29 in the gap check', () => {
    expect(checkSchedule([row('Most', [3, 1], [2, 27])]).gaps).toEqual([span([2, 28], [2, 28])])
  })

  it('an empty schedule is one whole-year gap, and a row with a bad date covers nothing', () => {
    expect(checkSchedule([]).gaps).toEqual([span([1, 1], [12, 31])])
    expect(checkSchedule([row('Bad', [2, 30], [12, 31])]).gaps).toEqual([span([1, 1], [12, 31])])
  })
})

describe('rowContains', () => {
  it('handles plain, wrapping and Feb-28-extended ranges', () => {
    expect(rowContains(row('A', [4, 16], [6, 10]), { month: 6, day: 10 })).toBe(true)
    expect(rowContains(row('A', [4, 16], [6, 10]), { month: 6, day: 11 })).toBe(false)
    expect(rowContains(row('W', [12, 20], [1, 5]), { month: 1, day: 3 })).toBe(true)
    expect(rowContains(row('W', [12, 20], [1, 5]), { month: 1, day: 6 })).toBe(false)
    expect(rowContains(row('F', [2, 1], [2, 28]), { month: 2, day: 29 })).toBe(true)
    expect(rowContains(row('Bad', [4, 1], [4, 31]), { month: 4, day: 2 })).toBe(false)
  })
})

describe('findRowIndex — first match wins, uncovered is -1', () => {
  it('returns the first row containing the date', () => {
    const schedule = [row('Wide', [1, 1], [12, 31]), row('Narrow', [5, 1], [5, 2])]
    expect(findRowIndex(schedule, new Date(2026, 4, 1))).toBe(0)
    expect(findRowIndex([row('Narrow', [5, 1], [5, 2])], new Date(2026, 4, 3))).toBe(-1)
  })
})

describe('resolvePeriodStart (#514 takeover boundary)', () => {
  const schedule = [row('Clay', [4, 16], [6, 10]), row('Holidays', [12, 20], [1, 5])]

  it('inside a row → that row’s start, this year', () => {
    expect(resolvePeriodStart(schedule, new Date(2026, 4, 5))).toEqual(new Date(2026, 3, 16))
  })

  it('in the January part of a range that wraps → its start last year', () => {
    expect(resolvePeriodStart(schedule, new Date(2027, 0, 3))).toEqual(new Date(2026, 11, 20))
    expect(resolvePeriodStart(schedule, new Date(2026, 11, 25))).toEqual(new Date(2026, 11, 20))
  })

  it('inside a gap → the day after the previous row ends', () => {
    expect(resolvePeriodStart(schedule, new Date(2026, 7, 1))).toEqual(new Date(2026, 5, 11))
    // The gap after Holidays starts Jan 6 and runs until Clay begins.
    expect(resolvePeriodStart(schedule, new Date(2026, 1, 1))).toEqual(new Date(2026, 0, 6))
  })

  it('with no rows at all → Jan 1', () => {
    expect(resolvePeriodStart([], new Date(2026, 7, 1))).toEqual(new Date(2026, 0, 1))
  })
})

describe('nextChange', () => {
  it('finds the next row, or the next gap', () => {
    const schedule = [row('A', [1, 1], [6, 30]), row('B', [7, 1], [9, 30])]
    expect(nextChange(schedule, new Date(2026, 5, 20))).toEqual({ date: new Date(2026, 6, 1), index: 1 })
    expect(nextChange(schedule, new Date(2026, 8, 30))).toEqual({ date: new Date(2026, 9, 1), index: -1 })
  })

  it('is null when nothing changes within a year', () => {
    expect(nextChange([row('All', [1, 1], [12, 31])], new Date(2026, 5, 20))).toBeNull()
    expect(nextChange([], new Date(2026, 5, 20))).toBeNull()
  })
})

describe('formatting, validity and sorting', () => {
  it('formats dates and spans', () => {
    expect(formatDate({ month: 4, day: 16 })).toBe('Apr 16')
    expect(formatDate({ month: 0, day: 3 })).toBe('month 0, day 3')
    expect(formatSpan(span([4, 16], [4, 16]))).toBe('Apr 16')
    expect(formatSpan(span([4, 16], [6, 10]))).toBe('Apr 16 – Jun 10')
  })

  it('accepts Feb 29 and rejects Feb 30 and fractional days', () => {
    expect(isValidDate({ month: 2, day: 29 })).toBe(true)
    expect(isValidDate({ month: 2, day: 30 })).toBe(false)
    expect(isValidDate({ month: 2, day: 1.5 })).toBe(false)
  })

  it('sorts by start date, keeping ties in order', () => {
    const sorted = sortByStart([row('C', [6, 1], [6, 2]), row('A', [1, 5], [1, 6]), row('B', [1, 5], [1, 7])])
    expect(sorted.map((r) => r.name)).toEqual(['A', 'B', 'C'])
  })
})
