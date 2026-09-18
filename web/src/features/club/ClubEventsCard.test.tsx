import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {
  MemoryRouter,
  useLocation,
  useNavigationType,
} from "react-router-dom";
import { ClubEventsCard } from "./ClubEventsCard";
import { BUCKETS } from "@/features/club/eventBuckets";

const { useGetApiV1ClubsCodeCodeEvents } = vi.hoisted(() => ({
  useGetApiV1ClubsCodeCodeEvents: vi.fn(),
}));
vi.mock("@/api/generated/clubs/clubs", () => ({
  useGetApiV1ClubsCodeCodeEvents,
}));

function event(n: number) {
  return {
    publicCode: `EVT${n}`,
    name: `Event ${n}`,
    startDate: "2999-05-01",
    endDate: "2999-05-03",
    eventType: "OPEN_PLAY",
    isFinalized: false,
    completedMatchCount: 0,
  };
}

/** Surfaces the query string the card pages itself through (#1056), and how it got there. */
function UrlProbe() {
  const location = useLocation();
  const navigationType = useNavigationType();
  return (
    <>
      <div data-testid="url">{decodeURIComponent(location.search)}</div>
      <div data-testid="nav-type">{navigationType}</div>
    </>
  );
}

function renderCard(
  bucket: "UPCOMING" | "UNFINALIZED" | "FINALIZED" = "UPCOMING",
  entry = "/clubs/CLB001",
) {
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <ClubEventsCard
        code="CLB001"
        bucket={bucket}
        title="Upcoming events"
        emptyLabel="No upcoming events."
      />
      <UrlProbe />
    </MemoryRouter>,
  );
}

/** All three cards at once, exactly as ClubPage composes them. */
function renderClubPageCards(entry = "/clubs/CLB001") {
  return render(
    <MemoryRouter initialEntries={[entry]}>
      {BUCKETS.map((b) => (
        <ClubEventsCard
          key={b.bucket}
          code="CLB001"
          bucket={b.bucket}
          title={b.title}
          emptyLabel={b.emptyLabel}
        />
      ))}
      <UrlProbe />
    </MemoryRouter>,
  );
}

/** The offsets every card asked for, in bucket order — one per rendered card. */
function requestedOffsets() {
  const byBucket = new Map<string, number>();
  for (const [, params] of useGetApiV1ClubsCodeCodeEvents.mock.calls) {
    byBucket.set(params.bucket, params.offset);
  }
  return BUCKETS.map((b) => byBucket.get(b.bucket));
}

describe("ClubEventsCard (#786)", () => {
  beforeEach(() => vi.clearAllMocks());

  it("asks the server for the first page of its own bucket", () => {
    useGetApiV1ClubsCodeCodeEvents.mockReturnValue({
      data: { bucket: "UPCOMING", items: [event(1)], total: 1 },
      isLoading: false,
    });
    renderCard();

    // Ten at a time, scoped to this bucket — the whole point of the split.
    expect(useGetApiV1ClubsCodeCodeEvents).toHaveBeenCalledWith("CLB001", {
      bucket: "UPCOMING",
      limit: 10,
      offset: 0,
    });
    expect(
      screen.getByRole("link", { name: /Event 1/ }),
    ).toHaveAttribute("href", "/events/EVT1");
  });

  it("pages by asking for the next offset, not by slicing locally", async () => {
    const items = Array.from({ length: 10 }, (_, i) => event(i + 1));
    useGetApiV1ClubsCodeCodeEvents.mockReturnValue({
      data: { bucket: "UPCOMING", items, total: 23 },
      isLoading: false,
    });
    const user = userEvent.setup();
    renderCard();

    // total is the bucket's size, so the pager can offer more pages than this response holds.
    expect(screen.getByText(/Showing 1–10 of 23/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Next" }));

    expect(useGetApiV1ClubsCodeCodeEvents).toHaveBeenLastCalledWith("CLB001", {
      bucket: "UPCOMING",
      limit: 10,
      offset: 10,
    });
  });

  it("shows the bucket's total as a subtitle, pluralized", () => {
    useGetApiV1ClubsCodeCodeEvents.mockReturnValue({
      data: { bucket: "UPCOMING", items: [event(1)], total: 1 },
      isLoading: false,
    });
    renderCard();
    expect(screen.getByText("1 event")).toBeInTheDocument();
  });

  it("shows the empty label and no pager for an empty bucket", () => {
    useGetApiV1ClubsCodeCodeEvents.mockReturnValue({
      data: { bucket: "FINALIZED", items: [], total: 0 },
      isLoading: false,
    });
    renderCard("FINALIZED");

    expect(screen.getByText("No upcoming events.")).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Next" }),
    ).not.toBeInTheDocument();
  });

  it("shows a loading state while its own page is in flight", () => {
    useGetApiV1ClubsCodeCodeEvents.mockReturnValue({
      data: undefined,
      isLoading: true,
    });
    renderCard();
    expect(screen.getByText("Loading…")).toBeInTheDocument();
  });

  it("shows an error state without taking down the rest of the page", () => {
    useGetApiV1ClubsCodeCodeEvents.mockReturnValue({
      data: undefined,
      isError: true,
    });
    renderCard();
    expect(screen.getByText(/couldn’t load these events/i)).toBeInTheDocument();
  });

  it("shows the end date for a non-upcoming bucket (#296)", () => {
    useGetApiV1ClubsCodeCodeEvents.mockReturnValue({
      data: { bucket: "FINALIZED", items: [event(1)], total: 1 },
      isLoading: false,
    });
    renderCard("FINALIZED");
    expect(screen.getByText("Ended 2999-05-03")).toBeInTheDocument();
    expect(screen.queryByText(/^Starts /)).not.toBeInTheDocument();
  });

  describe("page in the URL (#1056)", () => {
    const TEN = Array.from({ length: 10 }, (_, i) => event(i + 1));

    beforeEach(() => {
      useGetApiV1ClubsCodeCodeEvents.mockReturnValue({
        data: { bucket: "UPCOMING", items: TEN, total: 40 },
        isLoading: false,
      });
    });

    it("opens on the page its own namespaced param names", () => {
      renderCard("UPCOMING", "/clubs/CLB001?upcoming.page=3");
      // Reload-proof: the third page is requested straight away, with no click.
      expect(useGetApiV1ClubsCodeCodeEvents).toHaveBeenLastCalledWith("CLB001", {
        bucket: "UPCOMING",
        limit: 10,
        offset: 20,
      });
      expect(screen.getByText(/Showing 21–30 of 40/)).toBeInTheDocument();
    });

    it("writes its page under its namespace, by replace", async () => {
      const user = userEvent.setup();
      renderCard("FINALIZED");
      await user.click(screen.getByRole("button", { name: "Next" }));
      expect(screen.getByTestId("url")).toHaveTextContent("?finalized.page=2");
      // A page step is not its own Back step (#323).
      expect(screen.getByTestId("nav-type")).toHaveTextContent("REPLACE");
    });

    it("pages one of the three cards without paging the other two", async () => {
      const user = userEvent.setup();
      renderClubPageCards();
      // The first "Next" belongs to the Upcoming card, which is rendered first.
      await user.click(screen.getAllByRole("button", { name: "Next" })[0]);

      // The whole reason the hook namespaces: a shared ?page= would move all three.
      expect(requestedOffsets()).toEqual([10, 0, 0]);
      expect(screen.getByTestId("url")).toHaveTextContent("?upcoming.page=2");
    });

    it("restores all three pages independently from one link", () => {
      renderClubPageCards("/clubs/CLB001?upcoming.page=2&finalized.page=4");
      expect(requestedOffsets()).toEqual([10, 0, 30]);
    });

    it("says so and shows the first page when the link's page is unreadable", () => {
      renderCard("UPCOMING", "/clubs/CLB001?upcoming.page=nope");
      expect(screen.getByRole("alert")).toHaveTextContent(
        "This link had 1 unreadable view setting (upcoming.page)",
      );
      // Visible fallback, not an empty card: the first page is on screen.
      expect(screen.getByText(/Showing 1–10 of 40/)).toBeInTheDocument();
    });
  });
});
