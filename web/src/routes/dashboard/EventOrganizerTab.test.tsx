import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { EventOrganizerTab } from "./EventOrganizerTab";

const {
  useGetApiV1Events,
  useGetApiV1Clubs,
  useGetApiV1PointsPolicies,
  useGetApiV1UsersMe,
  createMutate,
  state,
} = vi.hoisted(() => ({
  useGetApiV1Events: vi.fn(),
  useGetApiV1Clubs: vi.fn(),
  useGetApiV1PointsPolicies: vi.fn(),
  useGetApiV1UsersMe: vi.fn(),
  createMutate: vi.fn(),
  state: { fail: false },
}));

vi.mock("@/api/generated/events/events", () => ({
  useGetApiV1Events,
  getGetApiV1EventsQueryKey: () => ["events"],
  usePostApiV1Events: (opts?: { mutation?: { onSuccess?: () => void } }) => ({
    isPending: false,
    mutate: (vars: unknown, handlers?: { onError?: () => void }) => {
      createMutate(vars);
      if (state.fail) handlers?.onError?.();
      else opts?.mutation?.onSuccess?.();
    },
  }),
}));
vi.mock("@/api/generated/clubs/clubs", () => ({ useGetApiV1Clubs }));
vi.mock("@/api/generated/points-budget/points-budget", () => ({
  useGetApiV1PointsPolicies,
}));
vi.mock("@/api/generated/users/users", () => ({ useGetApiV1UsersMe }));
vi.mock("@/components/UserSearchSelect", () => ({
  UserSearchSelect: ({
    placeholder,
    onSelect,
  }: {
    placeholder?: string;
    onSelect: (u: {
      id: string;
      publicCode: string;
      displayName: string;
    }) => void;
  }) => (
    <button
      type="button"
      onClick={() =>
        onSelect({ id: "u1", publicCode: "AAA111", displayName: "Ana" })
      }
    >
      {placeholder}
    </button>
  ),
}));
vi.mock("./events/EventDetail", () => ({
  EventDetail: ({
    eventId,
    onBack,
  }: {
    eventId: string;
    onBack: () => void;
  }) => (
    <div>
      detail:{eventId}
      <button type="button" onClick={onBack}>
        back
      </button>
    </div>
  ),
}));

function renderTab() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <EventOrganizerTab />
    </QueryClientProvider>,
  );
}

const event = {
  id: "e1",
  publicCode: "EV1",
  name: "Spring Open",
  startDate: "2026-03-01",
  endDate: "2026-03-03",
  isActive: true,
  participants: [{ userId: "u1", displayName: "Ana", publicCode: "AAA111" }],
  creatorDisplayName: "Hank",
  creatorPublicCode: "HHH999",
};

describe("EventOrganizerTab", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.fail = false;
    useGetApiV1Events.mockReturnValue({ data: [], isLoading: false });
    useGetApiV1Clubs.mockReturnValue({ data: [], isLoading: false });
    // Default global policies (#429): LEAGUE 5..50/90d, TOURNAMENT 10..500/365d, mirroring V16 seeds.
    useGetApiV1PointsPolicies.mockReturnValue({
      data: [
        { eventType: "LEAGUE", minPoints: 5, maxPoints: 50, maxValidityDays: 90 },
        {
          eventType: "TOURNAMENT",
          minPoints: 10,
          maxPoints: 500,
          maxValidityDays: 365,
        },
      ],
    });
    // Default: a plain user with no CLUB_OWNER capability (so the #364 default never fires).
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: "me", capabilities: ["HOST"] },
    });
  });

  it("shows loading and empty states", () => {
    useGetApiV1Events.mockReturnValue({ data: undefined, isLoading: true });
    const { rerender } = renderTab();
    expect(screen.getByText("Loading…")).toBeInTheDocument();

    useGetApiV1Events.mockReturnValue({ data: [], isLoading: false });
    rerender(
      <QueryClientProvider client={new QueryClient()}>
        <EventOrganizerTab />
      </QueryClientProvider>,
    );
    expect(screen.getByText(/No events yet/)).toBeInTheDocument();
  });

  it("lists events and opens the detail when a row is clicked", async () => {
    const twoPlayers = {
      ...event,
      id: "e2",
      name: "Doubles Day",
      participants: [
        { userId: "u1", displayName: "Ana", publicCode: "AAA111" },
        { userId: "u2", displayName: "Bob", publicCode: "BBB222" },
      ],
    };
    useGetApiV1Events.mockReturnValue({
      data: [event, twoPlayers],
      isLoading: false,
    });
    const user = userEvent.setup();
    renderTab();
    // Both rows render — a singular (1 player) and a plural (2 players) participant count (#307).
    expect(screen.getByText("Spring Open")).toBeInTheDocument();
    expect(screen.getByText("Doubles Day")).toBeInTheDocument();
    expect(screen.getByText(/· 1 player$/)).toBeInTheDocument();
    expect(screen.getByText(/· 2 players$/)).toBeInTheDocument();
    // Regression guard: never the mis-pluralized "playeres" (#307).
    expect(screen.queryByText(/playeres/)).not.toBeInTheDocument();

    await user.click(screen.getByText("Spring Open"));
    expect(screen.getByText("detail:e1")).toBeInTheDocument();
    // Going back returns to the list.
    await user.click(screen.getByRole("button", { name: "back" }));
    expect(screen.getByText("Events")).toBeInTheDocument();
  });

  it("shows the filing host on each event card, omitting it when unknown (#270)", () => {
    const orphan = {
      ...event,
      id: "e3",
      name: "Orphan Cup",
      creatorDisplayName: null,
      creatorPublicCode: null,
    };
    useGetApiV1Events.mockReturnValue({
      data: [event, orphan],
      isLoading: false,
    });
    renderTab();

    expect(screen.getByText("Filed by Hank")).toBeInTheDocument();
    // The creator-less event renders no "Filed by" line — only the one with a known creator does.
    expect(screen.getAllByText(/Filed by/)).toHaveLength(1);
  });

  it("splits events into Upcoming and Past subsections by end date (#271)", () => {
    const upcoming = {
      ...event,
      id: "up",
      name: "Future Fest",
      startDate: "2999-01-01",
      endDate: "2999-01-02",
    };
    const past = {
      ...event,
      id: "pa",
      name: "Old Open",
      startDate: "2000-01-01",
      endDate: "2000-01-02",
    };
    useGetApiV1Events.mockReturnValue({
      data: [past, upcoming],
      isLoading: false,
    });
    renderTab();

    expect(screen.getByText("Upcoming")).toBeInTheDocument();
    expect(screen.getByText("Past")).toBeInTheDocument();
    expect(screen.getByText("Future Fest")).toBeInTheDocument();
    expect(screen.getByText("Old Open")).toBeInTheDocument();
    // No per-section empty state when both sections have events.
    expect(screen.queryByText("No upcoming events.")).not.toBeInTheDocument();
    expect(screen.queryByText("No past events.")).not.toBeInTheDocument();
  });

  it("shows a per-section empty state when a section has no events (#271)", () => {
    const past = {
      ...event,
      id: "pa",
      name: "Old Open",
      startDate: "2000-01-01",
      endDate: "2000-01-02",
    };
    useGetApiV1Events.mockReturnValue({ data: [past], isLoading: false });
    renderTab();

    expect(screen.getByText("No upcoming events.")).toBeInTheDocument();
    expect(screen.getByText("Old Open")).toBeInTheDocument();
    // The Past section has the event, so no "No past events." message.
    expect(screen.queryByText("No past events.")).not.toBeInTheDocument();
  });

  it("shows only the start date for upcoming and only the end date for past events (#296)", () => {
    const upcoming = {
      ...event,
      id: "up",
      name: "Future Fest",
      startDate: "2999-01-01",
      endDate: "2999-01-02",
    };
    const past = {
      ...event,
      id: "pa",
      name: "Old Open",
      startDate: "2000-01-01",
      endDate: "2000-01-02",
    };
    useGetApiV1Events.mockReturnValue({
      data: [past, upcoming],
      isLoading: false,
    });
    renderTab();

    // Upcoming: start date shown, end date hidden.
    expect(screen.getByText(/Starts 2999-01-01/)).toBeInTheDocument();
    expect(screen.queryByText(/2999-01-02/)).not.toBeInTheDocument();
    // Past: end date shown, start date hidden.
    expect(screen.getByText(/Ended 2000-01-02/)).toBeInTheDocument();
    expect(screen.queryByText(/2000-01-01/)).not.toBeInTheDocument();
  });

  it("creates an event with a roster", async () => {
    const user = userEvent.setup();
    renderTab();

    // Disabled until name + both dates are filled.
    expect(screen.getByRole("button", { name: "Create event" })).toBeDisabled();
    await user.type(screen.getByLabelText("Name"), "Summer Open");
    await user.type(screen.getByLabelText("Start date"), "2026-06-01");
    await user.type(screen.getByLabelText("End date"), "2026-06-02");
    await user.click(
      screen.getByRole("button", { name: "Search players to add…" }),
    );
    // The chosen player shows as a removable chip.
    expect(screen.getByRole("button", { name: /Ana ✕/ })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Create event" }));
    expect(createMutate).toHaveBeenCalledWith({
      data: {
        name: "Summer Open",
        startDate: "2026-06-01",
        endDate: "2026-06-02",
        type: "OPEN_PLAY",
        participantIds: ["u1"],
      },
    });
  });

  it("includes the chosen event type in the create payload (#403)", async () => {
    const user = userEvent.setup();
    renderTab();
    await user.type(screen.getByLabelText("Name"), "League Night");
    await user.type(screen.getByLabelText("Start date"), "2026-06-01");
    await user.type(screen.getByLabelText("End date"), "2026-06-02");
    // The type selector defaults to Open play; switch it to League.
    await user.selectOptions(screen.getByLabelText("Type"), "LEAGUE");
    await user.click(screen.getByRole("button", { name: "Create event" }));

    expect(createMutate).toHaveBeenCalledWith({
      data: {
        name: "League Night",
        startDate: "2026-06-01",
        endDate: "2026-06-02",
        type: "LEAGUE",
        participantIds: [],
      },
    });
  });

  it("shows an error when event creation fails", async () => {
    state.fail = true;
    const user = userEvent.setup();
    renderTab();
    await user.type(screen.getByLabelText("Name"), "Summer Open");
    await user.type(screen.getByLabelText("Start date"), "2026-06-01");
    await user.type(screen.getByLabelText("End date"), "2026-06-02");
    await user.click(screen.getByRole("button", { name: "Create event" }));
    expect(screen.getByText(/Could not create the event/)).toBeInTheDocument();
  });

  it('groups events by club (alphabetically) with a clubless "Open" group last (#313)', () => {
    const bravo = {
      ...event,
      id: "b1",
      name: "Bravo Cup",
      clubId: "c2",
      clubName: "Bravo TC",
    };
    // Two upcoming Alpha events (distinct start dates) exercise the "reuse existing club group" path
    // and the upcoming date-sort within a group.
    const alpha = {
      ...event,
      id: "a1",
      name: "Alpha Cup",
      clubId: "c1",
      clubName: "Alpha TC",
      startDate: "2999-02-01",
      endDate: "2999-02-05",
    };
    const alpha2 = {
      ...event,
      id: "a2",
      name: "Alpha Cup Two",
      clubId: "c1",
      clubName: "Alpha TC",
      startDate: "2999-01-01",
      endDate: "2999-02-05",
    };
    const openEvent = { ...event, id: "o1", name: "Casual Meetup" }; // no club
    // Deliberately unsorted input so the club sort (named alphabetical, Open last) runs its branches.
    useGetApiV1Events.mockReturnValue({
      data: [openEvent, bravo, alpha, alpha2],
      isLoading: false,
    });
    renderTab();

    // Two club group headers (with per-club counts, #367) + the clubless "Open" group.
    expect(screen.getByText("Alpha TC (2)")).toBeInTheDocument();
    expect(screen.getByText("Bravo TC (1)")).toBeInTheDocument();
    expect(screen.getByText("Open (1)")).toBeInTheDocument();
    expect(screen.getByText("Alpha Cup")).toBeInTheDocument();
    expect(screen.getByText("Alpha Cup Two")).toBeInTheDocument();
    expect(screen.getByText("Bravo Cup")).toBeInTheDocument();
    expect(screen.getByText("Casual Meetup")).toBeInTheDocument();

    // Group order: named clubs alphabetical, then "Open".
    const headers = screen
      .getAllByText(/^(Alpha TC|Bravo TC|Open) \(\d+\)$/)
      .map((el) => el.textContent);
    expect(headers).toEqual(["Alpha TC (2)", "Bravo TC (1)", "Open (1)"]);
  });

  it("offers a club dropdown and files the event under the selected club (#313)", async () => {
    useGetApiV1Clubs.mockReturnValue({
      data: [{ id: "c1", name: "Downtown TC", isActive: true, owners: [] }],
      isLoading: false,
    });
    const user = userEvent.setup();
    renderTab();
    await user.type(screen.getByLabelText("Name"), "Club Cup");
    await user.type(screen.getByLabelText("Start date"), "2026-06-01");
    await user.type(screen.getByLabelText("End date"), "2026-06-02");
    await user.selectOptions(screen.getByLabelText("Club"), "c1");
    await user.click(screen.getByRole("button", { name: "Create event" }));

    expect(createMutate).toHaveBeenCalledWith({
      data: {
        name: "Club Cup",
        startDate: "2026-06-01",
        endDate: "2026-06-02",
        type: "OPEN_PLAY",
        participantIds: [],
        clubId: "c1",
      },
    });
  });

  it("hides the club dropdown when there are no clubs (#313)", () => {
    // data undefined (not yet loaded) resolves to no clubs → no dropdown.
    useGetApiV1Clubs.mockReturnValue({ data: undefined, isLoading: false });
    renderTab();
    expect(screen.queryByLabelText("Club")).not.toBeInTheDocument();
  });

  it("de-duplicates and removes a staged participant before creating", async () => {
    const user = userEvent.setup();
    renderTab();
    // Selecting the same player twice adds them once (de-duplicated).
    await user.click(
      screen.getByRole("button", { name: "Search players to add…" }),
    );
    await user.click(
      screen.getByRole("button", { name: "Search players to add…" }),
    );
    expect(screen.getByRole("button", { name: /Ana ✕/ })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /Ana ✕/ }));
    expect(
      screen.queryByRole("button", { name: /Ana ✕/ }),
    ).not.toBeInTheDocument();
  });

  it("pre-selects a CLUB_OWNER's single owned club, still allowing a change or clear (#364)", async () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: "me", capabilities: ["CLUB_OWNER"] },
    });
    useGetApiV1Clubs.mockReturnValue({
      data: [
        {
          id: "c1",
          name: "Downtown TC",
          isActive: true,
          owners: [{ userId: "me", publicCode: "MEE000" }],
        },
        { id: "c2", name: "Uptown TC", isActive: true, owners: [] },
      ],
      isLoading: false,
    });
    const user = userEvent.setup();
    renderTab();

    // The owned club is pre-selected rather than defaulting to "Open".
    const select = screen.getByLabelText("Club") as HTMLSelectElement;
    expect(select.value).toBe("c1");

    // Editable: the owner can switch to another club…
    await user.selectOptions(select, "c2");
    expect(select.value).toBe("c2");
    // …and clear it back to "Open".
    await user.selectOptions(select, "");
    expect(select.value).toBe("");
  });

  it("does not default the club when a CLUB_OWNER owns multiple clubs (#364)", () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: "me", capabilities: ["CLUB_OWNER"] },
    });
    useGetApiV1Clubs.mockReturnValue({
      data: [
        {
          id: "c1",
          name: "Downtown TC",
          isActive: true,
          owners: [{ userId: "me", publicCode: "MEE000" }],
        },
        {
          id: "c2",
          name: "Uptown TC",
          isActive: true,
          owners: [{ userId: "me", publicCode: "MEE000" }],
        },
      ],
      isLoading: false,
    });
    renderTab();
    // Ambiguous ownership → no guess; the selector stays on "Open".
    expect((screen.getByLabelText("Club") as HTMLSelectElement).value).toBe("");
  });

  it("does not default the club for a non-owner even if they own it in data (#364)", () => {
    // Plain HOST (the beforeEach default) — the club owns the user, but no CLUB_OWNER capability.
    useGetApiV1Clubs.mockReturnValue({
      data: [
        {
          id: "c1",
          name: "Downtown TC",
          isActive: true,
          owners: [{ userId: "me", publicCode: "MEE000" }],
        },
      ],
      isLoading: false,
    });
    renderTab();
    expect((screen.getByLabelText("Club") as HTMLSelectElement).value).toBe("");
  });

  it("collapses and expands a club group, showing a per-club count (#367)", async () => {
    const alpha = {
      ...event,
      id: "a1",
      name: "Alpha Cup",
      clubId: "c1",
      clubName: "Alpha TC",
    };
    useGetApiV1Events.mockReturnValue({ data: [alpha], isLoading: false });
    const user = userEvent.setup();
    renderTab();

    // The header shows the club name with its event count and is expanded by default.
    const toggle = screen.getByRole("button", { name: /Alpha TC \(1\)/ });
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByText("Alpha Cup")).toBeInTheDocument();

    // Collapsing hides the event rows…
    await user.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByText("Alpha Cup")).not.toBeInTheDocument();

    // …and re-expanding shows them again.
    await user.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByText("Alpha Cup")).toBeInTheDocument();
  });

  // --- Points config on create (#429) ---

  it("shows and requires points config for a budgeted event with a club, and sends it (#429)", async () => {
    useGetApiV1Clubs.mockReturnValue({
      data: [{ id: "c1", name: "Downtown TC", isActive: true, owners: [] }],
      isLoading: false,
    });
    const user = userEvent.setup();
    renderTab();
    await user.type(screen.getByLabelText("Name"), "League Night");
    await user.type(screen.getByLabelText("Start date"), "2026-06-01");
    await user.type(screen.getByLabelText("End date"), "2026-06-02");
    await user.selectOptions(screen.getByLabelText("Type"), "LEAGUE");
    await user.selectOptions(screen.getByLabelText("Club"), "c1");

    // The points-config fields appear (hidden until budgeted type + club); the global hint shows.
    expect(screen.getByLabelText("Min points")).toBeInTheDocument();
    expect(
      screen.getByText(/global League policy allows 5–50 points/),
    ).toBeInTheDocument();
    // Required until filled: the submit stays disabled with the fields blank.
    expect(
      screen.getByRole("button", { name: "Create event" }),
    ).toBeDisabled();

    await user.type(screen.getByLabelText("Min points"), "10");
    await user.type(screen.getByLabelText("Max points"), "40");
    await user.type(screen.getByLabelText("Validity start"), "2026-06-01");
    await user.type(screen.getByLabelText("Validity end"), "2026-08-01");
    await user.click(screen.getByRole("button", { name: "Create event" }));

    expect(createMutate).toHaveBeenCalledWith({
      data: {
        name: "League Night",
        startDate: "2026-06-01",
        endDate: "2026-06-02",
        type: "LEAGUE",
        participantIds: [],
        clubId: "c1",
        minPointsPerMatch: 10,
        maxPointsPerMatch: 40,
        pointValidityStart: "2026-06-01",
        pointValidityEnd: "2026-08-01",
      },
    });
  });

  it("hides points config for OPEN_PLAY or a clubless budgeted event, creating without it (#429)", async () => {
    useGetApiV1Clubs.mockReturnValue({
      data: [{ id: "c1", name: "Downtown TC", isActive: true, owners: [] }],
      isLoading: false,
    });
    const user = userEvent.setup();
    renderTab();
    await user.type(screen.getByLabelText("Name"), "Casual Meetup");
    await user.type(screen.getByLabelText("Start date"), "2026-06-01");
    await user.type(screen.getByLabelText("End date"), "2026-06-02");

    // OPEN_PLAY (the default) with a club → no points fields.
    await user.selectOptions(screen.getByLabelText("Club"), "c1");
    expect(screen.queryByLabelText("Min points")).not.toBeInTheDocument();

    // A budgeted type WITHOUT a club → still no points fields (deferred; settable later).
    await user.selectOptions(screen.getByLabelText("Type"), "TOURNAMENT");
    await user.selectOptions(screen.getByLabelText("Club"), "");
    expect(screen.queryByLabelText("Min points")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Create event" }));
    // Created without any points fields.
    expect(createMutate).toHaveBeenCalledWith({
      data: {
        name: "Casual Meetup",
        startDate: "2026-06-01",
        endDate: "2026-06-02",
        type: "TOURNAMENT",
        participantIds: [],
      },
    });
  });

  it("flags points values outside the global policy bounds (#429)", async () => {
    useGetApiV1Clubs.mockReturnValue({
      data: [{ id: "c1", name: "Downtown TC", isActive: true, owners: [] }],
      isLoading: false,
    });
    const user = userEvent.setup();
    renderTab();
    await user.type(screen.getByLabelText("Name"), "League Night");
    await user.type(screen.getByLabelText("Start date"), "2026-06-01");
    await user.type(screen.getByLabelText("End date"), "2026-06-02");
    await user.selectOptions(screen.getByLabelText("Type"), "LEAGUE");
    await user.selectOptions(screen.getByLabelText("Club"), "c1");

    // Min below LEAGUE's global minimum of 5 → the create button stays disabled (invalid window).
    await user.type(screen.getByLabelText("Min points"), "1");
    await user.type(screen.getByLabelText("Max points"), "40");
    await user.type(screen.getByLabelText("Validity start"), "2026-06-01");
    await user.type(screen.getByLabelText("Validity end"), "2026-08-01");
    expect(
      screen.getByRole("button", { name: "Create event" }),
    ).toBeDisabled();
    expect(createMutate).not.toHaveBeenCalled();

    // Correcting the min to a within-bounds value re-enables and sends it.
    await user.clear(screen.getByLabelText("Min points"));
    await user.type(screen.getByLabelText("Min points"), "10");
    await user.click(screen.getByRole("button", { name: "Create event" }));
    expect(createMutate).toHaveBeenCalledTimes(1);
  });
});
