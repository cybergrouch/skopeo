import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { EventOrganizerTab } from "./EventOrganizerTab";

const { useGetApiV1Clubs, useGetApiV1Events } = vi.hoisted(() => ({
  useGetApiV1Clubs: vi.fn(),
  // Kept only so the test can assert it is NEVER called (#794) — the tab must not query events.
  useGetApiV1Events: vi.fn(),
}));
vi.mock("@/api/generated/clubs/clubs", () => ({ useGetApiV1Clubs }));
vi.mock("@/api/generated/events/events", () => ({
  useGetApiV1Events,
  getGetApiV1EventsQueryKey: () => ["events"],
  usePostApiV1Events: () => ({ isPending: false, mutate: vi.fn() }),
}));
// The create form is exercised in its own test; here we only assert the tab renders it.
vi.mock("@/features/event/NewEventForm", () => ({
  NewEventForm: () => <div>new-event-form</div>,
}));

const clubs = [
  {
    id: "c1",
    name: "Downtown TC",
    publicCode: "CLB001",
    isActive: true,
    owners: [],
  },
  {
    id: "c2",
    name: "West End",
    publicCode: "CLB002",
    isActive: true,
    owners: [],
  },
];

function renderTab() {
  return render(
    <MemoryRouter>
      <QueryClientProvider client={new QueryClient()}>
        <EventOrganizerTab />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe("EventOrganizerTab (#794)", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useGetApiV1Clubs.mockReturnValue({ data: clubs, isLoading: false });
  });

  it("never queries events — the list it used to render is gone", () => {
    renderTab();

    // The whole point of #794: the slowest screen in the dashboard stopped asking for every event.
    // Asserting on the hook rather than on absent markup, so a reintroduced query can't slip back in.
    expect(useGetApiV1Events).not.toHaveBeenCalled();
  });

  it("lists clubs, each linking to that club's public page", () => {
    renderTab();

    expect(screen.getByRole("link", { name: "Downtown TC" })).toHaveAttribute(
      "href",
      "/clubs/CLB001",
    );
    expect(screen.getByRole("link", { name: "West End" })).toHaveAttribute(
      "href",
      "/clubs/CLB002",
    );
    // The shareable code is shown alongside, as it is elsewhere.
    expect(screen.getByText("CLB001")).toBeInTheDocument();
  });

  it("keeps the create form, so an admin can file under any club from here", () => {
    renderTab();
    expect(screen.getByText("new-event-form")).toBeInTheDocument();
  });

  it("shows a loading state while the clubs resolve", () => {
    useGetApiV1Clubs.mockReturnValue({ data: undefined, isLoading: true });
    renderTab();
    expect(screen.getByText("Loading clubs…")).toBeInTheDocument();
  });

  it("shows an error state when the clubs cannot be loaded", () => {
    useGetApiV1Clubs.mockReturnValue({ data: undefined, isError: true });
    renderTab();
    expect(screen.getByText(/couldn’t load the clubs/i)).toBeInTheDocument();
  });

  it("points at Club Management when there are no clubs yet", () => {
    useGetApiV1Clubs.mockReturnValue({ data: [], isLoading: false });
    renderTab();

    // A club is required to create an event now (#794), so an empty list is a dead end without this.
    expect(screen.getByText(/No clubs yet/)).toBeInTheDocument();
  });
});
