import { describe, expect, it } from 'vitest'

import { formatPoints } from './points'

describe('formatPoints', () => {
  it('strips the fractional part and prepends a + sign', () => {
    expect(formatPoints('240.0000')).toBe('+240')
  })

  it('signs zero as +0', () => {
    expect(formatPoints('0')).toBe('+0')
    expect(formatPoints(0)).toBe('+0')
  })

  it('rounds decimals to the nearest integer', () => {
    expect(formatPoints('240.6')).toBe('+241')
    expect(formatPoints('240.4')).toBe('+240')
  })

  it('accepts a numeric input', () => {
    expect(formatPoints(240)).toBe('+240')
  })

  it('formats negatives defensively with a - sign', () => {
    expect(formatPoints('-5.0000')).toBe('-5')
    expect(formatPoints(-5)).toBe('-5')
  })

  it('returns null for null, undefined, and empty string', () => {
    expect(formatPoints(null)).toBeNull()
    expect(formatPoints(undefined)).toBeNull()
    expect(formatPoints('')).toBeNull()
  })

  it('returns null for unparseable input', () => {
    expect(formatPoints('abc')).toBeNull()
  })
})
