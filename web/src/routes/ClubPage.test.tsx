import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ClubPage } from "./ClubPage";

const { useGetApiV1ClubsCodeCode, useGetApiV1UsersMe, state } = vi.hoisted(() => ({
  useGetApiV1ClubsCodeCode: vi.fn(),
  useGetApiV1UsersMe: vi.fn(),
  state: { user: { uid: "u1" } as { uid: string } | null },
}));
// PublicPageNav reads auth (#193); default to a logged-in user, overridden per test.
vi.mock("@/auth/useAuth", () => ({ useAuth: () => ({ user: state.user }) }));
vi.mock("@/api/generated/clubs/clubs", () => ({
  useGetApiV1ClubsCodeCode,
}));
// The viewer lookup that gates the club's own New Event form (#780); anonymous by default.
vi.mock("@/api/generated/users/users", () => ({ useGetApiV1UsersMe }));
// The form is exercised in the Event Organizer's own tests; here we only assert the gating decision.
vi.mock("@/features/event/NewEventForm", () => ({
  NewEventForm: ({ fixedClubPublicCode }: { fixedClubPublicCode?: string }) => (
    <div>new-event-form:{fixedClubPublicCode}</div>
  ),
}));

const club = {
  publicCode: "CLB001",
  name: "Downtown TC",
  isActive: true,
  // One event per bucket (#780): a future untouched one, an ended one with results, and a finalized one.
  events: [
    {
      publicCode: "EVT001",
      name: "Spring Open",
      startDate: "2999-05-01",
      endDate: "2999-05-03",
      eventType: "OPEN_PLAY",
      isFinalized: false,
      completedMatchCount: 0,
    },
    {
      publicCode: "EVT002",
      name: "Autumn Meet",
      startDate: "2026-09-01",
      endDate: "2026-09-03",
      eventType: "OPEN_PLAY",
      isFinalized: false,
      completedMatchCount: 2,
    },
    {
      publicCode: "EVT000",
      name: "Winter Cup",
      startDate: "2026-01-01",
      endDate: "2026-01-03",
      eventType: "TOURNAMENT",
      isFinalized: true,
      finalizedAt: "2026-01-04T10:00:00",
      completedMatchCount: 3,
    },
  ],
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

  it("groups events as Upcoming / Unfinalized / Finalized, each linking to its page (#780)", () => {
    useGetApiV1ClubsCodeCode.mockReturnValue({ data: club, isLoading: false });
    renderAt();

    expect(screen.getByText("Downtown TC")).toBeInTheDocument();
    expect(screen.getByText("CLB001")).toBeInTheDocument();

    // The same three groupings the Event Organizer uses (#483) — not the old Upcoming/Past pair.
    const upcoming = screen.getByText("Upcoming").parentElement as HTMLElement;
    expect(
      within(upcoming).getByRole("link", { name: /Spring Open/ }),
    ).toHaveAttribute("href", "/events/EVT001");

    // Ended with recorded results → Unfinalized, not Finalized.
    const unfinalized = screen.getByText("Unfinalized")
      .parentElement as HTMLElement;
    expect(
      within(unfinalized).getByRole("link", { name: /Autumn Meet/ }),
    ).toHaveAttribute("href", "/events/EVT002");

    const finalized = screen.getByText("Finalized")
      .parentElement as HTMLElement;
    expect(
      within(finalized).getByRole("link", { name: /Winter Cup/ }),
    ).toHaveAttribute("href", "/events/EVT000");

    // Share card still points at the public view.
    expect(screen.getByText("Share this club")).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Copy link" }),
    ).toBeInTheDocument();
  });

  it("shows each event's type and the date that matters for its grouping (#296)", () => {
    useGetApiV1ClubsCodeCode.mockReturnValue({ data: club, isLoading: false });
    renderAt();

    // Upcoming shows the start date; the rest show the end date.
    const upcoming = screen.getByText("Upcoming").parentElement as HTMLElement;
    expect(within(upcoming).getByText("OPEN_PLAY")).toBeInTheDocument();
    expect(within(upcoming).getByText("Starts 2999-05-01")).toBeInTheDocument();

    const finalized = screen.getByText("Finalized")
      .parentElement as HTMLElement;
    expect(within(finalized).getByText("TOURNAMENT")).toBeInTheDocument();
    expect(within(finalized).getByText("Ended 2026-01-03")).toBeInTheDocument();

    // No designated/awarded points copy anywhere on the page (#559).
    expect(screen.queryByText(/pts designated/)).not.toBeInTheDocument();
    expect(screen.queryByText(/pts awarded/)).not.toBeInTheDocument();
  });

  it("shows a per-bucket empty state when a club has no events", () => {
    useGetApiV1ClubsCodeCode.mockReturnValue({
      data: { ...club, events: [] },
      isLoading: false,
    });
    renderAt();
    expect(screen.getByText("No upcoming events.")).toBeInTheDocument();
    expect(screen.getByText("No unfinalized events.")).toBeInTheDocument();
    expect(screen.getByText("No finalized events.")).toBeInTheDocument();
  });

  it("renders without events when the payload omits them entirely (#780)", () => {
    // The web and API deploy as separate artifacts, web first, so a freshly-deployed page can briefly
    // see an API that still returns the old upcoming/past pair. That must not white-screen the page.
    const withoutEvents: Record<string, unknown> = { ...club };
    delete withoutEvents.events;
    useGetApiV1ClubsCodeCode.mockReturnValue({
      data: withoutEvents,
      isLoading: false,
    });
    renderAt();

    expect(screen.getByText("Downtown TC")).toBeInTheDocument();
    expect(screen.getByText("No upcoming events.")).toBeInTheDocument();
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
    // The public page still renders normally.
    expect(screen.getByText("Downtown TC")).toBeInTheDocument();
    expect(screen.getByText("Upcoming")).toBeInTheDocument();
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
