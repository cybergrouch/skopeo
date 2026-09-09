import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { LiveScoreCard } from "./LiveScoreCard";

const { useLiveScore } = vi.hoisted(() => ({ useLiveScore: vi.fn() }));
vi.mock("./useLiveScore", () => ({ useLiveScore }));

const score = {
  publicCode: "MTCH01",
  sequence: 12,
  pointsTeam1: "40",
  pointsTeam2: "30",
  gamesTeam1: 4,
  gamesTeam2: 3,
  sets: [],
  isTiebreak: false,
  isPaused: false,
  hasStarted: true,
  serverId: null,
  outcomeKind: null,
  outcomeWinner: null,
};

function renderCard() {
  return render(
    <LiveScoreCard publicCode="MTCH01" team1Name="Ana" team2Name="Bob" />,
  );
}

describe("LiveScoreCard", () => {
  beforeEach(() => vi.clearAllMocks());

  it("renders nothing when no match is being scored", () => {
    // The normal state for almost every fixture. An empty scoreboard would read as a fault.
    useLiveScore.mockReturnValue(null);
    const { container } = renderCard();
    expect(container).toBeEmptyDOMElement();
  });

  it("shows the live score with both sides", () => {
    useLiveScore.mockReturnValue(score);
    renderCard();

    expect(screen.getByText("Live")).toBeInTheDocument();
    expect(screen.getByText("Ana")).toBeInTheDocument();
    expect(screen.getByText("Bob")).toBeInTheDocument();
    // Rendered by the server, never computed here — so this view cannot disagree with the umpire's.
    expect(screen.getByText("40")).toBeInTheDocument();
    expect(screen.getByText("30")).toBeInTheDocument();
  });

  it("shows a suspension rather than pretending play continues", () => {
    useLiveScore.mockReturnValue({ ...score, isPaused: true });
    renderCard();
    expect(screen.getByText("Suspended")).toBeInTheDocument();
  });

  it("distinguishes a match that has not started from one in play", () => {
    useLiveScore.mockReturnValue({ ...score, hasStarted: false });
    renderCard();
    expect(screen.getByText("About to start")).toBeInTheDocument();
  });

  it("badges a tiebreak, since the points stop reading as 15/30/40", () => {
    useLiveScore.mockReturnValue({
      ...score,
      isTiebreak: true,
      pointsTeam1: "5",
      pointsTeam2: "3",
    });
    renderCard();
    expect(screen.getByText("Tiebreak")).toBeInTheDocument();
  });

  it("shows the winner once the match is finalized (#911)", () => {
    useLiveScore.mockReturnValue({
      ...score,
      outcomeKind: "COMPLETED",
      outcomeWinner: "TEAM1",
    });
    renderCard();
    expect(screen.getByText("Final")).toBeInTheDocument();
    expect(screen.getByText("This match has finished.")).toBeInTheDocument();
  });

  it("says how a match ended when it was not played out", () => {
    useLiveScore.mockReturnValue({
      ...score,
      outcomeKind: "RETIRED",
      outcomeWinner: "TEAM2",
    });
    renderCard();
    // A bare "Final" over a 1-3 scoreline would misrepresent what happened.
    expect(screen.getByText("Final — retired")).toBeInTheDocument();
  });

  it("falls back to Final for an outcome it does not recognise", () => {
    // Forward-compatibility: a new completion reason on the server must not blank the badge.
    useLiveScore.mockReturnValue({
      ...score,
      outcomeKind: "SOMETHING_NEW",
      outcomeWinner: "TEAM1",
    });
    renderCard();
    expect(screen.getByText("Final")).toBeInTheDocument();
  });

  it("lists banked sets", () => {
    useLiveScore.mockReturnValue({
      ...score,
      sets: [
        { gamesTeam1: 6, gamesTeam2: 4, tiebreakTeam1Points: null, tiebreakTeam2Points: null },
        { gamesTeam1: 3, gamesTeam2: 6, tiebreakTeam1Points: null, tiebreakTeam2Points: null },
      ],
    });
    renderCard();
    expect(screen.getByText("6-4, 3-6")).toBeInTheDocument();
  });

  it("hides the tiebreak badge once the match is over", () => {
    useLiveScore.mockReturnValue({
      ...score,
      isTiebreak: true,
      outcomeKind: "COMPLETED",
      outcomeWinner: "TEAM1",
    });
    renderCard();
    expect(screen.queryByText("Tiebreak")).not.toBeInTheDocument();
  });
});
