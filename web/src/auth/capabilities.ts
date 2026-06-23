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

/** Match-management tabs are for hosts and administrators. */
export function canManageMatches(
  capabilities: readonly Capability[] | undefined,
): boolean {
  return (
    hasCapability(capabilities, Capability.HOST) ||
    hasCapability(capabilities, Capability.ADMINISTRATOR)
  )
}

export function isAdministrator(
  capabilities: readonly Capability[] | undefined,
): boolean {
  return hasCapability(capabilities, Capability.ADMINISTRATOR)
}
