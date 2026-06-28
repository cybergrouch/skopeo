import { UserResponseCapabilitiesItem } from '@/api/generated/model'

/** The capabilities the backend grants a user (PLAYER, HOST, CLUB_OWNER, ADMINISTRATOR). */
export const Capability = UserResponseCapabilitiesItem
export type Capability = UserResponseCapabilitiesItem

export function hasCapability(
  capabilities: readonly Capability[] | undefined,
  capability: Capability,
): boolean {
  return Boolean(capabilities?.includes(capability))
}

/**
 * The Matches tab is for match managers: hosts, club owners, and administrators.
 * (Profile and Research are available to every player; the Admin tab is
 * administrators-only — see {@link isAdministrator}.)
 */
export function canManageMatches(
  capabilities: readonly Capability[] | undefined,
): boolean {
  return (
    hasCapability(capabilities, Capability.HOST) ||
    hasCapability(capabilities, Capability.CLUB_OWNER) ||
    hasCapability(capabilities, Capability.ADMINISTRATOR)
  )
}

export function isAdministrator(
  capabilities: readonly Capability[] | undefined,
): boolean {
  return hasCapability(capabilities, Capability.ADMINISTRATOR)
}

/**
 * The Ratings tab is for raters: users who can set initial ratings and triage rating
 * work (#106). ADMINISTRATOR implicitly has RATER.
 */
export function canRate(
  capabilities: readonly Capability[] | undefined,
): boolean {
  return (
    hasCapability(capabilities, Capability.RATER) ||
    hasCapability(capabilities, Capability.ADMINISTRATOR)
  )
}

/**
 * The Research tab is for researchers (#107) — gated so it can later be monetized. Every sign-up
 * gets RESEARCHER for now, so behaviour is unchanged. ADMINISTRATOR implicitly has RESEARCHER.
 */
export function isResearcher(
  capabilities: readonly Capability[] | undefined,
): boolean {
  return (
    hasCapability(capabilities, Capability.RESEARCHER) ||
    hasCapability(capabilities, Capability.ADMINISTRATOR)
  )
}
