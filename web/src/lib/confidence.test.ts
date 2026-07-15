import { describe, expect, it } from 'vitest'
import { formatConfidence } from './confidence'

describe('formatConfidence', () => {
  it('renders a 0..1 decimal string as a whole percent', () => {
    expect(formatConfidence('1')).toBe('100%')
    expect(formatConfidence('0.87')).toBe('87%')
    expect(formatConfidence('0.005')).toBe('1%')
    expect(formatConfidence('0')).toBe('0%')
  })

  it('returns null when confidence is absent or unparseable', () => {
    expect(formatConfidence(undefined)).toBeNull()
    expect(formatConfidence(null)).toBeNull()
    expect(formatConfidence('')).toBeNull()
    expect(formatConfidence('not-a-number')).toBeNull()
  })
})
