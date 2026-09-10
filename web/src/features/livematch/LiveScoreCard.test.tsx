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

  it("marks WHICH player retired, next to their name (#951)", () => {
    // Ana is not the winner, so Ana is the one who retired — derived, because the document
    // deliberately does not carry the conceding side (#933).
    useLiveScore.mockReturnValue({
      ...score,
      outcomeKind: "RETIRED",
      outcomeWinner: "TEAM2",
    });
    renderCard();

    expect(screen.getByText("(Retired)")).toBeInTheDocument();
    // On the player, not in the badge: a badge saying "retired" leaves the reader to work out who by
    // reasoning backwards from the winner.
    expect(screen.getByText("Final")).toBeInTheDocument();
    expect(screen.queryByText("Final — retired")).not.toBeInTheDocument();
  });

  it("puts the marker on the loser even when the winner is the other side (#951)", () => {
    // The assertion that catches an inverted derivation. Same test as above with the sides swapped —
    // marking the WINNER as retired is the failure this guards, and it is invisible with one fixture.
    useLiveScore.mockReturnValue({
      ...score,
      outcomeKind: "RETIRED",
      outcomeWinner: "TEAM1",
    });
    const { container } = renderCard();

    const rows = container.querySelectorAll("div.flex.items-baseline");
    expect(rows[0].textContent).toContain("Ana");
    expect(rows[0].textContent).not.toContain("(Retired)");
    expect(rows[1].textContent).toContain("Bob");
    expect(rows[1].textContent).toContain("(Retired)");
  });

  it("marks a default the same way, with its own wording (#951)", () => {
    useLiveScore.mockReturnValue({
      ...score,
      outcomeKind: "DEFAULTED",
      outcomeWinner: "TEAM1",
    });
    renderCard();
    expect(screen.getByText("(Default)")).toBeInTheDocument();
    expect(screen.getByText("Final")).toBeInTheDocument();
  });

  it("marks nobody when the match was played out", () => {
    useLiveScore.mockReturnValue({
      ...score,
      outcomeKind: "COMPLETED",
      outcomeWinner: "TEAM1",
    });
    renderCard();
    expect(screen.queryByText("(Retired)")).not.toBeInTheDocument();
    expect(screen.queryByText("(Default)")).not.toBeInTheDocument();
  });

  it("falls back to Final for an outcome it does not recognise", () => {
    // Forward-compatibility: a new completion reason on the server must not blank the badge, and must
    // not invent a marker for a state this build has never heard of.
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

  it("shows who is serving while the match is live (#943)", () => {
    useLiveScore.mockReturnValue({ ...score, serverName: "Ana" });
    renderCard();
    expect(screen.getByText(/Ana to serve/)).toBeInTheDocument();
  });

  it("stops showing a server once the match has ended (#943)", () => {
    // "to serve" on a finished match describes something that is not going to happen.
    useLiveScore.mockReturnValue({
      ...score,
      serverName: "Ana",
      outcomeKind: "COMPLETED",
      outcomeWinner: "TEAM1",
    });
    renderCard();
    expect(screen.queryByText(/to serve/)).not.toBeInTheDocument();
  });

  it("says nothing when no server has been set", () => {
    // The normal state until an umpire sets one; an empty "to serve" line would read as a fault.
    useLiveScore.mockReturnValue({ ...score, serverName: null });
    renderCard();
    expect(screen.queryByText(/to serve/)).not.toBeInTheDocument();
  });
});
