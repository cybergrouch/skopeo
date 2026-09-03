import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { EventPage } from "./EventPage";

const { toastSuccess, toastError } = vi.hoisted(() => ({
  toastSuccess: vi.fn(),
  toastError: vi.fn(),
}));
vi.mock("sonner", () => ({
  toast: { success: toastSuccess, error: toastError },
}));

const { useGetApiV1EventsCodeCode, signupMutate, state } = vi.hoisted(() => ({
  useGetApiV1EventsCodeCode: vi.fn(),
  signupMutate: vi.fn(),
  state: {
    signupFail: false,
    signupPending: false,
    user: { uid: "u1" } as { uid: string } | null,
    // The viewer's capabilities (#741): a plain player by default, so these cases exercise the
    // read-only composition. EventManagerView has its own suite.
    capabilities: ["PLAYER"] as string[],
    // The organizer payload, resolved only for a match manager (#741).
    managedId: undefined as string | undefined,
  },
}));
// JoinCard + PublicPageNav read auth (#193); default to a logged-in user, overridden per test.
vi.mock("@/auth/useAuth", () => ({ useAuth: () => ({ user: state.user }) }));
// /me drives the capability gate on the unified event page (#741).
vi.mock("@/features/event/EventManagerView", () => ({
  EventManagerView: ({ eventId }: { eventId: string }) => (
    <div>organizer:{eventId}</div>
  ),
}));
vi.mock("@/api/generated/users/users", () => ({
  useGetApiV1UsersMe: () => ({ data: { capabilities: state.capabilities } }),
}));
vi.mock("@/api/generated/events/events", () => ({
  useGetApiV1EventsCodeCode,
  // The manager payload is only fetched for a match manager; a plain viewer never resolves one.
  useGetApiV1EventsCodeCodeManage: () => ({
    data: state.managedId ? { id: state.managedId } : undefined,
  }),
  // No awards by default (#857), so the points card renders nothing and this file keeps asserting the
  // page composition it always did. EventPointsCard.test.tsx covers the card itself.
  useGetApiV1EventsCodeCodePoints: () => ({
    data: { rows: [], totalPoints: "0.0000" },
    isLoading: false,
  }),
  getGetApiV1EventsCodeCodeQueryKey: (code: string) => ["event", code],
  usePostApiV1EventsCodeCodeSignup: (opts?: {
    mutation?: { onSuccess?: () => void; onError?: () => void };
  }) => ({
    isPending: state.signupPending,
    mutate: (vars: unknown) => {
      signupMutate(vars);
      if (state.signupFail) opts?.mutation?.onError?.();
      else opts?.mutation?.onSuccess?.();
    },
  }),
}));

const event = {
  publicCode: "EVT001",
  name: "Spring Open",
  startDate: "2026-03-01",
  endDate: "2026-03-03",
  participants: [
    { userId: "u1", displayName: "Ana", publicCode: "AAA111" },
    { userId: "abcdef120000", displayName: null, publicCode: null }, // both null → not a link
  ],
  matches: [
    {
      publicCode: "MTCH01",
      matchFormat: "SINGLES",
      matchType: "OPEN_PLAY",
      matchDate: "2026-03-02",
      status: "COMPLETED",
      rated: true, // committed by the calculation → "Rated"
      team1: [{ displayName: "Ana", publicCode: "AAA111" }],
      team2: [{ displayName: "Bob", publicCode: "BBB222" }],
      winner: "TEAM1",
      sets: [{ setNumber: 1, team1Games: 6, team2Games: 4 }],
    },
    {
      publicCode: "MTCH02",
      matchFormat: "SINGLES",
      matchType: "OPEN_PLAY",
      matchDate: "2026-03-02",
      status: "COMPLETED",
      rated: false, // recorded but not yet rated → "Awaiting rating"
      team1: [{ displayName: "Ana", publicCode: "AAA111" }],
      team2: [{ displayName: "Bob", publicCode: "BBB222" }],
      winner: "TEAM2",
      sets: [{ setNumber: 1, team1Games: 3, team2Games: 6 }],
    },
    {
      // No winner yet and no sets — exercises the "not played" branches.
      publicCode: "MTCH03",
      matchFormat: "SINGLES",
      matchType: "OPEN_PLAY",
      matchDate: "2026-03-03",
      status: "SCHEDULED",
      rated: false, // no result → "Scheduled"
      team1: [{ displayName: "Ana", publicCode: "AAA111" }],
      team2: [{ displayName: "Bob", publicCode: "BBB222" }],
      winner: "NONE",
      sets: [],
    },
  ],
};

function renderAt(code = "EVT001") {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter initialEntries={[`/events/${code}`]}>
        <Routes>
          <Route path="/events/:code" element={<EventPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("EventPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.signupFail = false;
    state.signupPending = false;
    state.user = { uid: "u1" }; // logged in by default
    state.capabilities = ["PLAYER"];
    state.managedId = undefined;
  });

  it("renders the organizer surface in place of the read-only one for a match manager (#741)", () => {
    state.capabilities = ["PLAYER", "HOST"];
    state.managedId = "e1";
    useGetApiV1EventsCodeCode.mockReturnValue({ data: event });
    renderAt();

    // One route, two compositions: the manager gets the organizer surface here, not a second page.
    expect(screen.getByText("organizer:e1")).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Request to join" }),
    ).not.toBeInTheDocument();
  });

  it("keeps the read-only composition for a plain player (#741)", () => {
    useGetApiV1EventsCodeCode.mockReturnValue({ data: event });
    renderAt();

    expect(screen.queryByText(/^organizer:/)).not.toBeInTheDocument();
    expect(screen.getByText("Spring Open")).toBeInTheDocument();
  });

  it("shows the event class and a Finalized badge once finalized (#741)", () => {
    useGetApiV1EventsCodeCode.mockReturnValue({
      data: { ...event, type: "TOURNAMENT", isFinalized: true },
    });
    renderAt();

    expect(screen.getByText(/Tournament/)).toBeInTheDocument();
    expect(screen.getByTestId("finalized-badge")).toBeInTheDocument();
  });

  it("drops the class separator when the payload carries no event class (#741)", () => {
    useGetApiV1EventsCodeCode.mockReturnValue({
      data: { ...event, type: undefined },
    });
    renderAt();

    // No leading " · " before the date range when there's no class to label it with.
    expect(screen.getByText(/^2026-03-01 – 2026-03-03/)).toBeInTheDocument();
  });

  it("withholds Request to join on a finalized event, explaining why (#741)", () => {
    useGetApiV1EventsCodeCode.mockReturnValue({
      data: { ...event, isFinalized: true },
    });
    renderAt();

    expect(
      screen.queryByRole("button", { name: "Request to join" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByText("This event is closed to new participants."),
    ).toBeInTheDocument();
  });

  it("withholds Request to join on a deleted event (#741)", () => {
    useGetApiV1EventsCodeCode.mockReturnValue({
      data: { ...event, isActive: false },
    });
    renderAt();

    expect(
      screen.queryByRole("button", { name: "Request to join" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByText("This event is closed to new participants."),
    ).toBeInTheDocument();
  });

  it("shows a loading state", () => {
    useGetApiV1EventsCodeCode.mockReturnValue({
      data: undefined,
      isLoading: true,
    });
    renderAt();
    expect(screen.getByText("Loading event…")).toBeInTheDocument();
  });

  it("shows an error state", () => {
    useGetApiV1EventsCodeCode.mockReturnValue({
      data: undefined,
      isError: true,
    });
    renderAt();
    expect(
      screen.getByText(/couldn’t find or load this event/i),
    ).toBeInTheDocument();
  });

  it("renders details, participant + match links, and a share QR", () => {
    useGetApiV1EventsCodeCode.mockReturnValue({
      data: event,
      isLoading: false,
    });
    renderAt();

    expect(screen.getByText("Spring Open")).toBeInTheDocument();
    expect(screen.getByText("EVT001")).toBeInTheDocument();
    // Participant with a code links to their profile; the both-null one is plain text.
    const participantLink = screen.getByRole("link", { name: "Ana" });
    expect(participantLink).toHaveAttribute("href", "/players/AAA111");
    // The participant name link wears the themed content-link style (#451).
    expect(participantLink).toHaveClass("content-link");
    expect(screen.getByText("abcdef12")).toBeInTheDocument();
    expect(
      screen.queryByRole("link", { name: "abcdef12" }),
    ).not.toBeInTheDocument();
    // Each match row links to its public match page (ordering is asserted per-section below).
    const matchLinks = screen.getAllByRole("link", { name: /Ana vs Bob/ });
    expect(matchLinks.map((l) => l.getAttribute("href")).sort()).toEqual([
      "/matches/MTCH01",
      "/matches/MTCH02",
      "/matches/MTCH03",
    ]);
    // Share card.
    expect(screen.getByText("Share this event")).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Copy link" }),
    ).toBeInTheDocument();
  });

  it("shows the organizing club, and omits it when clubless (#313)", () => {
    useGetApiV1EventsCodeCode.mockReturnValue({
      data: { ...event, clubName: "Downtown TC" },
      isLoading: false,
    });
    const { unmount } = renderAt();
    expect(screen.getByText("Club")).toBeInTheDocument();
    expect(screen.getByText("Downtown TC")).toBeInTheDocument();
    unmount();

    // A clubless event shows no Club section.
    useGetApiV1EventsCodeCode.mockReturnValue({
      data: { ...event, clubName: null },
      isLoading: false,
    });
    renderAt();
    expect(screen.queryByText("Club")).not.toBeInTheDocument();
  });

  it("splits matches into read-only Awaiting and Recorded sections (#321)", () => {
    useGetApiV1EventsCodeCode.mockReturnValue({
      data: event,
      isLoading: false,
    });
    renderAt();

    // Both section headings render...
    const awaiting = screen.getByText("Awaiting results")
      .parentElement as HTMLElement;
    const recorded = screen.getByText("Recorded results")
      .parentElement as HTMLElement;
    // ...the scheduled (no-sets) fixture is under Awaiting...
    expect(
      within(awaiting).getByRole("link", { name: /Ana vs Bob/ }),
    ).toHaveAttribute("href", "/matches/MTCH03");
    // ...and the two played fixtures under Recorded.
    const recordedLinks = within(recorded).getAllByRole("link", {
      name: /Ana vs Bob/,
    });
    expect(recordedLinks.map((l) => l.getAttribute("href"))).toEqual([
      "/matches/MTCH01",
      "/matches/MTCH02",
    ]);
    // The public page has no result-entry controls (read-only).
    expect(
      screen.queryByRole("button", {
        name: /Record result|Save result|Edit result/,
      }),
    ).not.toBeInTheDocument();
  });

  it("renders a status badge per fixture and no data-entry controls (#361)", () => {
    useGetApiV1EventsCodeCode.mockReturnValue({
      data: event,
      isLoading: false,
    });
    renderAt();

    // The three statuses are derived read-only: rated → Rated, completed-unrated → Awaiting rating,
    // no result → Scheduled.
    expect(screen.getByText("Rated")).toBeInTheDocument();
    expect(screen.getByText("Awaiting rating")).toBeInTheDocument();
    expect(screen.getByText("Scheduled")).toBeInTheDocument();

    // Strictly read-only (#361): no result upload, scheduling, reorder, or delete affordances.
    expect(
      screen.queryByRole("button", {
        name: /Record result|Save result|Edit result|Delete fixture|Add set/i,
      }),
    ).not.toBeInTheDocument();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /Reorder match/i }),
    ).not.toBeInTheDocument();
  });

  it("flags a soft-deleted event but still renders it (#325)", () => {
    useGetApiV1EventsCodeCode.mockReturnValue({
      data: { ...event, isActive: false },
      isLoading: false,
    });
    renderAt();
    expect(screen.getByText("Spring Open")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent(
      /this event has been deleted/i,
    );
  });

  it("shows no deleted flag for an active event (#325)", () => {
    useGetApiV1EventsCodeCode.mockReturnValue({
      data: { ...event, isActive: true },
      isLoading: false,
    });
    renderAt();
    expect(
      screen.queryByText(/this event has been deleted/i),
    ).not.toBeInTheDocument();
  });

  it("shows empty states for an event with no participants or matches", () => {
    useGetApiV1EventsCodeCode.mockReturnValue({
      data: { ...event, participants: [], matches: [] },
      isLoading: false,
    });
    renderAt();
    expect(screen.getByText("No participants yet.")).toBeInTheDocument();
    expect(
      screen.getByText("No fixtures awaiting results."),
    ).toBeInTheDocument();
    expect(screen.getByText("No recorded results yet.")).toBeInTheDocument();
  });

  it("offers Request to join and signs up when the viewer has no status (#201)", async () => {
    useGetApiV1EventsCodeCode.mockReturnValue({
      data: { ...event, viewerStatus: null },
      isLoading: false,
    });
    const user = userEvent.setup();
    renderAt();

    await user.click(screen.getByRole("button", { name: "Request to join" }));
    expect(signupMutate).toHaveBeenCalledWith({ code: "EVT001" });
  });

  it("prompts an anonymous viewer to log in / sign up instead of joining (#193)", () => {
    state.user = null;
    useGetApiV1EventsCodeCode.mockReturnValue({
      data: { ...event, viewerStatus: null },
      isLoading: false,
    });
    renderAt();

    expect(
      screen.queryByRole("button", { name: "Request to join" }),
    ).not.toBeInTheDocument();
    // The join prompt (unique to the JoinCard) links to login/signup; the page CTA also shows "Log in".
    expect(screen.getByText(/to request to join/i)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "sign up" })).toHaveAttribute(
      "href",
      "/signup",
    );
  });

  it("shows the pending state instead of a join button once requested (#201)", () => {
    useGetApiV1EventsCodeCode.mockReturnValue({
      data: { ...event, viewerStatus: "PENDING" },
      isLoading: false,
    });
    renderAt();
    expect(
      screen.getByText(/pending the host’s approval/i),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Request to join" }),
    ).not.toBeInTheDocument();
  });

  it("shows the approved and on-hold states (#201)", () => {
    useGetApiV1EventsCodeCode.mockReturnValue({
      data: { ...event, viewerStatus: "APPROVED" },
      isLoading: false,
    });
    const { unmount } = renderAt();
    expect(screen.getByText(/confirmed for this event/i)).toBeInTheDocument();
    unmount();

    useGetApiV1EventsCodeCode.mockReturnValue({
      data: { ...event, viewerStatus: "HOLD" },
      isLoading: false,
    });
    renderAt();
    expect(screen.getByText(/on hold/i)).toBeInTheDocument();
  });

  it("shows an error when signing up fails, and a busy label while in flight (#201)", () => {
    // In-flight: the button is disabled and reads "Requesting…".
    state.signupPending = true;
    useGetApiV1EventsCodeCode.mockReturnValue({
      data: { ...event, viewerStatus: null },
      isLoading: false,
    });
    const { unmount } = renderAt();
    expect(screen.getByRole("button", { name: "Requesting…" })).toBeDisabled();
    unmount();

    // Failure: clicking surfaces the error message.
    state.signupPending = false;
    state.signupFail = true;
    renderAt();
    const user = userEvent.setup();
    return user
      .click(screen.getByRole("button", { name: "Request to join" }))
      .then(() =>
        waitFor(() =>
          expect(toastError).toHaveBeenCalledWith(
            expect.stringMatching(/Could not sign up/i),
            expect.anything(),
          ),
        ),
      );
  });
});
