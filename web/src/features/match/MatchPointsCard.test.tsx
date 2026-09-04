import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { MatchPointsCard } from "./MatchPointsCard";

const { usePoints } = vi.hoisted(() => ({ usePoints: vi.fn() }));
vi.mock("@/api/generated/matches/matches", () => ({
  useGetApiV1MatchesCodeCodePoints: usePoints,
}));

function renderCard() {
  return render(
    <MemoryRouter>
      <MatchPointsCard code="MTCH01" />
    </MemoryRouter>,
  );
}

const row = (overrides: Record<string, unknown> = {}) => ({
  userId: "u1",
  displayName: "Ana",
  publicCode: "AAA111",
  points: "8.0000",
  awardId: "a1",
  pointClass: "OPEN_PLAY",
  isPlaceholder: false,
  isDeleted: false,
  ...overrides,
});

describe("MatchPointsCard", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    usePoints.mockReturnValue({ data: undefined, isLoading: false });
  });

  it("renders nothing while loading or on a failed request", () => {
    const { container } = renderCard();
    // No shell, no spinner: a card that might turn out to be empty should not flash into existence.
    expect(container).toBeEmptyDOMElement();
  });

  it("names a placing distinctly from a per-set payout", () => {
    usePoints.mockReturnValue({
      data: {
        rows: [
          row({ points: "1000.0000", pointClass: "ANNUAL_TOURNAMENT" }),
          row({ userId: "u2", awardId: "a2", displayName: "Bob", points: "7.0000" }),
        ],
        totalPoints: "1007.0000",
      },
      isLoading: false,
    });
    renderCard();

    // 1000 beside 7 is the case this label exists for (#836/#837) — unlabelled it reads as a bug.
    expect(screen.getByText("tournament placing")).toBeInTheDocument();
    expect(screen.getByText("per set")).toBeInTheDocument();
    expect(screen.getByText("+1000")).toBeInTheDocument();
    expect(screen.getByText(/\+1007/)).toBeInTheDocument();
  });

  it("falls back to the raw point class when the backend adds one the UI does not know", () => {
    usePoints.mockReturnValue({
      data: { rows: [row({ pointClass: "LEAGUE_FINALS" })], totalPoints: "8.0000" },
      isLoading: false,
    });
    renderCard();

    expect(screen.getByText("LEAGUE_FINALS")).toBeInTheDocument();
  });

  it("renders a row with neither a public code nor a point class", () => {
    usePoints.mockReturnValue({
      data: {
        rows: [row({ publicCode: null, displayName: null, pointClass: null, awardId: null })],
        totalPoints: "8.0000",
      },
      isLoading: false,
    });
    renderCard();

    // An unlinkable, unnamed recipient still gets a row — the award is real either way.
    expect(screen.getByText("u1")).toBeInTheDocument();
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });

  it("explains an award when the server sent a derivation, and says so when it cannot", () => {
    usePoints.mockReturnValue({
      data: {
        rows: [
          row({
            derivation: {
              awardId: "a1",
              points: "8.0000",
              pointClass: "OPEN_PLAY",
              scheduleVersion: 1,
              recorded: true,
              teamBand: "4.0",
              opponentBand: "3.5",
              sets: [
                {
                  setNumber: 1,
                  score: "6-4",
                  margin: 2,
                  relation: "FAVORITE",
                  wonSet: true,
                  winnerPoints: 8,
                  loserPoints: 1,
                  pointsForThisPlayer: 8,
                },
              ],
            },
          }),
          row({
            userId: "u2",
            awardId: "a2",
            displayName: "Bob",
            points: "1.0000",
            derivation: {
              awardId: "a2",
              points: "1.0000",
              pointClass: "OPEN_PLAY",
              scheduleVersion: 1,
              recorded: false,
              unavailableReason: "This award predates the change that records how amounts are derived.",
              sets: [],
            },
          }),
        ],
        totalPoints: "9.0000",
      },
      isLoading: false,
    });
    renderCard();

    expect(screen.getByText(/Set 1: 6-4/)).toBeInTheDocument();
    expect(screen.getByText(/favourite won/)).toBeInTheDocument();
    // The unexplainable one states the gap instead of substituting current rates (#862) — and does not
    // repeat the amount, which the row above it already states.
    expect(screen.getByText(/predates the change/)).toBeInTheDocument();
    expect(screen.queryByText("+1 points")).not.toBeInTheDocument();
  });

  it("omits the derivation entirely for a reader the server did not send one to", () => {
    usePoints.mockReturnValue({ data: { rows: [row()], totalPoints: "8.0000" }, isLoading: false });
    renderCard();

    // No client-side capability check lives here: the field's absence IS the gate (#583/#654), so there
    // is nothing in this component that can fall out of step with the server's rule.
    // Two: the row's own amount and the card total, which is one award here.
    expect(screen.getAllByText("+8")).toHaveLength(2);
    expect(screen.queryByText(/Set 1/)).not.toBeInTheDocument();
    expect(screen.queryByText(/margin/)).not.toBeInTheDocument();
  });

  it("falls back to raw figures rather than rendering nothing", () => {
    // A degenerate payload: amounts that will not parse. Showing them raw is a worse-looking card but a
    // truthful one; "null" beside a player's name would be neither.
    usePoints.mockReturnValue({
      data: { rows: [row({ points: "" })], totalPoints: "" },
      isLoading: false,
    });
    renderCard();

    expect(screen.getByText("Ranking points awarded")).toBeInTheDocument();
    expect(screen.getByText("Ana")).toBeInTheDocument();
  });
});
