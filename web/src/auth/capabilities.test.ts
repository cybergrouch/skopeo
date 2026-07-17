import { describe, it, expect } from 'vitest'
import {
  Capability,
  canEditEndedEvents,
  canManageMatches,
  canManagePointsBudget,
  canRate,
  hasCapability,
  isAdministrator,
  isResearcher,
} from './capabilities'

describe('capabilities', () => {
  it('hasCapability checks membership and handles undefined', () => {
    expect(hasCapability([Capability.PLAYER], Capability.PLAYER)).toBe(true)
    expect(hasCapability([Capability.PLAYER], Capability.ADMINISTRATOR)).toBe(
      false,
    )
    expect(hasCapability(undefined, Capability.PLAYER)).toBe(false)
  })

  it('canManageMatches is true for hosts, club owners, and administrators', () => {
    expect(canManageMatches([Capability.PLAYER])).toBe(false)
    expect(canManageMatches([Capability.HOST])).toBe(true)
    expect(canManageMatches([Capability.CLUB_OWNER])).toBe(true)
    expect(canManageMatches([Capability.ADMINISTRATOR])).toBe(true)
  })

  it('isAdministrator is true only for administrators', () => {
    expect(isAdministrator([Capability.ADMINISTRATOR])).toBe(true)
    expect(isAdministrator([Capability.HOST])).toBe(false)
    expect(isAdministrator(undefined)).toBe(false)
  })

  it('canRate is true for raters and administrators (#106)', () => {
    expect(canRate([Capability.RATER])).toBe(true)
    expect(canRate([Capability.ADMINISTRATOR])).toBe(true)
    expect(canRate([Capability.PLAYER])).toBe(false)
    expect(canRate([Capability.HOST])).toBe(false)
    expect(canRate(undefined)).toBe(false)
  })

  it('canEditEndedEvents is true only for administrators and club owners (#310)', () => {
    expect(canEditEndedEvents([Capability.ADMINISTRATOR])).toBe(true)
    expect(canEditEndedEvents([Capability.CLUB_OWNER])).toBe(true)
    expect(canEditEndedEvents([Capability.HOST])).toBe(false)
    expect(canEditEndedEvents([Capability.PLAYER])).toBe(false)
    expect(canEditEndedEvents(undefined)).toBe(false)
  })

  it('canManagePointsBudget is true for points managers and administrators (#403)', () => {
    expect(canManagePointsBudget([Capability.POINTS_MANAGER])).toBe(true)
    expect(canManagePointsBudget([Capability.ADMINISTRATOR])).toBe(true)
    expect(canManagePointsBudget([Capability.PLAYER])).toBe(false)
    expect(canManagePointsBudget([Capability.HOST])).toBe(false)
    expect(canManagePointsBudget(undefined)).toBe(false)
  })

  it('isResearcher is true for researchers and administrators (#107)', () => {
    expect(isResearcher([Capability.RESEARCHER])).toBe(true)
    expect(isResearcher([Capability.ADMINISTRATOR])).toBe(true)
    expect(isResearcher([Capability.PLAYER])).toBe(false)
    expect(isResearcher([Capability.HOST])).toBe(false)
    expect(isResearcher(undefined)).toBe(false)
  })
})
