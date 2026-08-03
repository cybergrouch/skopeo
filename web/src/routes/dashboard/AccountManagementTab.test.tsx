import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { AccountManagementTab } from "./AccountManagementTab";

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

describe("AccountManagementTab", () => {
  it("renders the player/account administration sections moved out of Admin (#648)", () => {
    render(<AccountManagementTab />);
    expect(screen.getByText("manage player section")).toBeInTheDocument();
    expect(screen.getByText("deleted accounts section")).toBeInTheDocument();
    expect(screen.getByText("duplicates section")).toBeInTheDocument();
    expect(
      screen.getByText("duplicate candidates section"),
    ).toBeInTheDocument();
  });
});
