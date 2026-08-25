import type { ClubResponse } from "@/api/generated/model";
import { isAdministrator, type Capability } from "./capabilities";

/**
 * The clubs the viewer is a NAMED OWNER of (#789) — the client mirror of the server's `club_owners`
 * read (`ClubAccess.ownedClubIds`). `GET /api/v1/clubs` already carries each club's owners and is
 * readable by exactly the staff who could qualify, so ownership needs no new field on the wire.
 */
export function ownedClubs(
  clubs: readonly ClubResponse[],
  meId: string | undefined,
): ClubResponse[] {
  if (!meId) return [];
  return clubs.filter((club) =>
    club.owners.some((owner) => owner.userId === meId),
  );
}

/**
 * Whether the viewer owns the club with [publicCode]. The public club page is addressed by public code
 * rather than id — `ClubPublicResponse` deliberately carries no internal ids — so the match is made on
 * the code and the staff-readable clubs list supplies the owners.
 */
export function ownsClubWithPublicCode(
  clubs: readonly ClubResponse[],
  meId: string | undefined,
  publicCode: string | undefined,
): boolean {
  if (!publicCode) return false;
  return ownedClubs(clubs, meId).some((club) => club.publicCode === publicCode);
}

/**
 * Whether the viewer may organize events for the club with [publicCode] (#789): an ADMINISTRATOR
 * anywhere, otherwise only a named owner of *that* club. This mirrors `ClubAccess.mayFileUnder` — the
 * server refuses a create filed under a club the caller doesn't own — so the UI stops offering a form
 * whose submission would 403. It is never the only gate.
 *
 * Note there is no `canManageMatches` fallback: holding HOST or CLUB_OWNER is what makes you *eligible*
 * to own a club, not a claim on every club's calendar.
 */
export function canOrganizeClub({
  capabilities,
  clubs,
  meId,
  publicCode,
}: {
  capabilities: readonly Capability[] | undefined;
  clubs: readonly ClubResponse[];
  meId: string | undefined;
  publicCode: string | undefined;
}): boolean {
  return (
    isAdministrator(capabilities) ||
    ownsClubWithPublicCode(clubs, meId, publicCode)
  );
}
