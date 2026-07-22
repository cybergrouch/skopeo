import { describe, it, expect } from 'vitest'
import { resolveSeasonTheme, resolveActiveTheme, type ThemeName } from './season'

// Local-time constructor (month is 0-based here) so the resolver's getMonth/getDate read cleanly.
const on = (month: number, day: number) => new Date(2026, month - 1, day)

describe('resolveSeasonTheme', () => {
  const cases: Array<[string, Date, ThemeName]> = [
    ['Jan 1 → ao (start)', on(1, 1), 'ao'],
    ['Jan 31 → ao (end)', on(1, 31), 'ao'],
    ['Feb 1 → valentines (start)', on(2, 1), 'valentines'],
    ['Feb 14 → valentines (end boundary)', on(2, 14), 'valentines'],
    ['Feb 15 → spring (start boundary)', on(2, 15), 'spring'],
    ['Mar 31 → spring (end boundary)', on(3, 31), 'spring'],
    ['Apr 1 → clay (start boundary)', on(4, 1), 'clay'],
    ['May 20 → clay', on(5, 20), 'clay'],
    ['Jun 10 → clay (end boundary)', on(6, 10), 'clay'],
    ['Jun 11 → grass (start boundary)', on(6, 11), 'grass'],
    ['Jul 31 → grass (end boundary)', on(7, 31), 'grass'],
    ['Aug 1 → uso (start boundary)', on(8, 1), 'uso'],
    ['Sep 15 → uso (end boundary)', on(9, 15), 'uso'],
    ['Sep 16 → rainy (start boundary)', on(9, 16), 'rainy'],
    ['Oct 16 → rainy (end boundary)', on(10, 16), 'rainy'],
    ['Oct 17 → offseason (start boundary)', on(10, 17), 'offseason'],
    ['Oct 24 → offseason (end boundary)', on(10, 24), 'offseason'],
    ['Oct 25 → halloween (start boundary)', on(10, 25), 'halloween'],
    ['Nov 2 → halloween (end boundary)', on(11, 2), 'halloween'],
    ['Nov 3 → autumn (start boundary)', on(11, 3), 'autumn'],
    ['Dec 9 → autumn (end boundary)', on(12, 9), 'autumn'],
    ['Dec 10 → christmas (start boundary)', on(12, 10), 'christmas'],
    ['Dec 25 → christmas', on(12, 25), 'christmas'],
    ['Dec 31 → christmas (end boundary)', on(12, 31), 'christmas'],
  ]

  it.each(cases)('%s', (_label, date, expected) => {
    expect(resolveSeasonTheme(date)).toBe(expected)
  })
})

describe('resolveActiveTheme ENUM_TO_THEME mapping', () => {
  // A date that resolves to offseason, so AUTO/unknown are distinguishable from the pinned themes.
  const inOffseason = on(10, 20)
  const cases: Array<[string, ThemeName]> = [
    ['GRASS', 'grass'],
    ['CLAY', 'clay'],
    ['AO', 'ao'],
    ['US_OPEN', 'uso'],
    ['OFF_SEASON', 'offseason'],
    ['CHRISTMAS', 'christmas'],
    ['VALENTINES', 'valentines'],
    ['SPRING', 'spring'],
    ['RAINY', 'rainy'],
    ['HALLOWEEN', 'halloween'],
    ['AUTUMN', 'autumn'],
    ['SKOPEO_OG', 'og'],
  ]

  it.each(cases)('%s pins to its theme', (setting, expected) => {
    expect(resolveActiveTheme(setting, inOffseason)).toBe(expected)
  })

  it('AUTO resolves by date', () => {
    expect(resolveActiveTheme('AUTO', on(2, 10))).toBe('valentines')
  })

  it('unknown value falls back to date resolution', () => {
    expect(resolveActiveTheme('NEON', on(2, 10))).toBe('valentines')
  })
})
