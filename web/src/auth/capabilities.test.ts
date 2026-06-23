import { describe, it, expect } from 'vitest'
import {
  Capability,
  canManageMatches,
  hasCapability,
  isAdministrator,
} from './capabilities'

describe('capabilities', () => {
  it('hasCapability checks membership and handles undefined', () => {
    expect(hasCapability([Capability.PLAYER], Capability.PLAYER)).toBe(true)
    expect(hasCapability([Capability.PLAYER], Capability.ADMINISTRATOR)).toBe(
      false,
    )
    expect(hasCapability(undefined, Capability.PLAYER)).toBe(false)
  })

  it('canManageMatches is true for hosts and administrators only', () => {
    expect(canManageMatches([Capability.PLAYER])).toBe(false)
    expect(canManageMatches([Capability.HOST])).toBe(true)
    expect(canManageMatches([Capability.ADMINISTRATOR])).toBe(true)
    expect(canManageMatches([Capability.CLUB_OWNER])).toBe(false)
  })

  it('isAdministrator is true only for administrators', () => {
    expect(isAdministrator([Capability.ADMINISTRATOR])).toBe(true)
    expect(isAdministrator([Capability.HOST])).toBe(false)
    expect(isAdministrator(undefined)).toBe(false)
  })
})
