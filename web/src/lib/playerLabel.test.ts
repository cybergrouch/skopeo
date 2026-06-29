import { describe, it, expect } from 'vitest'
import { playerLabel } from './playerLabel'

describe('playerLabel', () => {
  it('prefers the display name', () => {
    expect(playerLabel('Ana', 'AAA111', 'abcdef120000')).toBe('Ana')
  })

  it('falls back to the public code', () => {
    expect(playerLabel(null, 'AAA111', 'abcdef120000')).toBe('AAA111')
  })

  it('falls back to a sliced id when neither is set', () => {
    expect(playerLabel(null, null, 'abcdef120000')).toBe('abcdef12')
  })
})
