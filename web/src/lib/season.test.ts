import { describe, it, expect } from 'vitest'
import {
  resolveSeasonTheme,
  resolveActiveTheme,
  resolveSeasonStart,
  resolveEffectiveTheme,
  type ThemeName,
} from './season'

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

describe('resolveSeasonStart', () => {
  // Each date maps to the start boundary of its window (midnight, same year). Boundaries mirror the
  // window table used by resolveSeasonTheme — the single source of truth.
  const cases: Array<[string, Date, Date]> = [
    ['before Feb 1 → Jan 1 (ao window is Jan-1-anchored)', on(1, 20), on(1, 1)],
    ['Jan 1 → Jan 1', on(1, 1), on(1, 1)],
    ['Feb 1 → Feb 1', on(2, 1), on(2, 1)],
    ['Feb 10 → Feb 1', on(2, 10), on(2, 1)],
    ['Feb 15 → Feb 15', on(2, 15), on(2, 15)],
    ['Mar 20 → Feb 15', on(3, 20), on(2, 15)],
    ['Apr 1 → Apr 1', on(4, 1), on(4, 1)],
    ['May 5 → Apr 1', on(5, 5), on(4, 1)],
    ['Jun 11 → Jun 11', on(6, 11), on(6, 11)],
    ['Jul 4 → Jun 11', on(7, 4), on(6, 11)],
    ['Aug 1 → Aug 1', on(8, 1), on(8, 1)],
    ['Sep 16 → Sep 16', on(9, 16), on(9, 16)],
    ['Oct 17 → Oct 17', on(10, 17), on(10, 17)],
    ['Oct 25 → Oct 25', on(10, 25), on(10, 25)],
    ['Nov 3 → Nov 3', on(11, 3), on(11, 3)],
    ['Dec 10 → Dec 10', on(12, 10), on(12, 10)],
    ['Dec 31 → Dec 10', on(12, 31), on(12, 10)],
  ]

  it.each(cases)('%s', (_label, now, expected) => {
    expect(resolveSeasonStart(now).getTime()).toBe(expected.getTime())
  })

  it('anchors the start to the year of `now`', () => {
    expect(resolveSeasonStart(new Date(2027, 6, 4)).getFullYear()).toBe(2027)
  })
})

describe('resolveEffectiveTheme', () => {
  // Branch 1: no local theme → follow the global path exactly.
  describe('branch 1: no local theme follows global', () => {
    it('local null + AUTO global → seasonal', () => {
      expect(
        resolveEffectiveTheme({ global: 'AUTO', localTheme: null, localSetAt: null }, on(2, 10)),
      ).toBe('valentines')
    })

    it('local null + fixed global → the fixed theme', () => {
      expect(
        resolveEffectiveTheme({ global: 'GRASS', localTheme: null, localSetAt: null }, on(2, 10)),
      ).toBe('grass')
    })

    it('local undefined + undefined global → seasonal by date', () => {
      expect(
        resolveEffectiveTheme(
          { global: undefined, localTheme: undefined, localSetAt: undefined },
          on(4, 15),
        ),
      ).toBe('clay')
    })
  })

  // Branch 2: a fixed (non-AUTO) global never overrides a local choice.
  describe('branch 2: local wins over a fixed global', () => {
    it('local CLAY + fixed GRASS global → clay', () => {
      expect(
        resolveEffectiveTheme(
          { global: 'GRASS', localTheme: 'CLAY', localSetAt: on(1, 1).toISOString() },
          on(8, 1),
        ),
      ).toBe('clay')
    })
  })

  // Branch 3: global AUTO — a season that began after the user set local takes over.
  describe('branch 3: local vs global AUTO by season boundary', () => {
    it('season start BEFORE setAt → local wins', () => {
      // now is Aug 1 (uso window starts Aug 1); user set local on Aug 5 → season start (Aug 1) < setAt.
      expect(
        resolveEffectiveTheme(
          { global: 'AUTO', localTheme: 'CLAY', localSetAt: on(8, 5).toISOString() },
          on(8, 20),
        ),
      ).toBe('clay')
    })

    it('season start AFTER setAt → seasonal takeover', () => {
      // User set local in the grass window (Jun 15); now is Aug 20 (uso, starts Aug 1 > Jun 15).
      expect(
        resolveEffectiveTheme(
          { global: 'AUTO', localTheme: 'CLAY', localSetAt: on(6, 15).toISOString() },
          on(8, 20),
        ),
      ).toBe('uso')
    })

    it('re-setting local within the current season reclaims the choice', () => {
      // Same current season (uso, starts Aug 1); re-set on Aug 10 → season start (Aug 1) ≤ setAt.
      expect(
        resolveEffectiveTheme(
          { global: 'AUTO', localTheme: 'GRASS', localSetAt: on(8, 10).toISOString() },
          on(8, 25),
        ),
      ).toBe('grass')
    })

    it('accepts a Date for localSetAt', () => {
      expect(
        resolveEffectiveTheme(
          { global: 'AUTO', localTheme: 'CLAY', localSetAt: on(6, 15) },
          on(8, 20),
        ),
      ).toBe('uso')
    })
  })
})
