import { describe, it, expect, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";
import type { ReplayResponse } from "@/api/generated/model";
import { RETIRED_REPLAY } from "@/test/replayFixtures";
import { ReplayCard } from "./ReplayCard";
import { ReplaySwimlaneChart } from "./ReplaySwimlaneChart";
import { hasReplayFeature } from "./replayFeatures";
import { scorerOf } from "./replayScorers";

/** What an entitled viewer (#1183) receives: every step names its scorer, and the codes are resolved. */
const ENTITLED: ReplayResponse = {
  ...RETIRED_REPLAY,
  points: RETIRED_REPLAY.points.map((point, index) => ({
    ...point,
    scorer: index < 4 ? "UMP001" : "UMP002",
  })),
  scorers: {
    UMP001: { displayName: "Uma", isPlaceholder: false, isDeleted: false },
    UMP002: { displayName: "Vic", isPlaceholder: true, isDeleted: false },
  },
};

const stepAt = (container: HTMLElement, index: number) =>
  container.querySelector(`[data-testid="swimlane-step"][data-index="${index}"]`) as Element;

describe("scorerOf", () => {
  it("names a step's scorer from the resolved map", () => {
    expect(scorerOf(ENTITLED, 0)).toEqual({ label: "Uma", isPlaceholder: false, isDeleted: false });
    expect(scorerOf(ENTITLED, 5)).toEqual({ label: "Vic", isPlaceholder: true, isDeleted: false });
  });

  it("never names a deleted scorer", () => {
    const replay = { ...ENTITLED, scorers: { ...ENTITLED.scorers, UMP001: { displayName: "Uma", isDeleted: true } } };
    expect(scorerOf(replay, 0)).toEqual({ label: "a deleted account", isPlaceholder: false, isDeleted: true });
  });

  it("falls back for a code that no longer resolves, or a scorer with no name", () => {
    expect(scorerOf({ ...ENTITLED, scorers: {} }, 0)?.label).toBe("Unknown scorer");
    expect(scorerOf({ ...ENTITLED, scorers: { UMP001: { displayName: null } } }, 0)?.label).toBe("Unknown scorer");
  });

  it("says nothing when the step carries no scorer", () => {
    expect(scorerOf(RETIRED_REPLAY, 0)).toBeNull();
    expect(scorerOf(ENTITLED, 999)).toBeNull();
  });
});

describe("the scorers feature", () => {
  it("is offered only when the steps carry scorer codes, which the server sends only to the entitled", () => {
    expect(hasReplayFeature(ENTITLED, "scorers")).toBe(true);
    expect(hasReplayFeature(RETIRED_REPLAY, "scorers")).toBe(false);
  });
});

describe("the per-point scorer popover", () => {
  function renderCard(replay: ReplayResponse, onSelect = vi.fn()) {
    render(<ReplayCard replay={replay} selected={0} onSelect={onSelect} team1Name="Ana" team2Name="Bob" />);
    return onSelect;
  }

  it("opens from a timeline badge, naming that point's scorer, and still selects it", () => {
    const onSelect = renderCard(ENTITLED);
    const badge = screen.getByRole("button", { name: /^Point 2:/ });

    fireEvent.click(badge);

    expect(onSelect).toHaveBeenCalledWith(1);
    const popover = screen.getByTestId("replay-scorer-popover");
    expect(popover).toHaveTextContent("Scored by Uma");
  });

  it("leaves the badges as they were for a viewer sent no scorers", () => {
    const onSelect = renderCard(RETIRED_REPLAY);
    const badge = screen.getByRole("button", { name: /^Point 2:/ });

    fireEvent.click(badge);

    expect(onSelect).toHaveBeenCalledWith(1);
    expect(badge).not.toHaveAttribute("aria-haspopup");
    expect(screen.queryByTestId("replay-scorer-popover")).not.toBeInTheDocument();
  });

  it("opens from a swimlane mark, naming that point's scorer", () => {
    const onSelect = vi.fn();
    const { container } = render(
      <ReplaySwimlaneChart replay={ENTITLED} selected={0} onSelect={onSelect} team1Name="Ana" team2Name="Bob" />,
    );

    fireEvent.click(stepAt(container, 5));

    expect(onSelect).toHaveBeenCalledWith(5);
    const popover = screen.getByTestId("replay-scorer-popover");
    expect(popover).toHaveTextContent("Scored by Vic");
    expect(within(popover).getByText("Unclaimed")).toBeInTheDocument();
    expect(popover).toHaveTextContent(/^Point 6:/);
  });

  it("opens no popover from a swimlane mark for a viewer sent no scorers", () => {
    const onSelect = vi.fn();
    const { container } = render(
      <ReplaySwimlaneChart replay={RETIRED_REPLAY} selected={0} onSelect={onSelect} team1Name="Ana" team2Name="Bob" />,
    );

    fireEvent.click(stepAt(container, 5));

    expect(onSelect).toHaveBeenCalledWith(5);
    expect(screen.queryByTestId("replay-scorer-popover")).not.toBeInTheDocument();
  });
});
