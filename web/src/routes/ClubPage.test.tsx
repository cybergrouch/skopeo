import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ClubPage } from "./ClubPage";

const { useGetApiV1ClubsCodeCode, useGetApiV1UsersMe, state } = vi.hoisted(
  () => ({
    useGetApiV1ClubsCodeCode: vi.fn(),
    useGetApiV1UsersMe: vi.fn(),
    state: { user: { uid: "u1" } as { uid: string } | null },
  }),
);
// PublicPageNav reads auth (#193); default to a logged-in user, overridden per test.
vi.mock("@/auth/useAuth", () => ({ useAuth: () => ({ user: state.user }) }));
vi.mock("@/api/generated/clubs/clubs", () => ({ useGetApiV1ClubsCodeCode }));
// The viewer lookup that gates the club's own New Event form (#780); anonymous by default.
vi.mock("@/api/generated/users/users", () => ({ useGetApiV1UsersMe }));
// Both are exercised in their own tests; here we only assert the page's wiring and gating.
vi.mock("@/features/event/NewEventForm", () => ({
  NewEventForm: ({ fixedClubPublicCode }: { fixedClubPublicCode?: string }) => (
    <div>new-event-form:{fixedClubPublicCode}</div>
  ),
}));
vi.mock("@/features/club/ClubEventsCard", () => ({
  ClubEventsCard: ({
    code,
    bucket,
    title,
  }: {
    code: string;
    bucket: string;
    title: string;
  }) => (
    <div>
      events-card:{bucket}:{code}:{title}
    </div>
  ),
}));

// The summary carries no events at all now (#786) — they are fetched per bucket by the cards.
const club = {
  publicCode: "CLB001",
  name: "Downtown TC",
  isActive: true,
};

function renderAt(code = "CLB001") {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter initialEntries={[`/clubs/${code}`]}>
        <Routes>
          <Route path="/clubs/:code" element={<ClubPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("ClubPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.user = { uid: "u1" };
    // Anonymous by default: no capabilities, so no organizer affordances.
    useGetApiV1UsersMe.mockReturnValue({ data: undefined });
  });

  it("shows a loading state", () => {
    useGetApiV1ClubsCodeCode.mockReturnValue({
      data: undefined,
      isLoading: true,
    });
    renderAt();
    expect(screen.getByText("Loading club…")).toBeInTheDocument();
  });

  it("shows an error state", () => {
    useGetApiV1ClubsCodeCode.mockReturnValue({
      data: undefined,
      isError: true,
    });
    renderAt();
    expect(
      screen.getByText(/couldn’t find or load this club/i),
    ).toBeInTheDocument();
  });

  it("renders one paginated card per grouping, wired to this club (#786)", () => {
    useGetApiV1ClubsCodeCode.mockReturnValue({ data: club, isLoading: false });
    renderAt();

    expect(screen.getByText("Downtown TC")).toBeInTheDocument();
    expect(screen.getByText("CLB001")).toBeInTheDocument();

    // One separately-paginated card per bucket, in order.
    expect(
      screen.getByText("events-card:UPCOMING:CLB001:Upcoming events"),
    ).toBeInTheDocument();
    expect(
      screen.getByText("events-card:UNFINALIZED:CLB001:Unfinalized events"),
    ).toBeInTheDocument();
    expect(
      screen.getByText("events-card:FINALIZED:CLB001:Finalized events"),
    ).toBeInTheDocument();

    // Share card still points at the public view.
    expect(screen.getByText("Share this club")).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Copy link" }),
    ).toBeInTheDocument();
  });

  it("offers a New Event form fixed to this club for a match manager (#780)", () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { capabilities: ["PLAYER", "HOST"] },
    });
    useGetApiV1ClubsCodeCode.mockReturnValue({ data: club, isLoading: false });
    renderAt();

    // Filed under this club, identified by its public code — no club selector to get wrong.
    expect(screen.getByText("new-event-form:CLB001")).toBeInTheDocument();
  });

  it("hides the New Event form from an anonymous visitor, leaving the page intact (#780)", () => {
    useGetApiV1ClubsCodeCode.mockReturnValue({ data: club, isLoading: false });
    renderAt();

    expect(screen.queryByText(/new-event-form/)).not.toBeInTheDocument();
    // The public page still renders normally, event cards included.
    expect(screen.getByText("Downtown TC")).toBeInTheDocument();
    expect(
      screen.getByText("events-card:UPCOMING:CLB001:Upcoming events"),
    ).toBeInTheDocument();
  });

  it("hides the New Event form from a signed-in non-manager (#780)", () => {
    useGetApiV1UsersMe.mockReturnValue({ data: { capabilities: ["PLAYER"] } });
    useGetApiV1ClubsCodeCode.mockReturnValue({ data: club, isLoading: false });
    renderAt();

    expect(screen.queryByText(/new-event-form/)).not.toBeInTheDocument();
  });

  it("hides the New Event form on a deleted club (#325, #780)", () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { capabilities: ["PLAYER", "ADMINISTRATOR"] },
    });
    useGetApiV1ClubsCodeCode.mockReturnValue({
      data: { ...club, isActive: false },
      isLoading: false,
    });
    renderAt();

    // A deleted club is kept for reference only — nothing new gets filed under it.
    expect(screen.queryByText(/new-event-form/)).not.toBeInTheDocument();
  });

  it("flags a soft-deleted club but still renders it (#325)", () => {
    useGetApiV1ClubsCodeCode.mockReturnValue({
      data: { ...club, isActive: false },
      isLoading: false,
    });
    renderAt();
    expect(screen.getByText("Downtown TC")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent(
      /this club has been deleted/i,
    );
  });

  it("shows no deleted flag for an active club (#325)", () => {
    useGetApiV1ClubsCodeCode.mockReturnValue({
      data: { ...club, isActive: true },
      isLoading: false,
    });
    renderAt();
    expect(
      screen.queryByText(/this club has been deleted/i),
    ).not.toBeInTheDocument();
  });
});
