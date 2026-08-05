import { UserResponseCapabilitiesItem } from "@/api/generated/model";

/** The capabilities the backend grants a user (PLAYER, HOST, CLUB_OWNER, ADMINISTRATOR). */
export const Capability = UserResponseCapabilitiesItem;
export type Capability = UserResponseCapabilitiesItem;

export function hasCapability(
  capabilities: readonly Capability[] | undefined,
  capability: Capability,
): boolean {
  return Boolean(capabilities?.includes(capability));
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
  );
}

export function isAdministrator(
  capabilities: readonly Capability[] | undefined,
): boolean {
  return hasCapability(capabilities, Capability.ADMINISTRATOR);
}

/**
 * Whether the viewer may see raw NTRP values (full precision), #583: ADMINISTRATOR and not previewing
 * as a non-admin via the per-admin toggle. Mirrors the backend `User.canSeeRawRating` so the UI reveals
 * raw ratings on exactly the same rule (e.g. the public-profile rating history, #654).
 */
export function canSeeRawRatings(
  capabilities: readonly Capability[] | undefined,
  previewRatingsAsNonAdmin: boolean | undefined,
): boolean {
  return isAdministrator(capabilities) && !previewRatingsAsNonAdmin;
}

/**
 * The Settings tab is for players managing their own account (#589): every signed-in user is a PLAYER,
 * so in practice this is always true — it's an explicit gate so the tab reads as player-owned.
 */
export function isPlayer(
  capabilities: readonly Capability[] | undefined,
): boolean {
  return hasCapability(capabilities, Capability.PLAYER);
}

/**
 * Who may still enter data on an event after it has ended (#310, #326): administrators and club
 * owners. A plain HOST is blocked once the event's end date has passed — this mirrors the backend
 * EXPIRY_EXEMPT_ROLES, so the UI just avoids offering an action the server would 409.
 */
export function canEditEndedEvents(
  capabilities: readonly Capability[] | undefined,
): boolean {
  return (
    hasCapability(capabilities, Capability.ADMINISTRATOR) ||
    hasCapability(capabilities, Capability.CLUB_OWNER)
  );
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
  );
}

/**
 * The Points Management tab is for points managers (#403 §5.1): the staff role over the points
 * economy — now the global award schedules (#552/#553) and the ranking-points ledger (#472), after the
 * per-club budget + per-event designation subsystem was removed (#559). ADMINISTRATOR is implicitly one.
 */
export function canManagePointsBudget(
  capabilities: readonly Capability[] | undefined,
): boolean {
  return (
    hasCapability(capabilities, Capability.POINTS_MANAGER) ||
    hasCapability(capabilities, Capability.ADMINISTRATOR)
  );
}

/**
 * Who may see a player's active-points audit (#448): the profile owner (the viewer is looking at their
 * own profile — {@link isOwner}) or an ADMINISTRATOR. Other and anonymous viewers get only the public
 * rank + points headline, never the audit. Mirrors the backend owner-or-admin gate; also gates the fetch.
 */
export function canViewPointsAudit(
  capabilities: readonly Capability[] | undefined,
  isOwner: boolean,
): boolean {
  return isOwner || isAdministrator(capabilities);
}

/**
 * The Research tab is for researchers (#107) — gated so it can later be monetized. RESEARCHER is a
 * separately-granted capability (no longer given to every sign-up, #622); a plain player without it
 * does not see the Research tab. ADMINISTRATOR implicitly has RESEARCHER.
 */
export function isResearcher(
  capabilities: readonly Capability[] | undefined,
): boolean {
  return (
    hasCapability(capabilities, Capability.RESEARCHER) ||
    hasCapability(capabilities, Capability.ADMINISTRATOR)
  );
}
