import { describe, it, expect } from 'vitest'
import { plural } from './plural'

describe('plural', () => {
  it('is empty for exactly one', () => {
    expect(plural(1)).toBe('')
  })

  it("is 'es' for zero and many", () => {
    expect(plural(0)).toBe('es')
    expect(plural(2)).toBe('es')
  })
})
