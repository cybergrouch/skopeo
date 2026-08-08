import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { setupUser } from "@/test/user";
import { Capability } from "@/auth/capabilities";
import { ProfileTab } from "./ProfileTab";

const { toastError } = vi.hoisted(() => ({ toastError: vi.fn() }));
vi.mock("sonner", () => ({ toast: { error: toastError } }));

const {
  useGetApiV1UsersUserIdRatings,
  useGetApiV1UsersUserIdRatingHistory,
  useGetApiV1PlayersCodeMatchHistory,
  claimMutate,
  claimState,
  useAuthMock,
} = vi.hoisted(() => ({
  useGetApiV1UsersUserIdRatings: vi.fn(),
  useGetApiV1UsersUserIdRatingHistory: vi.fn(),
  useGetApiV1PlayersCodeMatchHistory: vi.fn(),
  claimMutate: vi.fn(),
  claimState: { isPending: false },
  useAuthMock: vi.fn(),
}));

vi.mock("@/api/generated/ratings/ratings", () => ({
  useGetApiV1UsersUserIdRatings,
  useGetApiV1UsersUserIdRatingHistory,
}));
vi.mock("@/api/generated/users/users", () => ({
  useGetApiV1PlayersCodeMatchHistory,
  usePostApiV1UsersClaim: () => ({
    mutateAsync: claimMutate,
    isPending: claimState.isPending,
  }),
  getGetApiV1UsersMeQueryKey: () => ["me"],
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
// The win–loss card has its own API hook + tests (#276); stub it here.
vi.mock("@/components/WinLossCard", () => ({
  WinLossCard: ({ code }: { code: string }) => <div>win-loss:{code}</div>,
}));
// The standing headline + points audit have their own API hooks + tests (#448); stub them here so this
// test stays focused on the Profile shell (and no real users API hook is invoked).
vi.mock("@/components/PlayerStandingCard", () => ({
  PlayerStandingCard: ({ code }: { code: string }) => <div>standing:{code}</div>,
}));
vi.mock("@/components/PointsAuditCard", () => ({
  PointsAuditCard: ({ code, enabled }: { code: string; enabled: boolean }) =>
    enabled ? <div>points-audit:{code}</div> : null,
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
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter>
        <ProfileTab
          userId="u1"
          capabilities={capabilities}
          publicCode={publicCode}
          photoUrl={photoUrl}
        />
      </MemoryRouter>
    </QueryClientProvider>,
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
    claimState.isPending = false;
    claimMutate.mockResolvedValue({
      id: "u1",
      publicCode: "K7Q2MX",
      country: "PH",
      kycVerified: false,
      isActive: true,
      names: [],
      contacts: [],
      identities: [],
      capabilities: [],
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

  it("shows the standing headline and the owner's active-points audit when a public code is present (#448)", () => {
    renderProfile([Capability.PLAYER], "K7Q2MX");
    expect(screen.getByText("standing:K7Q2MX")).toBeInTheDocument();
    // On the owner's own Profile tab the audit is always enabled (they are viewing themselves).
    expect(screen.getByText("points-audit:K7Q2MX")).toBeInTheDocument();
  });

  it("omits the standing headline and audit when there is no public code (#448)", () => {
    renderProfile([Capability.PLAYER]);
    expect(screen.queryByText(/^standing:/)).not.toBeInTheDocument();
    expect(screen.queryByText(/^points-audit:/)).not.toBeInTheDocument();
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

  it("no longer hosts the owner actions moved to the Settings tab (#589)", () => {
    renderProfile([Capability.PLAYER], "K7Q2MX");
    // Edit-profile-details and rating-reconsideration now live on the Settings tab.
    expect(screen.queryByText("Profile details")).not.toBeInTheDocument();
    expect(screen.queryByText("Edit profile details")).not.toBeInTheDocument();
    expect(screen.queryByText("re-rate card")).not.toBeInTheDocument();
  });

  it("shows the Ranking sub-section within the identity card, below Rating (#589)", () => {
    renderProfile([Capability.PLAYER], "K7Q2MX");
    // Rating label (identity card) precedes the Ranking sub-section (rendered via PlayerStandingCard).
    const rating = screen.getByText("Rating");
    const ranking = screen.getByText("standing:K7Q2MX");
    expect(rating).toBeInTheDocument();
    expect(ranking).toBeInTheDocument();
    // The ranking node comes after the rating node in document order.
    expect(
      rating.compareDocumentPosition(ranking) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
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

  it("appends the computed rating confidence as an explainable percentage (#343, #463)", () => {
    useGetApiV1UsersUserIdRatings.mockReturnValue({
      data: [{ system: "NTRP", value: "4.000000", level: "4.0", confidence: "0.87" }],
      isLoading: false,
    });
    renderProfile();
    // The confidence renders through the shared ConfidenceValue tooltip trigger (#463), so the
    // percentage is a focusable button carrying an explanatory aria-label. It appears both in the
    // Rating card and in the Rating-history header, so more than one trigger is expected.
    const triggers = screen.getAllByRole("button", {
      name: /rating confidence 87%/i,
    });
    expect(triggers.length).toBeGreaterThan(0);
    expect(triggers[0]).toHaveTextContent("87%");
    expect(triggers[0]).toHaveAttribute("aria-describedby");
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

  // Claim-a-placeholder card (#727), shown only while the account is claim-eligible (empty).
  describe("claim a placeholder account (#727)", () => {
    it("shows the claim form for an eligible, empty account (no rating, no matches)", () => {
      renderProfile([Capability.PLAYER], "K7Q2MX");
      expect(
        screen.getByText("Claim a placeholder account"),
      ).toBeInTheDocument();
      expect(screen.getByLabelText("Claim code")).toBeInTheDocument();
      expect(
        screen.getByRole("button", { name: "Claim account" }),
      ).toBeInTheDocument();
    });

    it("hides the claim form once the account has a rating", () => {
      useGetApiV1UsersUserIdRatings.mockReturnValue({
        data: [{ system: "NTRP", value: "4.000000", level: "4.0" }],
        isLoading: false,
      });
      renderProfile([Capability.PLAYER], "K7Q2MX");
      expect(
        screen.queryByText("Claim a placeholder account"),
      ).not.toBeInTheDocument();
    });

    it("hides the claim form once the account has match history", () => {
      useGetApiV1PlayersCodeMatchHistory.mockReturnValue({
        data: { items: [], total: 3 },
        isLoading: false,
      });
      renderProfile([Capability.PLAYER], "K7Q2MX");
      expect(
        screen.queryByText("Claim a placeholder account"),
      ).not.toBeInTheDocument();
    });

    it("does not flash the claim form while the eligibility signals are loading", () => {
      useGetApiV1UsersUserIdRatings.mockReturnValue({
        data: undefined,
        isLoading: true,
      });
      renderProfile([Capability.PLAYER], "K7Q2MX");
      expect(
        screen.queryByText("Claim a placeholder account"),
      ).not.toBeInTheDocument();
    });

    it("claims an account and replaces the form with the success state", async () => {
      const user = setupUser();
      renderProfile([Capability.PLAYER], "K7Q2MX");
      await user.type(screen.getByLabelText("Claim code"), "SECRET-1234");
      await user.click(screen.getByRole("button", { name: "Claim account" }));

      await waitFor(() =>
        expect(claimMutate).toHaveBeenCalledWith({
          data: { code: "SECRET-1234" },
        }),
      );
      expect(await screen.findByText("Account claimed")).toBeInTheDocument();
      // The form itself is gone once claimed.
      expect(screen.queryByLabelText("Claim code")).not.toBeInTheDocument();
      expect(
        screen.getByRole("link", { name: /view your profile/i }),
      ).toHaveAttribute("href", "/players/K7Q2MX");
    });

    it("shows a pending label and disables the button while the claim is in flight", () => {
      claimState.isPending = true;
      renderProfile([Capability.PLAYER], "K7Q2MX");
      const button = screen.getByRole("button", { name: "Claiming…" });
      expect(button).toBeDisabled();
    });

    it("surfaces the server error message on a failed claim and stays on the form", async () => {
      claimMutate.mockRejectedValue({
        response: { data: { message: "This claim code has expired." } },
      });
      const user = setupUser();
      renderProfile([Capability.PLAYER], "K7Q2MX");
      await user.type(screen.getByLabelText("Claim code"), "OLD-CODE");
      await user.click(screen.getByRole("button", { name: "Claim account" }));

      await waitFor(() =>
        expect(toastError).toHaveBeenCalledWith("This claim code has expired.", {
          duration: 8000,
        }),
      );
      expect(screen.queryByText("Account claimed")).not.toBeInTheDocument();
    });

    it("validates that a code is entered before claiming", async () => {
      const user = setupUser();
      renderProfile([Capability.PLAYER], "K7Q2MX");
      await user.click(screen.getByRole("button", { name: "Claim account" }));
      expect(
        await screen.findByText(/enter the claim code you were given/i),
      ).toBeInTheDocument();
      expect(claimMutate).not.toHaveBeenCalled();
    });
  });
});
