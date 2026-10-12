import { afterEach, describe, it, expect, beforeEach, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { RETIRED_REPLAY } from "@/test/replayFixtures";
import { MatchReplaySection } from "./MatchReplaySection";

const { useGetApiV1MatchesCodeCodeReplay } = vi.hoisted(() => ({
  useGetApiV1MatchesCodeCodeReplay: vi.fn(),
}));
vi.mock("@/api/generated/matches/matches", () => ({
  useGetApiV1MatchesCodeCodeReplay,
}));

const LAST = RETIRED_REPLAY.points.length;

function renderSection() {
  return render(
    <MatchReplaySection code="MTCH01" team1Name="Ana" team2Name="Bob" />,
  );
}

const announcement = () => screen.getByTestId("replay-announcement").textContent;

describe("MatchReplaySection", () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("asks for this match's replay, once — a 404 is an answer, not a fault to retry", () => {
    useGetApiV1MatchesCodeCodeReplay.mockReturnValue({ data: undefined });
    renderSection();
    expect(useGetApiV1MatchesCodeCodeReplay).toHaveBeenCalledWith("MTCH01", {
      query: { enabled: true, retry: false },
    });
  });

  it("renders nothing while there is no replay (loading, never live-scored, or edited away)", () => {
    useGetApiV1MatchesCodeCodeReplay.mockReturnValue({ data: undefined, isError: true });
    const { container } = renderSection();
    expect(container).toBeEmptyDOMElement();
  });

  it("opens on the final score, and keeps the selection as the visitor steps", () => {
    useGetApiV1MatchesCodeCodeReplay.mockReturnValue({ data: RETIRED_REPLAY });
    renderSection();

    expect(screen.getByRole("region", { name: "Match replay" })).toBeInTheDocument();
    expect(announcement()).toContain(`Point ${LAST} of ${LAST}`);

    fireEvent.click(screen.getByText("Previous", { selector: "button" }));
    expect(announcement()).toContain(`Point ${LAST - 1} of ${LAST}`);
    fireEvent.click(screen.getByText("First", { selector: "button" }));
    expect(announcement()).toContain("Start of the match");
  });

  it("draws no chart when there is no replay", () => {
    useGetApiV1MatchesCodeCodeReplay.mockReturnValue({ data: undefined, isError: true });
    const { container } = renderSection();
    expect(container.querySelector('[data-testid^="swimlane-set-"]')).toBeNull();
    expect(container.querySelector('[data-testid="momentum-share"]')).toBeNull();
  });

  it("moves the card when a point is picked in either chart", () => {
    useGetApiV1MatchesCodeCodeReplay.mockReturnValue({ data: RETIRED_REPLAY });
    const { container } = renderSection();
    // The replay opens on the final score, in set 3; step into set 1 so its swimlane block is open.
    fireEvent.click(screen.getByText("First", { selector: "button" }));
    fireEvent.click(screen.getByText("Next", { selector: "button" }));

    fireEvent.click(
      container.querySelector('[data-testid="swimlane-step"][data-index="4"]') as Element,
    );
    expect(announcement()).toContain(`Point 5 of ${LAST}`);

    // The momentum chart's eighth hit target is the eighth point played: position 7.
    fireEvent.click(container.querySelectorAll('[data-testid="momentum-hit"]')[7]);
    expect(announcement()).toContain(`Point 8 of ${LAST}`);
  });

  it("moves both charts when the card steps", () => {
    useGetApiV1MatchesCodeCodeReplay.mockReturnValue({ data: RETIRED_REPLAY });
    const { container } = renderSection();
    const selectedStep = () =>
      container.querySelector('[data-testid="swimlane-step"][data-selected]')?.getAttribute("data-index");
    // Time runs downward (#1156), so the momentum marker moves down as the selection advances.
    const markerY = () =>
      Number(container.querySelector('[data-testid="momentum-selected"]')?.getAttribute("y1"));

    fireEvent.click(screen.getByText("First", { selector: "button" }));
    expect(selectedStep()).toBeUndefined();
    const atStart = markerY();

    fireEvent.click(screen.getByText("Next", { selector: "button" }));
    expect(selectedStep()).toBe("0");
    expect(markerY()).toBeGreaterThan(atStart);
    expect(screen.getByText(/^After point 1 \(set 1\)/)).toBeInTheDocument();
  });

  it("lays both charts out vertically at every width, and keeps them on the card's selection (#1156)", () => {
    useGetApiV1MatchesCodeCodeReplay.mockReturnValue({ data: RETIRED_REPLAY });
    const { container } = renderSection();
    const selectedStep = () =>
      container.querySelector('[data-testid="swimlane-step"][data-selected]')?.getAttribute("data-index");

    expect(container.querySelector('[data-testid="swimlane-vertical"]')).not.toBeNull();
    expect(container.querySelector('svg[data-orientation="vertical"]')).not.toBeNull();
    expect(container.querySelector('[data-orientation="horizontal"]')).toBeNull();
    // Opens on the final step, in set 3: only set 3's swimlane block is open.
    expect(selectedStep()).toBe("12");

    // Stepping back to the start opens set 1 in the swimlanes, and the momentum chart follows.
    fireEvent.click(screen.getByText("First", { selector: "button" }));
    fireEvent.click(screen.getByText("Next", { selector: "button" }));
    expect(selectedStep()).toBe("0");
    expect(screen.getByText(/^After point 1 \(set 1\)/)).toBeInTheDocument();

    // A point picked in the vertical swimlane moves the card.
    fireEvent.click(
      container.querySelector('[data-testid="swimlane-step"][data-index="4"]') as Element,
    );
    expect(announcement()).toContain(`Point 5 of ${LAST}`);
  });
});
