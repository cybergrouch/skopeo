import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { AdminTab } from "./AdminTab";

vi.mock("./admin/PendingCalculationSection", () => ({
  PendingCalculationSection: () => <div>pending calculation section</div>,
}));
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
vi.mock("./admin/BuildInfoSection", () => ({
  BuildInfoSection: () => <div>build info section</div>,
}));
vi.mock("./admin/ClubsSection", () => ({
  ClubsSection: () => <div>clubs section</div>,
}));
vi.mock("./admin/ThemeSection", () => ({
  ThemeSection: () => <div>theme section</div>,
}));
vi.mock("./admin/StandingsSourceSection", () => ({
  StandingsSourceSection: () => <div>standings source section</div>,
}));

describe("AdminTab", () => {
  it("renders the admin sections (invites #135 and activity log #134 now live in their own tabs)", () => {
    render(<AdminTab />);
    expect(screen.getByText("manage player section")).toBeInTheDocument();
    expect(screen.getByText("deleted accounts section")).toBeInTheDocument();
    expect(screen.getByText("clubs section")).toBeInTheDocument();
    expect(screen.getByText("duplicates section")).toBeInTheDocument();
    expect(
      screen.getByText("duplicate candidates section"),
    ).toBeInTheDocument();
    expect(screen.getByText("pending calculation section")).toBeInTheDocument();
    expect(screen.getByText("theme section")).toBeInTheDocument();
    expect(screen.getByText("standings source section")).toBeInTheDocument();
    expect(screen.getByText("build info section")).toBeInTheDocument();
    expect(screen.queryByText("invites section")).not.toBeInTheDocument();
    expect(screen.queryByText("activity log section")).not.toBeInTheDocument();
    // Points Management moved to its own top-level dashboard tab (#444), so it's no longer here.
    expect(
      screen.queryByText("points management section"),
    ).not.toBeInTheDocument();
  });
});
