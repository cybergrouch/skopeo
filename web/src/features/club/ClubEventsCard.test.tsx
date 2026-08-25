import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { ClubEventsCard } from "./ClubEventsCard";

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

function renderCard(
  bucket: "UPCOMING" | "UNFINALIZED" | "FINALIZED" = "UPCOMING",
) {
  return render(
    <MemoryRouter>
      <ClubEventsCard
        code="CLB001"
        bucket={bucket}
        title="Upcoming events"
        emptyLabel="No upcoming events."
      />
    </MemoryRouter>,
  );
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
});
