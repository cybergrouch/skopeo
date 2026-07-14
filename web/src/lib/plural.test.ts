import { describe, it, expect } from 'vitest'
import { plural } from './plural'

describe('plural', () => {
  it('is empty for exactly one', () => {
    expect(plural(1)).toBe('')
    expect(plural(1, 'es')).toBe('')
  })

  it("defaults to 's' for zero and many (player → players)", () => {
    expect(plural(0)).toBe('s')
    expect(plural(2)).toBe('s')
  })

  it("uses a custom suffix when given (match → matches)", () => {
    expect(plural(0, 'es')).toBe('es')
    expect(plural(2, 'es')).toBe('es')
  })
})
