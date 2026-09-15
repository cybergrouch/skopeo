import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import { AccountManagementTab } from "./AccountManagementTab";

const { useGetApiV1UsersMe } = vi.hoisted(() => ({
  useGetApiV1UsersMe: vi.fn(),
}));
vi.mock("@/api/generated/users/users", () => ({ useGetApiV1UsersMe }));

/** Sign the viewer in with [capabilities]; every user holds PLAYER. */
function signedInAs(...capabilities: string[]) {
  useGetApiV1UsersMe.mockReturnValue({
    data: { capabilities: ["PLAYER", ...capabilities] },
  });
}

vi.mock("./admin/ManagePlayerSection", () => ({
  ManagePlayerSection: () => <div>manage player section</div>,
}));
vi.mock("./admin/DeletedAccountsSection", () => ({
  DeletedAccountsSection: () => <div>deleted accounts section</div>,
}));
vi.mock("./admin/DuplicatesSection", () => ({
  DuplicatesSection: () => <div>duplicates section</div>,
}));
vi.mock("./admin/DuplicateCandidatesSection", () => ({
  DuplicateCandidatesSection: () => <div>duplicate candidates section</div>,
}));
vi.mock("./admin/MergeAccountsSection", () => ({
  MergeAccountsSection: () => <div>merge accounts section</div>,
}));
vi.mock("./admin/InvitesSection", () => ({
  InvitesSection: () => <div>invites section</div>,
}));

describe("AccountManagementTab", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    signedInAs("ADMINISTRATOR");
  });

  it("renders the player/account administration sections moved out of Admin (#648)", () => {
    render(<AccountManagementTab />);
    expect(screen.getByText("manage player section")).toBeInTheDocument();
    expect(screen.getByText("deleted accounts section")).toBeInTheDocument();
    expect(screen.getByText("duplicates section")).toBeInTheDocument();
    expect(
      screen.getByText("duplicate candidates section"),
    ).toBeInTheDocument();
    expect(screen.getByText("merge accounts section")).toBeInTheDocument();
  });

  it("renders the invites section folded back in from its own tab (#725)", () => {
    render(<AccountManagementTab />);
    expect(screen.getByText("invites section")).toBeInTheDocument();
  });

  describe("as an ACCOUNT_MANAGER (#1002)", () => {
    beforeEach(() => signedInAs("ACCOUNT_MANAGER"));

    it("hides Manage player, whose controls answer to other rules entirely", () => {
      // Ratings (RATING_ROLES), points adjustments (POINTS_MANAGEMENT_ROLES), capability grant/revoke
      // and account soft-delete (ADMINISTRATOR). Showing the card would offer four controls that 403 —
      // the #867 shape — and would put a route to ADMINISTRATOR on the page.
      render(<AccountManagementTab />);
      expect(
        screen.queryByText("manage player section"),
      ).not.toBeInTheDocument();
    });

    it("still renders every section the role is actually for", () => {
      // The job has to remain coherent without that card: invites, restoring accounts, and the three
      // duplicate-rectification surfaces.
      render(<AccountManagementTab />);
      expect(screen.getByText("invites section")).toBeInTheDocument();
      expect(screen.getByText("deleted accounts section")).toBeInTheDocument();
      expect(screen.getByText("duplicates section")).toBeInTheDocument();
      expect(screen.getByText("merge accounts section")).toBeInTheDocument();
      expect(
        screen.getByText("duplicate candidates section"),
      ).toBeInTheDocument();
    });
  });
});
