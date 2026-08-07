import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { ClubManagementTab } from "./ClubManagementTab";

vi.mock("./admin/ClubsSection", () => ({
  ClubsSection: () => <div>clubs section</div>,
}));

describe("ClubManagementTab", () => {
  it("renders the clubs administration section moved out of Admin (#698)", () => {
    render(<ClubManagementTab />);
    expect(screen.getByText("clubs section")).toBeInTheDocument();
  });
});
