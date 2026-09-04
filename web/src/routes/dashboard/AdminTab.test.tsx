import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { AdminTab } from "./AdminTab";

vi.mock("./admin/PendingCalculationSection", () => ({
  PendingCalculationSection: () => <div>pending calculation section</div>,
}));
vi.mock("./admin/BuildInfoSection", () => ({
  BuildInfoSection: () => <div>build info section</div>,
}));
vi.mock("./admin/CircuitsSection", () => ({
  CircuitsSection: () => <div>circuits section</div>,
}));
vi.mock("./admin/ThemeSection", () => ({
  ThemeSection: () => <div>theme section</div>,
}));
vi.mock("./admin/FeatureFlagsSection", () => ({
  FeatureFlagsSection: () => <div>feature flags section</div>,
}));
vi.mock("./admin/StandingsSourceSection", () => ({
  StandingsSourceSection: () => <div>standings source section</div>,
}));
vi.mock("./admin/ApiClientsSection", () => ({
  ApiClientsSection: () => <div>api clients section</div>,
}));
// Stubbed like every other section: the real one reads a setting through react-query, so rendering it
// here would need a QueryClientProvider this test has no reason to set up (#881).
vi.mock("./admin/CalibrationWindowSection", () => ({
  CalibrationWindowSection: () => <div>calibration window section</div>,
}));

describe("AdminTab", () => {
  it("renders the admin sections (invites #135 and activity log #134 now live in their own tabs)", () => {
    render(<AdminTab />);
    expect(screen.getByText("circuits section")).toBeInTheDocument();
    expect(screen.getByText("pending calculation section")).toBeInTheDocument();
    expect(screen.getByText("theme section")).toBeInTheDocument();
    expect(screen.getByText("standings source section")).toBeInTheDocument();
    expect(screen.getByText("feature flags section")).toBeInTheDocument();
    expect(screen.getByText("calibration window section")).toBeInTheDocument();
    expect(screen.getByText("api clients section")).toBeInTheDocument();
    expect(screen.getByText("build info section")).toBeInTheDocument();
    expect(screen.queryByText("invites section")).not.toBeInTheDocument();
    expect(screen.queryByText("activity log section")).not.toBeInTheDocument();
    // Points Management moved to its own top-level dashboard tab (#444), so it's no longer here.
    expect(
      screen.queryByText("points management section"),
    ).not.toBeInTheDocument();
    // Clubs administration moved to the Club Management tab (#698).
    expect(screen.queryByText("clubs section")).not.toBeInTheDocument();
    // Player/account administration moved to the Account Management tab (#648).
    expect(screen.queryByText("manage player section")).not.toBeInTheDocument();
    expect(
      screen.queryByText("deleted accounts section"),
    ).not.toBeInTheDocument();
    expect(screen.queryByText("duplicates section")).not.toBeInTheDocument();
    expect(
      screen.queryByText("duplicate candidates section"),
    ).not.toBeInTheDocument();
  });
});
