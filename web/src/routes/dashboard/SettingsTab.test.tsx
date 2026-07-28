import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { SettingsTab } from "./SettingsTab";

const { useGetApiV1UsersUserIdRatings } = vi.hoisted(() => ({
  useGetApiV1UsersUserIdRatings: vi.fn(),
}));

vi.mock("@/api/generated/ratings/ratings", () => ({
  useGetApiV1UsersUserIdRatings,
}));
// The moved sub-sections each have their own API wiring + tests; stub them so this test focuses on
// the Settings shell (which owner actions are present, gated on whether a rating exists).
vi.mock("@/components/ReRateRequestCard", () => ({
  ReRateRequestCard: () => <div>re-rate card</div>,
}));
vi.mock("@/components/ProfileFieldsForm", () => ({
  ProfileFieldsForm: () => <div>profile fields form</div>,
}));
vi.mock("@/components/PhotoSettingsForm", () => ({
  PhotoSettingsForm: () => <div>photo settings form</div>,
}));
vi.mock("@/components/LocalThemeForm", () => ({
  LocalThemeForm: () => <div>local theme form</div>,
}));

describe("SettingsTab", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useGetApiV1UsersUserIdRatings.mockReturnValue({
      data: undefined,
      isLoading: false,
    });
  });

  it("renders the Edit profile details section (form, photo, appearance)", () => {
    render(<SettingsTab userId="u1" />);
    expect(screen.getByText("Edit profile details")).toBeInTheDocument();
    expect(screen.getByText("profile fields form")).toBeInTheDocument();
    expect(screen.getByText("photo settings form")).toBeInTheDocument();
    expect(screen.getByText("local theme form")).toBeInTheDocument();
  });

  it("hides Rating reconsideration until the user has a rating", () => {
    render(<SettingsTab userId="u1" />);
    expect(screen.queryByText("re-rate card")).not.toBeInTheDocument();
  });

  it("shows Rating reconsideration once a rating exists", () => {
    useGetApiV1UsersUserIdRatings.mockReturnValue({
      data: [{ system: "NTRP", value: "4.000000", level: "4.0" }],
      isLoading: false,
    });
    render(<SettingsTab userId="u1" />);
    expect(screen.getByText("re-rate card")).toBeInTheDocument();
  });
});
