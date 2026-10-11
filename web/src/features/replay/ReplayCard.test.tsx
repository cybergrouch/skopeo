import { describe, it, expect, vi } from "vitest";
import { useState } from "react";
import { fireEvent, render, screen, within } from "@testing-library/react";
import type { ReplayDocument } from "@/api/generated/model";
import {
  OLD_FORMAT_REPLAY,
  RETIRED_REPLAY,
  replayOf,
  replayPoint,
  replayScore,
} from "@/test/replayFixtures";
import { ReplayCard } from "./ReplayCard";
import { REPLAY_START } from "./replayFeatures";

const LAST = RETIRED_REPLAY.points.length - 1;

/** The card with real selection state, so stepping can be followed end to end. */
function Stateful({
  replay,
  initial,
}: {
  replay: ReplayDocument;
  initial: number;
}) {
  const [selected, setSelected] = useState(initial);
  return (
    <ReplayCard
      replay={replay}
      selected={selected}
      onSelect={setSelected}
      team1Name="Ana"
      team2Name="Bob"
    />
  );
}

function renderCard(replay = RETIRED_REPLAY, initial = LAST) {
  return render(<Stateful replay={replay} initial={initial} />);
}

const announcement = () => screen.getByTestId("replay-announcement").textContent;
/** A stepping control, found by its visible text — cheaper than a role query over every timeline button. */
const control = (name: string) => screen.getByText(name, { selector: "button" });
const side = (team: "TEAM1" | "TEAM2") =>
  screen.getByTestId(`replay-side-${team}`).textContent;

describe("ReplayCard", () => {
  it("opens on the final position, which equals the recorded result", () => {
    renderCard();

    expect(screen.getByText("Final")).toBeInTheDocument();
    // Bob conceded, so the shared mark (#987) sits on Bob — and the banked sets are the result's.
    expect(side("TEAM2")).toContain("(R)");
    expect(side("TEAM1")).not.toContain("(R)");
    expect(side("TEAM1")).toContain("6");
    expect(announcement()).toContain("Bob retired, Ana wins the match");
    expect(announcement()).toContain("Sets 6-0, 6-7 (5-7)");
    // Over: no point score is announced for a match that has ended.
    expect(announcement()).not.toContain("Points");
  });

  it("steps with Previous and Next, one point at a time", () => {
    renderCard();

    fireEvent.click(control("Previous"));
    expect(announcement()).toContain(`Point ${LAST} of ${LAST + 1}, set 3`);
    expect(announcement()).toContain("Points Ana 15, Bob 0");
    expect(screen.queryByText("Final")).not.toBeInTheDocument();

    fireEvent.click(control("Next"));
    expect(announcement()).toContain(`Point ${LAST + 1} of ${LAST + 1}`);
  });

  it("jumps to the start and back to the end with First and Last", () => {
    renderCard();

    fireEvent.click(control("First"));
    expect(announcement()).toContain("Start of the match.");
    expect(announcement()).toContain("Points Ana 0, Bob 0");
    // Ana serves the first point, as recorded on it.
    expect(announcement()).toContain("Ana to serve");
    expect(control("First")).toBeDisabled();
    expect(control("Previous")).toBeDisabled();

    fireEvent.click(control("Last"));
    expect(announcement()).toContain("Ana wins the match");
    expect(control("Next")).toBeDisabled();
    expect(control("Last")).toBeDisabled();
  });

  it("steps through every point with the arrow keys, and Home/End jump", () => {
    renderCard(RETIRED_REPLAY, REPLAY_START);
    const controls = screen.getByRole("group", { name: "Replay controls" });

    for (let i = 0; i <= LAST; i++) {
      fireEvent.keyDown(controls, { key: "ArrowRight" });
      expect(announcement()).toContain(`Point ${i + 1} of ${LAST + 1}`);
    }
    // Past the end stays at the end.
    fireEvent.keyDown(controls, { key: "ArrowRight" });
    expect(announcement()).toContain(`Point ${LAST + 1} of ${LAST + 1}`);

    fireEvent.keyDown(controls, { key: "ArrowLeft" });
    expect(announcement()).toContain(`Point ${LAST} of ${LAST + 1}`);
    fireEvent.keyDown(controls, { key: "Home" });
    expect(announcement()).toContain("Start of the match");
    fireEvent.keyDown(controls, { key: "End" });
    expect(announcement()).toContain(`Point ${LAST + 1} of ${LAST + 1}`);
  });

  it("ignores keys that are not for stepping", () => {
    const onSelect = vi.fn();
    render(
      <ReplayCard
        replay={RETIRED_REPLAY}
        selected={3}
        onSelect={onSelect}
        team1Name="Ana"
        team2Name="Bob"
      />,
    );
    fireEvent.keyDown(screen.getByRole("group", { name: "Replay controls" }), {
      key: "a",
    });
    expect(onSelect).not.toHaveBeenCalled();
  });

  it("shows the score after the selected point, with the server for the next one", () => {
    renderCard(RETIRED_REPLAY, 2);
    expect(side("TEAM1")).toContain("30");
    expect(side("TEAM2")).toContain("15");
    expect(announcement()).toContain("Games 0-0");
    expect(announcement()).toContain("Ana to serve");
    // Ana serving is marked on her row, not Bob's.
    expect(side("TEAM1")).toContain("to serve");
    expect(side("TEAM2")).not.toContain("to serve");
  });

  it("groups the timeline per set, headed by the set's result", () => {
    renderCard();
    const timeline = screen.getByRole("navigation", { name: "Replay timeline" });

    expect(within(timeline).getByRole("heading", { name: "Set 1 · 6-0" })).toBeInTheDocument();
    expect(
      within(timeline).getByRole("heading", { name: "Set 2 · 6-7 (5-7)" }),
    ).toBeInTheDocument();
    // Set 3 was cut short by the retirement, so it has no banked result to show.
    expect(within(timeline).getByRole("heading", { name: "Set 3" })).toBeInTheDocument();
  });

  it("labels point milestones by the score they produced, and games apart from points", () => {
    renderCard();
    const set1 = screen.getByRole("region", { name: "Set 1 · 6-0" });
    const labels = within(set1)
      .getAllByRole("button")
      .map((button) => button.textContent);

    expect(labels).toEqual([
      "0-0",
      "15-0",
      "15-15",
      "30-15",
      "40-15",
      "Game",
      "15-0",
      "30-0",
      "40-0",
      "Break",
      "Set",
    ]);
  });

  it("marks a break of serve distinctly from a hold", () => {
    renderCard();
    const hold = screen.getByRole("button", { name: "Point 5: Game Ana, games 1-0" });
    const brk = screen.getByRole("button", {
      name: "Point 9: Break of serve, Ana, games 2-0, break point",
    });
    expect(brk.className).not.toEqual(hold.className);
    expect(brk).toHaveClass("text-link");
  });

  it("labels tiebreak points and the tiebreak result", () => {
    renderCard();
    const set2 = screen.getByRole("region", { name: "Set 2 · 6-7 (5-7)" });

    expect(within(set2).getByText("Tiebreak", { selector: "li" })).toBeInTheDocument();
    expect(
      within(set2).getByRole("button", { name: "Point 11: Tiebreak 0-1, point Bob" }),
    ).toHaveTextContent("0-1");
    expect(
      within(set2).getByRole("button", { name: "Point 12: Set Bob, 6-7 (5-7)" }),
    ).toHaveTextContent("Tiebreak");
  });

  it("ends the timeline with the retirement", () => {
    renderCard();
    expect(
      screen.getByRole("button", {
        name: "Point 14: Bob retired, Ana wins the match",
      }),
    ).toHaveTextContent("Retired");
  });

  it("jumps on a milestone click, and marks the selected one with aria-current", () => {
    renderCard();
    const target = screen.getByRole("button", { name: "Point 3: 30-15, point Ana" });
    expect(target).not.toHaveAttribute("aria-current");

    fireEvent.click(target);
    expect(target).toHaveAttribute("aria-current", "step");
    expect(announcement()).toContain("Point 3 of 14, set 1");
    // Exactly one step is current.
    expect(
      screen
        .getAllByRole("button")
        .filter((button) => button.hasAttribute("aria-current")),
    ).toHaveLength(1);

    fireEvent.click(screen.getByRole("button", { name: "Start of the match, 0-0" }));
    expect(
      screen.getByRole("button", { name: "Start of the match, 0-0" }),
    ).toHaveAttribute("aria-current", "step");
  });

  it("does not call onSelect for a click on the step already selected", () => {
    const onSelect = vi.fn();
    render(
      <ReplayCard
        replay={RETIRED_REPLAY}
        selected={2}
        onSelect={onSelect}
        team1Name="Ana"
        team2Name="Bob"
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "Point 3: 30-15, point Ana" }));
    expect(onSelect).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Point 4: 40-15, point Ana" }));
    expect(onSelect).toHaveBeenCalledWith(3);
  });

  it("shows when each point was played, relative to the start of scoring", () => {
    renderCard(RETIRED_REPLAY, 10);
    expect(screen.getByTitle("Time since scoring began")).toHaveTextContent("1:00:05");
  });

  it("does not offer serve or break features an older replay lacks the data for", () => {
    renderCard(OLD_FORMAT_REPLAY, REPLAY_START);
    expect(announcement()).not.toContain("to serve");
    // The break still happened, but this replay cannot say so: it reads as a plain game.
    expect(screen.getByRole("button", { name: "Point 9: Game Ana, games 2-0" })).toHaveTextContent(
      "Game",
    );
    expect(screen.queryByText("Break")).not.toBeInTheDocument();
  });

  it("finishes on Final, marking nobody, when the match was played out", () => {
    const completed = replayOf(
      [
        replayPoint({
          wonBy: "TEAM2",
          setWon: true,
          gameWon: true,
          score: replayScore({ sets: [{ team1: 4, team2: 6 }] }),
        }),
        replayPoint({
          kind: "END",
          wonBy: "TEAM2",
          outcome: "COMPLETED",
          score: replayScore({ sets: [{ team1: 4, team2: 6 }] }),
        }),
      ],
      { sets: [{ team1: 4, team2: 6 }], winner: "TEAM2" },
    );
    renderCard(completed, 1);

    expect(screen.getByRole("button", { name: "Point 2: Bob wins the match" })).toHaveTextContent(
      "Final",
    );
    expect(side("TEAM1")).not.toContain("(R)");
    expect(side("TEAM2")).not.toContain("(D)");
    expect(announcement()).toContain("Sets 4-6.");
  });

  it("reads an ending with no recorded outcome as a plain Final", () => {
    const bare = replayOf(
      [replayPoint({ kind: "END", wonBy: "TEAM1", score: replayScore({ gamesTeam1: 1 }) })],
      { sets: [{ team1: 1, team2: 0 }], winner: "TEAM1" },
    );
    renderCard(bare, 0);
    expect(screen.getByRole("button", { name: "Point 1: Ana wins the match" })).toHaveTextContent(
      "Final",
    );
    expect(side("TEAM2")).not.toContain("(");
  });

  it("marks a default with its own wording", () => {
    const defaulted = replayOf(
      [
        replayPoint({ wonBy: "TEAM1", score: replayScore({ gamesTeam1: 2, gamesTeam2: 1 }) }),
        replayPoint({
          kind: "END",
          wonBy: "TEAM2",
          outcome: "DEFAULTED",
          score: replayScore({ gamesTeam1: 2, gamesTeam2: 1 }),
        }),
      ],
      { sets: [{ team1: 2, team2: 1 }], winner: "TEAM2" },
    );
    renderCard(defaulted, 1);

    expect(
      screen.getByRole("button", { name: "Point 2: Ana defaulted, Bob wins the match" }),
    ).toHaveTextContent("Defaulted");
    expect(side("TEAM1")).toContain("(D)");
    // Ended mid-set: the games in progress are part of the final position.
    expect(announcement()).toContain("Games 2-1");
  });

  it("copes with a replay that has no steps at all", () => {
    renderCard(replayOf([]), REPLAY_START);
    expect(announcement()).toContain("Start of the match");
    for (const name of ["First", "Previous", "Next", "Last"]) {
      expect(control(name)).toBeDisabled();
    }
  });
});
