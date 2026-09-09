import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { LiveScoringPage } from "./LiveScoringPage";

const {
  useDeleteApiV1MatchesMatchIdLiveClaim,
  useGetApiV1MatchesCodeCode,
  useGetApiV1MatchesMatchIdLive,
  usePostApiV1MatchesMatchIdLiveClaim,
  usePostApiV1MatchesMatchIdLiveEvents,
  usePostApiV1MatchesMatchIdLiveFinalize,
  usePostApiV1MatchesMatchIdLiveUndo,
  useGetApiV1UsersMe,
} = vi.hoisted(() => ({
  useDeleteApiV1MatchesMatchIdLiveClaim: vi.fn(),
  useGetApiV1MatchesCodeCode: vi.fn(),
  useGetApiV1MatchesMatchIdLive: vi.fn(),
  usePostApiV1MatchesMatchIdLiveClaim: vi.fn(),
  usePostApiV1MatchesMatchIdLiveEvents: vi.fn(),
  usePostApiV1MatchesMatchIdLiveFinalize: vi.fn(),
  usePostApiV1MatchesMatchIdLiveUndo: vi.fn(),
  useGetApiV1UsersMe: vi.fn(),
}));

vi.mock("@/api/generated/matches/matches", () => ({
  useDeleteApiV1MatchesMatchIdLiveClaim,
  useGetApiV1MatchesCodeCode,
  useGetApiV1MatchesMatchIdLive,
  usePostApiV1MatchesMatchIdLiveClaim,
  usePostApiV1MatchesMatchIdLiveEvents,
  usePostApiV1MatchesMatchIdLiveFinalize,
  usePostApiV1MatchesMatchIdLiveUndo,
}));
vi.mock("@/api/generated/users/users", () => ({ useGetApiV1UsersMe }));
vi.mock("sonner", () => ({ toast: { error: vi.fn(), success: vi.fn() } }));

const recordMutate = vi.fn();
const undoMutate = vi.fn();
const claimMutate = vi.fn();
const finalizeMutate = vi.fn();
const releaseMutate = vi.fn();
const exitFullscreen = vi.fn().mockResolvedValue(undefined);
let finalizeOnSuccess: (() => void) | undefined;

const liveView = {
  matchId: "m-1",
  sequence: 3,
  scorerId: "u1",
  hasStarted: true,
  isPaused: false,
  isTiebreak: false,
  pointsTeam1: "30",
  pointsTeam2: "15",
  gamesTeam1: 2,
  gamesTeam2: 1,
  sets: [],
  outcome: null,
};

function renderPage() {
  return render(
    <MemoryRouter initialEntries={["/matches/MTCH01/score"]}>
      <Routes>
        <Route path="/matches/:code/score" element={<LiveScoringPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

/** Get past the "Start scoring" gate, which exists because fullscreen needs a user gesture. */
async function start(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole("button", { name: "Start scoring" }));
}

describe("LiveScoringPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // jsdom has no fullscreen or orientation APIs; the hook is written to tolerate both being absent,
    // which is the same path iOS Safari takes for the orientation lock.
    Object.defineProperty(document.documentElement, "requestFullscreen", {
      value: vi.fn().mockResolvedValue(undefined),
      configurable: true,
    });
    Object.defineProperty(document, "exitFullscreen", {
      value: exitFullscreen,
      configurable: true,
    });
    Object.defineProperty(document, "fullscreenElement", {
      value: document.documentElement,
      configurable: true,
    });
    window.matchMedia = vi.fn().mockImplementation((query: string) => ({
      matches: false,
      media: query,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })) as unknown as typeof window.matchMedia;

    useGetApiV1UsersMe.mockReturnValue({
      data: { id: "u1", capabilities: ["PLAYER", "SCORER"] },
    });
    useGetApiV1MatchesCodeCode.mockReturnValue({
      data: {
        id: "m-1",
        publicCode: "MTCH01",
        matchNumber: 3,
        event: { publicCode: "EVT001", name: "Summer Open" },
        team1: [{ displayName: "Ana", publicCode: "AAA111" }],
        team2: [{ displayName: "Bob", publicCode: "BBB222" }],
      },
      isLoading: false,
    });
    useGetApiV1MatchesMatchIdLive.mockReturnValue({
      data: liveView,
      refetch: vi.fn(),
    });
    usePostApiV1MatchesMatchIdLiveEvents.mockReturnValue({
      mutate: recordMutate,
      isPending: false,
    });
    usePostApiV1MatchesMatchIdLiveUndo.mockReturnValue({
      mutate: undoMutate,
      isPending: false,
    });
    usePostApiV1MatchesMatchIdLiveClaim.mockReturnValue({
      mutate: claimMutate,
      isPending: false,
    });
    useDeleteApiV1MatchesMatchIdLiveClaim.mockReturnValue({
      mutate: releaseMutate,
      isPending: false,
    });
    usePostApiV1MatchesMatchIdLiveFinalize.mockImplementation(
      (opts?: { mutation?: { onSuccess?: () => void } }) => {
        finalizeOnSuccess = opts?.mutation?.onSuccess;
        return { mutate: finalizeMutate, isPending: false };
      },
    );
  });

  it("shows a rotate prompt in portrait and nothing else (#911)", () => {
    window.matchMedia = vi.fn().mockImplementation((query: string) => ({
      matches: true,
      media: query,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })) as unknown as typeof window.matchMedia;

    renderPage();

    expect(screen.getByText("Rotate your device")).toBeInTheDocument();
    // The board must not render at all — the requirement is landscape-only, not landscape-preferred.
    expect(screen.queryByLabelText("Point to Ana")).not.toBeInTheDocument();
  });

  it("refuses a viewer without the SCORER role", () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: "u1", capabilities: ["PLAYER"] },
    });
    renderPage();
    expect(screen.getByText("You cannot score this match")).toBeInTheDocument();
  });

  it("refuses when the match id was not revealed, which is itself the gate", () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({
      data: { publicCode: "MTCH01", matchNumber: 3 },
      isLoading: false,
    });
    renderPage();
    expect(
      screen.getByText("Match not available for scoring"),
    ).toBeInTheDocument();
  });

  it("claims the match when scoring starts, since fullscreen needs a gesture", async () => {
    const user = userEvent.setup();
    renderPage();
    await start(user);
    expect(claimMutate).toHaveBeenCalledWith({ matchId: "m-1" });
  });

  it("records a point to the side that was tapped", async () => {
    const user = userEvent.setup();
    renderPage();
    await start(user);

    await user.click(screen.getByLabelText("Point to Bob"));
    expect(recordMutate).toHaveBeenCalledWith({
      matchId: "m-1",
      data: { kind: "POINT_WON", side: "TEAM2" },
    });
  });

  it("renders the points the server sent rather than computing them", async () => {
    const user = userEvent.setup();
    renderPage();
    await start(user);
    // Deuce has exactly one implementation, and it is not here.
    expect(screen.getByText("30")).toBeInTheDocument();
    expect(screen.getByText("15")).toBeInTheDocument();
  });

  it("switching sides is display-only and records nothing (#911 §6)", async () => {
    const user = userEvent.setup();
    renderPage();
    await start(user);
    recordMutate.mockClear();

    await user.click(screen.getByRole("button", { name: "Switch sides" }));

    // A flip must never reach the log: events name a side, so a recorded flip would corrupt history.
    expect(recordMutate).not.toHaveBeenCalled();
    expect(screen.getByLabelText("Point to Ana")).toBeInTheDocument();
  });

  it("labels each side with its players, and the label follows the flip (#937)", async () => {
    const user = userEvent.setup();
    renderPage();
    await start(user);

    // Two anonymous tap targets is how a point gets recorded for the wrong player.
    const before = screen.getAllByRole("button", { name: /^Point to/ });
    expect(before[0]).toHaveAccessibleName("Point to Ana");
    expect(before[1]).toHaveAccessibleName("Point to Bob");

    await user.click(screen.getByRole("button", { name: "Switch sides" }));

    // The label must travel WITH its side. A flip that moved the scores but not the names would be
    // worse than no labels — it would confidently say the wrong thing.
    const after = screen.getAllByRole("button", { name: /^Point to/ });
    expect(after[0]).toHaveAccessibleName("Point to Bob");
    expect(after[1]).toHaveAccessibleName("Point to Ana");
  });

  it("shows the event name and match number, for an umpire running several courts (#937)", async () => {
    const user = userEvent.setup();
    renderPage();
    await start(user);
    expect(screen.getByText(/Summer Open/)).toBeInTheDocument();
    expect(screen.getByText(/Match #3/)).toBeInTheDocument();
  });

  it("shows the set IN PROGRESS, not the number banked (#937)", async () => {
    // Off by one is the entire point of showing it: with one set banked, they are playing the second.
    useGetApiV1MatchesMatchIdLive.mockReturnValue({
      data: {
        ...liveView,
        sets: [
          { gamesTeam1: 6, gamesTeam2: 4, winner: "TEAM1", tiebreakTeam1Points: null, tiebreakTeam2Points: null },
        ],
      },
      refetch: vi.fn(),
    });
    const user = userEvent.setup();
    renderPage();
    await start(user);
    expect(screen.getByText("Set 2")).toBeInTheDocument();
  });

  it("leaving releases the claim and the display locks, without finalizing (#937)", async () => {
    const user = userEvent.setup();
    renderPage();
    await start(user);

    await user.click(screen.getByRole("button", { name: "← Back" }));

    // Somebody who left is not scoring the match, so the claim must not linger.
    expect(releaseMutate).toHaveBeenCalledWith({ matchId: "m-1" });
    // ...and the app must not stay full-screen and rotation-locked after navigating away.
    expect(exitFullscreen).toHaveBeenCalled();
    // Leaving mid-match is not finalizing. Before #937 this was the only way out, which made it a trap.
    expect(finalizeMutate).not.toHaveBeenCalled();
  });

  it("undo posts to its own endpoint, because the server picks the target", async () => {
    const user = userEvent.setup();
    renderPage();
    await start(user);

    await user.click(screen.getByRole("button", { name: "Undo" }));
    expect(undoMutate).toHaveBeenCalledWith({ matchId: "m-1" });
    expect(recordMutate).not.toHaveBeenCalledWith(
      expect.objectContaining({ data: expect.objectContaining({ kind: "UNDONE" }) }),
    );
  });

  it("hides Finalize from a plain SCORER, who may score but not record the result", async () => {
    const user = userEvent.setup();
    renderPage();
    await start(user);
    // #934: finalizing keeps the #789 organizer gate. Offering the button would 403 — the #867 shape.
    expect(
      screen.queryByRole("button", { name: "Finalize" }),
    ).not.toBeInTheDocument();
  });

  it("offers Finalize to an organizer, but only once the match is declared over", async () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: "u1", capabilities: ["PLAYER", "SCORER", "HOST"] },
    });
    const user = userEvent.setup();
    const { unmount } = renderPage();
    await start(user);

    expect(screen.getByRole("button", { name: "Finalize" })).toBeDisabled();
    unmount();

    useGetApiV1MatchesMatchIdLive.mockReturnValue({
      data: {
        ...liveView,
        outcome: { kind: "COMPLETED", winner: "TEAM1", concededBy: null },
      },
      refetch: vi.fn(),
    });
    renderPage();
    await start(user);
    expect(screen.getByRole("button", { name: "Finalize" })).toBeEnabled();
  });

  it("pause and resume swap, and send the matching action", async () => {
    const user = userEvent.setup();
    const { unmount } = renderPage();
    await start(user);

    await user.click(screen.getByRole("button", { name: "Pause" }));
    expect(recordMutate).toHaveBeenCalledWith({
      matchId: "m-1",
      data: { kind: "PAUSED" },
    });
    unmount();

    useGetApiV1MatchesMatchIdLive.mockReturnValue({
      data: { ...liveView, isPaused: true },
      refetch: vi.fn(),
    });
    renderPage();
    await start(user);
    expect(screen.getByText("Paused")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Resume" }));
    expect(recordMutate).toHaveBeenCalledWith({
      matchId: "m-1",
      data: { kind: "RESUMED" },
    });
  });

  it("a retirement names the side that conceded", async () => {
    const user = userEvent.setup();
    renderPage();
    await start(user);

    await user.click(screen.getByRole("button", { name: "1 retires" }));
    // The side that retired, not the winner — the server turns it into a win for the opponent.
    expect(recordMutate).toHaveBeenCalledWith({
      matchId: "m-1",
      data: { kind: "RETIRED", side: "TEAM1" },
    });
  });

  it("shows banked sets and the tiebreak badge", async () => {
    useGetApiV1MatchesMatchIdLive.mockReturnValue({
      data: {
        ...liveView,
        isTiebreak: true,
        sets: [
          {
            gamesTeam1: 6,
            gamesTeam2: 7,
            winner: "TEAM2",
            tiebreakTeam1Points: 5,
            tiebreakTeam2Points: 7,
          },
        ],
      },
      refetch: vi.fn(),
    });
    const user = userEvent.setup();
    renderPage();
    await start(user);

    expect(screen.getByText(/6-7/)).toBeInTheDocument();
    expect(screen.getByText("Tiebreak")).toBeInTheDocument();
  });
  it("shows a loading state while the match is being fetched", () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({ data: undefined, isLoading: true });
    renderPage();
    expect(screen.getByText("Loading…")).toBeInTheDocument();
  });

  it("shows a loading state while the score is being fetched", async () => {
    useGetApiV1MatchesMatchIdLive.mockReturnValue({
      data: undefined,
      refetch: vi.fn(),
    });
    const user = userEvent.setup();
    renderPage();
    await start(user);
    expect(screen.getByText("Loading score…")).toBeInTheDocument();
  });

  it("offers a way back to the match from each refusal", async () => {
    // Both refusals, because the test name says each and a page that fills the screen with no way out
    // is the failure mode worth guarding. Covering only one would let the other become a dead end.
    const user = userEvent.setup();

    useGetApiV1UsersMe.mockReturnValue({
      data: { id: "u1", capabilities: ["PLAYER"] },
    });
    const noRole = renderPage();
    await user.click(screen.getByRole("button", { name: "Back to the match" }));
    expect(screen.queryByText("You cannot score this match")).not.toBeInTheDocument();
    noRole.unmount();

    // ...and the one where the caller may score but the id was never revealed.
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: "u1", capabilities: ["PLAYER", "SCORER"] },
    });
    useGetApiV1MatchesCodeCode.mockReturnValue({
      data: { publicCode: "MTCH01", matchNumber: 3 },
      isLoading: false,
    });
    renderPage();
    await user.click(screen.getByRole("button", { name: "Back to the match" }));
    expect(
      screen.queryByText("Match not available for scoring"),
    ).not.toBeInTheDocument();
  });

  it("offers Start match until the match has officially started (#911)", async () => {
    useGetApiV1MatchesMatchIdLive.mockReturnValue({
      data: { ...liveView, hasStarted: false },
      refetch: vi.fn(),
    });
    const user = userEvent.setup();
    renderPage();
    await start(user);

    await user.click(screen.getByRole("button", { name: "Start match" }));
    // Distinct from the first point on purpose — it is the anchor a match duration is measured from.
    expect(recordMutate).toHaveBeenCalledWith({
      matchId: "m-1",
      data: { kind: "MATCH_STARTED" },
    });
  });

  it("ends a set, starts a tiebreak, and records a default", async () => {
    const user = userEvent.setup();
    renderPage();
    await start(user);

    await user.click(screen.getByRole("button", { name: "Set to 1" }));
    expect(recordMutate).toHaveBeenCalledWith({
      matchId: "m-1",
      data: { kind: "SET_AWARDED", side: "TEAM1" },
    });

    await user.click(screen.getByRole("button", { name: "Start tiebreak" }));
    expect(recordMutate).toHaveBeenCalledWith({
      matchId: "m-1",
      data: { kind: "TIEBREAK_STARTED" },
    });

    await user.click(screen.getByRole("button", { name: "2 defaults" }));
    expect(recordMutate).toHaveBeenCalledWith({
      matchId: "m-1",
      data: { kind: "DEFAULTED", side: "TEAM2" },
    });
  });

  it("every action names the side it was pressed for, on both sides", async () => {
    // The whole control row is symmetric, and a copy-paste slip that wired both buttons to TEAM1 would
    // be invisible in a test that only ever presses one of each pair. So press the other one.
    const user = userEvent.setup();
    renderPage();
    await start(user);

    await user.click(screen.getByRole("button", { name: "Set to 2" }));
    expect(recordMutate).toHaveBeenCalledWith({
      matchId: "m-1",
      data: { kind: "SET_AWARDED", side: "TEAM2" },
    });

    await user.click(screen.getByRole("button", { name: "2 retires" }));
    expect(recordMutate).toHaveBeenCalledWith({
      matchId: "m-1",
      data: { kind: "RETIRED", side: "TEAM2" },
    });

    await user.click(screen.getByRole("button", { name: "1 defaults" }));
    expect(recordMutate).toHaveBeenCalledWith({
      matchId: "m-1",
      data: { kind: "DEFAULTED", side: "TEAM1" },
    });
  });

  it("finalizing submits, then leaves fullscreen and returns to the match", async () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: "u1", capabilities: ["PLAYER", "SCORER", "HOST"] },
    });
    useGetApiV1MatchesMatchIdLive.mockReturnValue({
      data: {
        ...liveView,
        outcome: { kind: "COMPLETED", winner: "TEAM1", concededBy: null },
      },
      refetch: vi.fn(),
    });
    const user = userEvent.setup();
    renderPage();
    await start(user);

    await user.click(screen.getByRole("button", { name: "Finalize" }));
    expect(finalizeMutate).toHaveBeenCalledWith({ matchId: "m-1" });

    // Leaving fullscreen on the way out matters: the umpire view is the only page that takes it, so
    // failing to release it would strand the whole app full-screen.
    finalizeOnSuccess?.();
    expect(exitFullscreen).toHaveBeenCalled();
  });
});
