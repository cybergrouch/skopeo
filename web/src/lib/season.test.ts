import { describe, it, expect } from 'vitest'
import { resolveSeasonTheme, type ThemeName } from './season'

// Local-time constructor (month is 0-based here) so the resolver's getMonth/getDate read cleanly.
const on = (month: number, day: number) => new Date(2026, month - 1, day)

describe('resolveSeasonTheme', () => {
  const cases: Array<[string, Date, ThemeName]> = [
    ['January → ao (start)', on(1, 1), 'ao'],
    ['January → ao (end)', on(1, 31), 'ao'],
    ['Feb → offseason (swing fill)', on(2, 15), 'offseason'],
    ['Mar 31 → offseason (day before clay)', on(3, 31), 'offseason'],
    ['Apr 1 → clay (start boundary)', on(4, 1), 'clay'],
    ['May → clay', on(5, 20), 'clay'],
    ['Jun 10 → clay (end boundary)', on(6, 10), 'clay'],
    ['Jun 11 → grass (start boundary)', on(6, 11), 'grass'],
    ['Jul 31 → grass (end boundary)', on(7, 31), 'grass'],
    ['Aug 1 → uso (start boundary)', on(8, 1), 'uso'],
    ['Sep 15 → uso (end boundary)', on(9, 15), 'uso'],
    ['Sep 16 → offseason (day after uso)', on(9, 16), 'offseason'],
    ['Oct → offseason', on(10, 15), 'offseason'],
    ['Nov → offseason', on(11, 30), 'offseason'],
    ['Dec 9 → offseason (day before christmas)', on(12, 9), 'offseason'],
    ['Dec 10 → christmas (carve-out start)', on(12, 10), 'christmas'],
    ['Dec 25 → christmas', on(12, 25), 'christmas'],
    ['Dec 31 → christmas (end boundary)', on(12, 31), 'christmas'],
  ]

  it.each(cases)('%s', (_label, date, expected) => {
    expect(resolveSeasonTheme(date)).toBe(expected)
  })
})
