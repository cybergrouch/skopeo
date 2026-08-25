import { describe, it, expect } from "vitest";
import type { ClubResponse } from "@/api/generated/model";
import {
  canOrganizeClub,
  ownedClubs,
  ownsClubWithPublicCode,
} from "./clubAccess";

function club(
  name: string,
  publicCode: string,
  ...ownerIds: string[]
): ClubResponse {
  return {
    id: `id-${publicCode}`,
    name,
    publicCode,
    isActive: true,
    owners: ownerIds.map((userId) => ({
      userId,
      publicCode: `OWN-${userId}`,
    })),
  };
}

const ME = "me-1";
const CLUBS = [
  club("Downtown TC", "CLB001", ME, "other-1"),
  club("West End", "CLB002", ME),
  club("Northside", "CLB003", "other-1"),
];

describe("clubAccess (#789)", () => {
  it("lists only the clubs the viewer is a named owner of", () => {
    expect(ownedClubs(CLUBS, ME).map((c) => c.publicCode)).toEqual([
      "CLB001",
      "CLB002",
    ]);
    // Co-ownership counts: CLB001 has two owners and both qualify.
    expect(ownedClubs(CLUBS, "other-1").map((c) => c.publicCode)).toEqual([
      "CLB001",
      "CLB003",
    ]);
  });

  it("owns nothing when the viewer is anonymous or unprovisioned", () => {
    expect(ownedClubs(CLUBS, undefined)).toEqual([]);
    expect(ownedClubs(CLUBS, "nobody")).toEqual([]);
  });

  it("matches ownership on the club's public code, which is how public pages are addressed", () => {
    expect(ownsClubWithPublicCode(CLUBS, ME, "CLB001")).toBe(true);
    expect(ownsClubWithPublicCode(CLUBS, ME, "CLB003")).toBe(false);
    // A page that hasn't loaded yet has no code, so no affordance is offered.
    expect(ownsClubWithPublicCode(CLUBS, ME, undefined)).toBe(false);
    expect(ownsClubWithPublicCode([], ME, "CLB001")).toBe(false);
  });

  it("lets an administrator organize any club, owned or not", () => {
    expect(
      canOrganizeClub({
        capabilities: ["PLAYER", "ADMINISTRATOR"],
        clubs: CLUBS,
        meId: ME,
        publicCode: "CLB003",
      }),
    ).toBe(true);
    // Even with no clubs list loaded at all.
    expect(
      canOrganizeClub({
        capabilities: ["ADMINISTRATOR"],
        clubs: [],
        meId: undefined,
        publicCode: "CLB003",
      }),
    ).toBe(true);
  });

  it("lets an owner organize their own club and refuses another club", () => {
    expect(
      canOrganizeClub({
        capabilities: ["PLAYER", "CLUB_OWNER"],
        clubs: CLUBS,
        meId: ME,
        publicCode: "CLB001",
      }),
    ).toBe(true);
    expect(
      canOrganizeClub({
        capabilities: ["PLAYER", "CLUB_OWNER"],
        clubs: CLUBS,
        meId: ME,
        publicCode: "CLB003",
      }),
    ).toBe(false);
  });

  it("treats a plain HOST who owns the club exactly like a CLUB_OWNER", () => {
    expect(
      canOrganizeClub({
        capabilities: ["PLAYER", "HOST"],
        clubs: CLUBS,
        meId: ME,
        publicCode: "CLB002",
      }),
    ).toBe(true);
  });

  it("refuses a staff caller who owns no club, and an anonymous viewer", () => {
    expect(
      canOrganizeClub({
        capabilities: ["PLAYER", "HOST", "CLUB_OWNER"],
        clubs: CLUBS,
        meId: "nobody",
        publicCode: "CLB001",
      }),
    ).toBe(false);
    expect(
      canOrganizeClub({
        capabilities: undefined,
        clubs: CLUBS,
        meId: undefined,
        publicCode: "CLB001",
      }),
    ).toBe(false);
  });
});
