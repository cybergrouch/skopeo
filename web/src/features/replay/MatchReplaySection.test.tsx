import { describe, it, expect, beforeEach, vi } from "vitest";
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
});
