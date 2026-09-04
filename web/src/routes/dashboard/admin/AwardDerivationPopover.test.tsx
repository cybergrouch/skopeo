import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AwardDerivationPopover } from "./AwardDerivationPopover";

const { useDerivation } = vi.hoisted(() => ({ useDerivation: vi.fn() }));
vi.mock("@/api/generated/ranking-points/ranking-points", () => ({
  useGetApiV1RankingPointsAwardIdDerivation: useDerivation,
}));

function renderPopover(points = "42.0000") {
  return render(<AwardDerivationPopover awardId="a1" points={points} />);
}

const set = (overrides: Record<string, unknown> = {}) => ({
  setNumber: 1,
  score: "6-4",
  margin: 2,
  relation: "EQUAL",
  wonSet: true,
  winnerPoints: 8,
  loserPoints: 0,
  pointsForThisPlayer: 8,
  ...overrides,
});

const derivation = (overrides: Record<string, unknown> = {}) => ({
  awardId: "a1",
  points: "42.0000",
  pointClass: "OPEN_PLAY",
  scheduleVersion: 1,
  recorded: true,
  sets: [set(), set({ setNumber: 2, score: "6-1", margin: 5, winnerPoints: 34, pointsForThisPlayer: 34 })],
  teamBand: "4.0",
  opponentBand: "4.0",
  ...overrides,
});

describe("AwardDerivationPopover", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useDerivation.mockReturnValue({ data: undefined, isLoading: false });
  });

  it("renders the amount as a focusable button, not hover-only", () => {
    renderPopover();
    // A reader has to be able to reach this by keyboard; the whole point is the number explains itself.
    const trigger = screen.getByRole("button", { name: /How these \+42 points were derived/ });
    expect(trigger).toHaveTextContent("+42");
  });

  it("does not fetch until opened", () => {
    renderPopover();
    // The ledger pages 25 rows and a reader opens one. Fetching with the table would have the server
    // assemble 25 derivations to show a single popover.
    expect(useDerivation).not.toHaveBeenCalled();
  });

  it("explains each set, and the parts visibly sum to the amount", async () => {
    useDerivation.mockReturnValue({ data: derivation(), isLoading: false });
    const user = userEvent.setup();
    renderPopover();

    await user.click(screen.getByRole("button", { name: /How these/ }));

    const lines = await screen.findAllByRole("listitem");
    expect(lines[0]).toHaveTextContent("Set 1: 6-4");
    expect(lines[0]).toHaveTextContent("margin 2");
    expect(lines[0]).toHaveTextContent("same band");
    expect(lines[0]).toHaveTextContent("+8");
    expect(lines[1]).toHaveTextContent("+34");
    // 8 + 34 = 42, the figure on the trigger.
    expect(screen.getByText(/42 points/)).toBeInTheDocument();
  });

  it("shows both sides' amounts, so a loser's points do not read as a bug", async () => {
    useDerivation.mockReturnValue({
      data: derivation({
        points: "1.0000",
        sets: [set({ relation: "FAVORITE", wonSet: false, winnerPoints: 2, loserPoints: 1, pointsForThisPlayer: 1 })],
        teamBand: "3.5",
        opponentBand: "4.0",
      }),
      isLoading: false,
    });
    const user = userEvent.setup();
    renderPopover("1.0000");

    await user.click(screen.getByRole("button", { name: /How these/ }));

    const line = await screen.findByRole("listitem");
    // "Lost the set and got +1" only makes sense next to what the winner got — the schedule paying a
    // loser at all is its least obvious property (#525).
    expect(line).toHaveTextContent("lost");
    expect(line).toHaveTextContent("winner +2, loser +1");
    expect(screen.getByText(/3\.5 vs 4\.0/)).toBeInTheDocument();
  });

  it("signs a negative amount, so -2 cannot read as 2", async () => {
    useDerivation.mockReturnValue({
      data: derivation({
        points: "-2.0000",
        sets: [set({ relation: "UPSET", wonSet: false, winnerPoints: 15, loserPoints: -2, pointsForThisPlayer: -2 })],
      }),
      isLoading: false,
    });
    const user = userEvent.setup();
    renderPopover("-2.0000");

    await user.click(screen.getByRole("button", { name: /How these/ }));

    expect(await screen.findByRole("listitem")).toHaveTextContent("loser -2");
  });

  it("explains a placement award by its placing and sanction column", async () => {
    useDerivation.mockReturnValue({
      data: derivation({
        points: "1000.0000",
        pointClass: "ANNUAL_TOURNAMENT",
        sets: [],
        teamBand: null,
        opponentBand: null,
        placement: { place: 1, sanctioned: true, scheduleAmount: 1000 },
      }),
      isLoading: false,
    });
    const user = userEvent.setup();
    renderPopover("1000.0000");

    await user.click(screen.getByRole("button", { name: /How these/ }));

    // 1000 vs 400 for the same placing is unexplainable from the number alone (#525), so the sanction
    // status has to be named.
    const body = await screen.findByText(/Placed 1st/);
    expect(body).toHaveTextContent("sanctioned");
    expect(body).toHaveTextContent("1000");
  });

  it("shows a manual grant's reason as its whole explanation", async () => {
    useDerivation.mockReturnValue({
      data: derivation({ pointClass: "EXTERNAL", sets: [], reason: "Goodwill adjustment" }),
      isLoading: false,
    });
    const user = userEvent.setup();
    renderPopover();

    await user.click(screen.getByRole("button", { name: /How these/ }));

    expect(await screen.findByText("Goodwill adjustment")).toBeInTheDocument();
  });

  it("says an unexplainable award was not recorded rather than inventing one", async () => {
    useDerivation.mockReturnValue({
      data: derivation({
        recorded: false,
        sets: [],
        unavailableReason: "This award predates the change that records how amounts are derived.",
      }),
      isLoading: false,
    });
    const user = userEvent.setup();
    renderPopover();

    await user.click(screen.getByRole("button", { name: /How these/ }));

    // The amount is still shown — the award is real, only its explanation is missing. A confident panel
    // whose numbers do not add up would be worse than the gap.
    expect(await screen.findByText(/predates the change/)).toBeInTheDocument();
    expect(screen.getByText(/42 points/)).toBeInTheDocument();
    expect(screen.queryByRole("listitem")).not.toBeInTheDocument();
  });

  it("shows the schedule version, so a reader knows which rates applied", async () => {
    useDerivation.mockReturnValue({ data: derivation({ scheduleVersion: 3 }), isLoading: false });
    const user = userEvent.setup();
    renderPopover();

    await user.click(screen.getByRole("button", { name: /How these/ }));

    expect(await screen.findByText(/schedule v3/)).toBeInTheDocument();
  });

  it("shows a loading state, then survives a failed fetch", async () => {
    useDerivation.mockReturnValue({ data: undefined, isLoading: true });
    const user = userEvent.setup();
    const { rerender } = renderPopover();
    await user.click(screen.getByRole("button", { name: /How these/ }));
    expect(await screen.findByText("Loading…")).toBeInTheDocument();

    useDerivation.mockReturnValue({ data: undefined, isLoading: false });
    rerender(<AwardDerivationPopover awardId="a1" points="42.0000" />);
    expect(await screen.findByText(/Couldn’t load the derivation/)).toBeInTheDocument();
  });

  it("names the non-sanctioned column, and ordinals past 1st", async () => {
    useDerivation.mockReturnValue({
      data: derivation({
        points: "40.0000",
        pointClass: "ANNUAL_TOURNAMENT",
        sets: [],
        placement: { place: 2, sanctioned: false, scheduleAmount: 40 },
      }),
      isLoading: false,
    });
    const user = userEvent.setup();
    renderPopover("40.0000");

    await user.click(screen.getByRole("button", { name: /How these/ }));

    // 40 vs 400 for the same placing is only explicable once the column is named (#525) — so the
    // non-sanctioned wording matters exactly as much as the sanctioned one.
    const body = await screen.findByText(/Placed 2nd/);
    expect(body).toHaveTextContent("non-sanctioned");
  });

  it("falls back to the raw relation when the backend adds one the UI does not know", async () => {
    useDerivation.mockReturnValue({
      data: derivation({ sets: [set({ relation: "HANDICAPPED" })] }),
      isLoading: false,
    });
    const user = userEvent.setup();
    renderPopover();

    await user.click(screen.getByRole("button", { name: /How these/ }));

    // A relation the UI has no phrasing for still reads as something, rather than blanking the line.
    expect(await screen.findByRole("listitem")).toHaveTextContent("handicapped");
  });

  it("still says something when an unexplainable award carries no reason", async () => {
    useDerivation.mockReturnValue({
      // Also the degenerate amount, so the unexplained panel's fallback is exercised on the path a
      // reader would actually hit it on.
      data: derivation({ recorded: false, sets: [], unavailableReason: null, points: "" }),
      isLoading: false,
    });
    const user = userEvent.setup();
    render(<AwardDerivationPopover awardId="a1" points="" />);

    await user.click(screen.getByRole("button", { name: /points were derived/ }));

    expect(await screen.findByText(/was not recorded/)).toBeInTheDocument();
  });

  it("explains a plate-final placing, which is 3rd or 4th and not 1st", async () => {
    useDerivation.mockReturnValue({
      data: derivation({
        points: "200.0000",
        pointClass: "ANNUAL_TOURNAMENT",
        sets: [],
        placement: { place: 4, sanctioned: true, scheduleAmount: 100 },
      }),
      isLoading: false,
    });
    const user = userEvent.setup();
    const { rerender } = renderPopover("200.0000");

    await user.click(screen.getByRole("button", { name: /How these/ }));

    // The plate final pays 3rd and 4th (#837); getting the ordinal wrong here would misreport a placing.
    expect(await screen.findByText(/Placed 4th/)).toBeInTheDocument();

    useDerivation.mockReturnValue({
      data: derivation({
        points: "200.0000",
        pointClass: "ANNUAL_TOURNAMENT",
        sets: [],
        placement: { place: 3, sanctioned: true, scheduleAmount: 200 },
      }),
      isLoading: false,
    });
    rerender(<AwardDerivationPopover awardId="a1" points="200.0000" />);
    expect(await screen.findByText(/Placed 3rd/)).toBeInTheDocument();
  });

  it("falls back to the raw amount rather than rendering nothing", async () => {
    // A degenerate payload: an amount that will not parse. Showing it raw is a worse-looking panel but a
    // truthful one; "null points" would be neither.
    useDerivation.mockReturnValue({ data: derivation({ points: "" }), isLoading: false });
    const user = userEvent.setup();
    render(<AwardDerivationPopover awardId="a1" points="" />);

    await user.click(screen.getByRole("button", { name: /points were derived/ }));

    expect(await screen.findAllByRole("listitem")).toHaveLength(2);
  });
});
