import { describe, it, expect } from 'vitest'
import {
  Capability,
  canEditEndedEvents,
  canManageMatches,
  canManagePointsBudget,
  canRate,
  canViewClubPointsSummary,
  hasCapability,
  isAdministrator,
  isClubOwner,
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

  it('isClubOwner checks the CLUB_OWNER capability (#403)', () => {
    expect(isClubOwner([Capability.CLUB_OWNER])).toBe(true)
    expect(isClubOwner([Capability.PLAYER])).toBe(false)
    expect(isClubOwner(undefined)).toBe(false)
  })

  it('canViewClubPointsSummary allows admins/points-managers and this-club owners only (#403)', () => {
    // Admin / points-manager pass regardless of ownership.
    expect(canViewClubPointsSummary([Capability.ADMINISTRATOR], [], 'u1')).toBe(true)
    expect(canViewClubPointsSummary([Capability.POINTS_MANAGER], [], 'u1')).toBe(true)
    // A CLUB_OWNER of this club (their id is among the owners) passes.
    expect(canViewClubPointsSummary([Capability.CLUB_OWNER], ['u1'], 'u1')).toBe(true)
    // A CLUB_OWNER of a different club (not among the owners) is denied.
    expect(canViewClubPointsSummary([Capability.CLUB_OWNER], ['u2'], 'u1')).toBe(false)
    // Missing user id, or a plain player, is denied.
    expect(canViewClubPointsSummary([Capability.CLUB_OWNER], ['u1'], undefined)).toBe(false)
    expect(canViewClubPointsSummary([Capability.PLAYER], ['u1'], 'u1')).toBe(false)
    expect(canViewClubPointsSummary(undefined, [], undefined)).toBe(false)
  })
})
