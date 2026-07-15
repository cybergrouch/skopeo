import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { Capability } from "@/auth/capabilities";
import { ProfileTab } from "./ProfileTab";

const {
  useGetApiV1UsersUserIdRatings,
  useGetApiV1UsersUserIdRatingHistory,
  useGetApiV1PlayersCodeMatchHistory,
  useAuthMock,
} = vi.hoisted(() => ({
  useGetApiV1UsersUserIdRatings: vi.fn(),
  useGetApiV1UsersUserIdRatingHistory: vi.fn(),
  useGetApiV1PlayersCodeMatchHistory: vi.fn(),
  useAuthMock: vi.fn(),
}));

vi.mock("@/api/generated/ratings/ratings", () => ({
  useGetApiV1UsersUserIdRatings,
  useGetApiV1UsersUserIdRatingHistory,
}));
vi.mock("@/api/generated/users/users", () => ({
  useGetApiV1PlayersCodeMatchHistory,
}));
// RatingHistoryCard pulls in the matches API (axios → firebase); mock it so the real Firebase
// client never initializes in tests.
vi.mock("@/api/generated/matches/matches", () => ({
  useGetApiV1MatchesIdCalculation: vi.fn(() => ({
    data: undefined,
    isLoading: false,
  })),
}));
vi.mock("@/auth/useAuth", () => ({ useAuth: useAuthMock }));
// The band meter animates via requestAnimationFrame/matchMedia; stub it so these tests stay focused
// on the Rating card wiring (the meter itself is covered in RatingBandMeter.test.tsx).
vi.mock("@/components/RatingBandMeter", () => ({
  RatingBandMeter: () => <div>band meter</div>,
}));
// The re-rate card has its own API wiring + tests (#140); stub it here.
vi.mock("@/components/ReRateRequestCard", () => ({
  ReRateRequestCard: () => <div>re-rate card</div>,
}));
// The win–loss card has its own API hook + tests (#276); stub it here.
vi.mock("@/components/WinLossCard", () => ({
  WinLossCard: ({ code }: { code: string }) => <div>win-loss:{code}</div>,
}));
// The editable name/demographics form has its own tests (#196/#199); stub it here so this test
// stays focused on the Profile shell.
vi.mock("@/components/ProfileFieldsForm", () => ({
  ProfileFieldsForm: () => <div>profile fields form</div>,
}));
// The photo-settings form has its own API wiring + tests (#303); stub it here.
vi.mock("@/components/PhotoSettingsForm", () => ({
  PhotoSettingsForm: () => <div>photo settings form</div>,
}));
// The events-history card has its own tests (#202) + its own API hook; stub it here.
vi.mock("@/components/EventsHistoryCard", () => ({
  EventsHistoryCard: () => <div>events history</div>,
}));
// The upcoming-matches card has its own tests (#251) + its own API hook; stub it here.
vi.mock("@/components/UpcomingMatchesCard", () => ({
  UpcomingMatchesCard: () => <div>upcoming matches</div>,
}));

function renderProfile(
  capabilities: Capability[] = [Capability.PLAYER],
  publicCode?: string,
  photoUrl?: string | null,
) {
  return render(
    <ProfileTab
      userId="u1"
      capabilities={capabilities}
      publicCode={publicCode}
      photoUrl={photoUrl}
    />,
  );
}

describe("ProfileTab", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useAuthMock.mockReturnValue({
      user: { displayName: "Roger F.", email: "roger@example.com" },
    });
    useGetApiV1UsersUserIdRatings.mockReturnValue({
      data: undefined,
      isLoading: false,
    });
    useGetApiV1UsersUserIdRatingHistory.mockReturnValue({
      data: undefined,
      isLoading: false,
    });
    useGetApiV1PlayersCodeMatchHistory.mockReturnValue({
      data: { items: [], total: 0 },
      isLoading: false,
    });
  });

  it("shows identity and capability badges", () => {
    renderProfile([Capability.PLAYER, Capability.HOST]);
    expect(screen.getByText("Roger F.")).toBeInTheDocument();
    expect(screen.getByText("roger@example.com")).toBeInTheDocument();
    expect(screen.getByText("PLAYER")).toBeInTheDocument();
    expect(screen.getByText("HOST")).toBeInTheDocument();
  });

  it("falls back to the email as the title when there is no display name", () => {
    useAuthMock.mockReturnValue({
      user: { displayName: null, email: "roger@example.com" },
    });
    renderProfile();
    // Email appears as both the title and the description.
    expect(screen.getAllByText("roger@example.com").length).toBeGreaterThan(0);
  });

  it("falls back to 'Player' when there is no user", () => {
    useAuthMock.mockReturnValue({ user: null });
    renderProfile();
    expect(screen.getByText("Player")).toBeInTheDocument();
  });

  it("shows the shareable player code when provided", () => {
    renderProfile([Capability.PLAYER], "K7Q2MX");
    expect(screen.getByText("K7Q2MX")).toBeInTheDocument();
  });

  it("shows a QR code and a copy-link button when a public code is present", () => {
    const { container } = renderProfile([Capability.PLAYER], "K7Q2MX");
    expect(
      screen.getByRole("button", { name: "Copy link" }),
    ).toBeInTheDocument();
    expect(container.querySelector("svg")).toBeInTheDocument();
  });

  it("copies the share link to the clipboard and shows feedback", () => {
    const writeText = vi.fn();
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText },
      configurable: true,
    });
    renderProfile([Capability.PLAYER], "K7Q2MX");

    fireEvent.click(screen.getByRole("button", { name: "Copy link" }));

    expect(writeText).toHaveBeenCalledWith(
      `${window.location.origin}/players/K7Q2MX`,
    );
    expect(screen.getByRole("button", { name: "Copied!" })).toBeInTheDocument();
  });

  it("shows the provider avatar when a photo URL is present", () => {
    useAuthMock.mockReturnValue({
      user: {
        displayName: "Roger F.",
        email: "roger@example.com",
        photoURL: "https://example.com/avatar.jpg",
      },
    });
    const { container } = renderProfile();
    expect(container.querySelector("img")).toHaveAttribute(
      "src",
      "https://example.com/avatar.jpg",
    );
  });

  it("prefers the API effective photo over the provider photo (#303)", () => {
    useAuthMock.mockReturnValue({
      user: {
        displayName: "Roger F.",
        email: "roger@example.com",
        photoURL: "https://provider.example/avatar.jpg",
      },
    });
    const { container } = renderProfile(
      [Capability.PLAYER],
      undefined,
      "https://custom.example/me.jpg",
    );
    expect(container.querySelector("img")).toHaveAttribute(
      "src",
      "https://custom.example/me.jpg",
    );
  });

  it("shows initials (no photo) when the effective photo is null, even with a provider photo (#303)", () => {
    useAuthMock.mockReturnValue({
      user: {
        displayName: "Roger F.",
        email: "roger@example.com",
        photoURL: "https://provider.example/avatar.jpg",
      },
    });
    const { container } = renderProfile([Capability.PLAYER], undefined, null);
    expect(container.querySelector("img")).toBeNull();
    expect(screen.getByText("R")).toBeInTheDocument();
  });

  it("renders the editable profile-details form", () => {
    renderProfile();
    expect(screen.getByText("Profile details")).toBeInTheDocument();
    expect(screen.getByText("profile fields form")).toBeInTheDocument();
  });

  it("shows the pending notice when there is no rating", () => {
    renderProfile();
    expect(screen.getByText("Pending assessment")).toBeInTheDocument();
  });

  it("lists ratings with and without a level", () => {
    useGetApiV1UsersUserIdRatings.mockReturnValue({
      data: [
        { system: "NTRP", value: "4.000000", level: "4.0" },
        { system: "UTR", value: "8.500000", level: null },
      ],
      isLoading: false,
    });
    renderProfile();
    // Band only — never the 6-decimal value when a level is present.
    expect(screen.getByText("4.0")).toBeInTheDocument();
    expect(screen.queryByText("4.000000 · 4.0")).not.toBeInTheDocument();
    // Falls back to the value when there's no published level.
    expect(screen.getByText("8.500000")).toBeInTheDocument();
    expect(screen.queryByText("Pending assessment")).not.toBeInTheDocument();
  });

  it("appends the computed rating confidence as a percentage (#343)", () => {
    useGetApiV1UsersUserIdRatings.mockReturnValue({
      data: [{ system: "NTRP", value: "4.000000", level: "4.0", confidence: "0.87" }],
      isLoading: false,
    });
    renderProfile();
    expect(screen.getByText(/· 87%/)).toBeInTheDocument();
  });

  it("renders the band meter when a rating exposes a band position", () => {
    useGetApiV1UsersUserIdRatings.mockReturnValue({
      data: [{ system: "NTRP", value: null, level: "4.0", bandPosition: 0.7 }],
      isLoading: false,
    });
    renderProfile();
    expect(screen.getByText("4.0")).toBeInTheDocument();
    expect(screen.getByText("band meter")).toBeInTheDocument();
  });

  it("omits the band meter when there is no band position", () => {
    useGetApiV1UsersUserIdRatings.mockReturnValue({
      data: [{ system: "NTRP", value: null, level: "4.0" }],
      isLoading: false,
    });
    renderProfile();
    expect(screen.getByText("4.0")).toBeInTheDocument();
    expect(screen.queryByText("band meter")).not.toBeInTheDocument();
  });

  it("renders history entries, with and without a level change", () => {
    useGetApiV1UsersUserIdRatingHistory.mockReturnValue({
      data: [
        {
          id: "h1",
          system: "NTRP",
          previousRating: "4.000000",
          newRating: "4.100000",
          newLevel: "4.5",
          levelChanged: true,
          calculatedAt: "2026-06-01T12:00:00",
        },
        {
          id: "h2",
          system: "NTRP",
          previousRating: "4.100000",
          newRating: "4.050000",
          newLevel: "4.5",
          levelChanged: false,
          calculatedAt: "2026-06-02T12:00:00",
        },
      ],
      isLoading: false,
    });
    renderProfile();
    // Full value lines (band transitions are rendered separately and covered in RatingHistoryCard).
    expect(screen.getByText("4.000000 → 4.100000")).toBeInTheDocument();
    expect(screen.getByText("4.100000 → 4.050000")).toBeInTheDocument();
  });

  it("shows an empty state when there is no history", () => {
    renderProfile();
    expect(screen.getByText("No rating changes yet.")).toBeInTheDocument();
  });

  it("shows loading states while ratings and history resolve", () => {
    useGetApiV1UsersUserIdRatings.mockReturnValue({
      data: undefined,
      isLoading: true,
    });
    useGetApiV1UsersUserIdRatingHistory.mockReturnValue({
      data: undefined,
      isLoading: true,
    });
    renderProfile();
    expect(screen.getAllByText("Loading…").length).toBe(2);
  });
});
