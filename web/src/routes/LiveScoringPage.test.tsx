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
import { toast } from "sonner";

const recordMutate = vi.fn();
const undoMutate = vi.fn();
const claimMutate = vi.fn();
const finalizeMutate = vi.fn();
const releaseMutate = vi.fn();
const exitFullscreen = vi.fn().mockResolvedValue(undefined);
let finalizeOnSuccess: (() => void) | undefined;

const players = [
  { userId: "p-1", name: "Ana", side: "TEAM1" },
  { userId: "p-2", name: "Bob", side: "TEAM2" },
];

const liveView = {
  matchId: "m-1",
  players,
  sequence: 3,
  scorerId: "u1",
  hasStarted: true,
  isPaused: false,
  // A match under way has a server: since #985 a point is refused without one, so a fixture lacking it
  // would not represent a scorable match.
  isBetweenSets: false,
  serverId: "p-1",
  serverName: "Ana",
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

  it("identifies the match before scoring starts, not just its number (#956)", async () => {
    // This screen cannot be skipped (fullscreen needs a gesture), so it is the ONLY confirmation an
    // umpire gets — and starting claims the match, displacing whoever held it.
    renderPage();

    expect(screen.getByText("Summer Open")).toBeInTheDocument();
    expect(screen.getByText("Match #3")).toBeInTheDocument();
    // The players are the part that actually confirms the right court.
    expect(screen.getByText(/Ana/)).toBeInTheDocument();
    expect(screen.getByText(/Bob/)).toBeInTheDocument();
  });

  it("offers a way off the confirm screen without claiming the match (#956)", async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole("button", { name: "Back" }));
    // Leaving must not claim: the whole point of the screen is that you might be on the wrong court.
    expect(claimMutate).not.toHaveBeenCalled();
  });

  it("degrades cleanly when the event has no name (#956)", async () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({
      data: {
        id: "m-1",
        publicCode: "MTCH01",
        matchNumber: 3,
        team1: [{ displayName: "Ana", publicCode: "AAA111" }],
        team2: [{ displayName: "Bob", publicCode: "BBB222" }],
      },
      isLoading: false,
    });
    renderPage();

    // MatchPublicResponse.event is optional; the layout must not render an empty heading.
    expect(screen.getByText("Match #3")).toBeInTheDocument();
    expect(screen.getByText(/Ana/)).toBeInTheDocument();
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

  it("falls back to just the match number when the event has no name", async () => {
    // MatchPublicResponse.event is optional in the contract, so the label must degrade to something
    // usable rather than rendering "undefined ·" in front of the number.
    useGetApiV1MatchesCodeCode.mockReturnValue({
      data: {
        id: "m-1",
        publicCode: "MTCH01",
        matchNumber: 3,
        team1: [{ displayName: "Ana", publicCode: "AAA111" }],
        team2: [{ displayName: "Bob", publicCode: "BBB222" }],
      },
      isLoading: false,
    });
    const user = userEvent.setup();
    renderPage();
    await start(user);

    expect(screen.getByText(/Match #3/)).toBeInTheDocument();
    expect(screen.queryByText(/·/)).not.toBeInTheDocument();
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

    await user.click(screen.getByRole("button", { name: "Ana retires" }));
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

    await user.click(screen.getByRole("button", { name: "Set to Ana" }));
    expect(recordMutate).toHaveBeenCalledWith({
      matchId: "m-1",
      data: { kind: "SET_AWARDED", side: "TEAM1" },
    });

    await user.click(screen.getByRole("button", { name: "Start tiebreak" }));
    expect(recordMutate).toHaveBeenCalledWith({
      matchId: "m-1",
      data: { kind: "TIEBREAK_STARTED" },
    });

    await user.click(screen.getByRole("button", { name: "Bob defaults" }));
    expect(recordMutate).toHaveBeenCalledWith({
      matchId: "m-1",
      data: { kind: "DEFAULTED", side: "TEAM2" },
    });
  });

  it("awards the game to either side (#944)", async () => {
    // GAME_AWARDED was wired end to end server-side with no client reference at all. Without it the
    // only route to a game is tapping four points, which records points that were never played — fine
    // for the games figure, fiction for the per-set detail a player is shown when they ask why their
    // rating moved.
    const user = userEvent.setup();
    renderPage();
    await start(user);

    await user.click(screen.getByRole("button", { name: "Game to Ana" }));
    expect(recordMutate).toHaveBeenCalledWith({
      matchId: "m-1",
      data: { kind: "GAME_AWARDED", side: "TEAM1" },
    });

    await user.click(screen.getByRole("button", { name: "Game to Bob" }));
    expect(recordMutate).toHaveBeenCalledWith({
      matchId: "m-1",
      data: { kind: "GAME_AWARDED", side: "TEAM2" },
    });
  });

  it("per-side actions follow the flip along with their side (#944)", async () => {
    // They sit under the board halves now, so a flip must carry them too — otherwise "Game" under the
    // left half would award the right-hand player, which is the mistake side labels exist to prevent.
    const user = userEvent.setup();
    renderPage();
    await start(user);

    const before = screen.getAllByRole("button", { name: /^Game to/ });
    expect(before[0]).toHaveAccessibleName("Game to Ana");

    await user.click(screen.getByRole("button", { name: "Switch sides" }));

    const after = screen.getAllByRole("button", { name: /^Game to/ });
    expect(after[0]).toHaveAccessibleName("Game to Bob");
  });

  it("every action names the side it was pressed for, on both sides", async () => {
    // The whole control row is symmetric, and a copy-paste slip that wired both buttons to TEAM1 would
    // be invisible in a test that only ever presses one of each pair. So press the other one.
    const user = userEvent.setup();
    renderPage();
    await start(user);

    await user.click(screen.getByRole("button", { name: "Set to Bob" }));
    expect(recordMutate).toHaveBeenCalledWith({
      matchId: "m-1",
      data: { kind: "SET_AWARDED", side: "TEAM2" },
    });

    await user.click(screen.getByRole("button", { name: "Bob retires" }));
    expect(recordMutate).toHaveBeenCalledWith({
      matchId: "m-1",
      data: { kind: "RETIRED", side: "TEAM2" },
    });

    await user.click(screen.getByRole("button", { name: "Ana defaults" }));
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

  it("offers to set a server when none is chosen (#943)", async () => {
    const user = userEvent.setup();
    // No server yet: the base fixture has one, since #985 makes a point impossible without it, so this
    // test has to arrange the state it is actually about.
    useGetApiV1MatchesMatchIdLive.mockReturnValue({
      data: { ...liveView, serverId: null, serverName: null },
      isLoading: false,
    });
    renderPage();
    await start(user);

    await user.click(screen.getByRole("button", { name: "Set which side is serving" }));
    expect(recordMutate).toHaveBeenCalledWith({
      matchId: "m-1",
      data: { kind: "SERVER_ASSIGNED", playerId: "p-1" },
    });
  });

  it("cycles to the next player rather than opening a menu (#943)", async () => {
    // One tap, because re-picking from a list every game is enough friction that the field stops
    // being maintained — and a serving indicator nobody updates is worse than none.
    useGetApiV1MatchesMatchIdLive.mockReturnValue({
      data: { ...liveView, serverId: "p-1", serverName: "Ana" },
      refetch: vi.fn(),
    });
    const user = userEvent.setup();
    renderPage();
    await start(user);

    await user.click(screen.getByRole("button", { name: /Serving: Ana/ }));
    expect(recordMutate).toHaveBeenCalledWith({
      matchId: "m-1",
      data: { kind: "SERVER_ASSIGNED", playerId: "p-2" },
    });
  });

  it("wraps around to the first player from the last (#943)", async () => {
    useGetApiV1MatchesMatchIdLive.mockReturnValue({
      data: { ...liveView, serverId: "p-2", serverName: "Bob" },
      refetch: vi.fn(),
    });
    const user = userEvent.setup();
    renderPage();
    await start(user);

    await user.click(screen.getByRole("button", { name: /Serving: Bob/ }));
    expect(recordMutate).toHaveBeenCalledWith({
      matchId: "m-1",
      data: { kind: "SERVER_ASSIGNED", playerId: "p-1" },
    });
  });

  it("renders no server control when the roster is unknown", async () => {
    useGetApiV1MatchesMatchIdLive.mockReturnValue({
      data: { ...liveView, players: [] },
      refetch: vi.fn(),
    });
    const user = userEvent.setup();
    renderPage();
    await start(user);
    expect(screen.queryByRole("button", { name: /Serving|Set who is serving/ })).not.toBeInTheDocument();
  });
  // ---- The opening and between-sets gates (#984/#985/#986) --------------------------------------

  it("keeps every scoring control inert until the match is started (#986)", async () => {
    // MATCH_STARTED is what starts the clock, so scoring without it produced a match with points and
    // a duration of zero — and a spectator card reading "About to start" beside accumulating points.
    const user = userEvent.setup();
    useGetApiV1MatchesMatchIdLive.mockReturnValue({
      data: { ...liveView, hasStarted: false },
      isLoading: false,
    });
    renderPage();
    await start(user);

    expect(screen.getByLabelText("Point to Ana")).toBeDisabled();
    expect(screen.getByLabelText("Game to Ana")).toBeDisabled();
    expect(screen.getByLabelText("Set to Ana")).toBeDisabled();
    expect(screen.getByLabelText("Ana retires")).toBeDisabled();
    // Back stays available throughout — an umpire must be able to leave a match opened by mistake.
    expect(screen.getByRole("button", { name: "← Back" })).toBeEnabled();
  });

  it("refuses points until somebody is serving, but still allows a declared game (#985)", async () => {
    // A point with nobody serving leaves the scoreboard unable to say who is. A game or set is an
    // umpire declaration and needs no server, so gating those too would be over-applying the rule.
    const user = userEvent.setup();
    useGetApiV1MatchesMatchIdLive.mockReturnValue({
      data: { ...liveView, serverId: null, serverName: null },
      isLoading: false,
    });
    renderPage();
    await start(user);

    expect(screen.getByLabelText("Point to Ana")).toBeDisabled();
    expect(screen.getByLabelText("Game to Ana")).toBeEnabled();
  });

  it("stops between sets and offers the choice that was missing (#984)", async () => {
    // The bug this whole change exists for: awarding a set rolled straight into the next, so the
    // moment to ask "is this over?" never came and Finalize could never be reached.
    const user = userEvent.setup();
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: "u1", capabilities: ["PLAYER", "SCORER", "HOST"] },
    });
    useGetApiV1MatchesMatchIdLive.mockReturnValue({
      data: { ...liveView, isBetweenSets: true },
      isLoading: false,
    });
    renderPage();
    await start(user);

    expect(screen.getByLabelText("Point to Ana")).toBeDisabled();
    expect(screen.getByRole("button", { name: "Start next set" })).toBeEnabled();
    // Finalize is reachable HERE, on a match that simply finished — no retirement required.
    expect(screen.getByRole("button", { name: "Finalize" })).toBeEnabled();
  });

  it("starts the next set on request (#984)", async () => {
    const user = userEvent.setup();
    useGetApiV1MatchesMatchIdLive.mockReturnValue({
      data: { ...liveView, isBetweenSets: true },
      isLoading: false,
    });
    renderPage();
    await start(user);

    await user.click(screen.getByRole("button", { name: "Start next set" }));
    expect(recordMutate).toHaveBeenCalledWith({ matchId: "m-1", data: { kind: "SET_STARTED" } });
  });

  it("offers no between-sets choice mid-set (#984)", async () => {
    // The control must not over-apply: it belongs only to the pause between sets.
    const user = userEvent.setup();
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: "u1", capabilities: ["PLAYER", "SCORER", "HOST"] },
    });
    renderPage();
    await start(user);

    expect(screen.queryByRole("button", { name: "Start next set" })).not.toBeInTheDocument();
    // And Finalize stays unreachable mid-set, as it always was.
    expect(screen.getByRole("button", { name: "Finalize" })).toBeDisabled();
  });

  it("leaves via Back, releasing fullscreen AND the claim on the way (#986, #1073)", async () => {
    // The umpire view is the only page that takes fullscreen, so leaving without releasing it would
    // strand the whole app.
    //
    // The claim assertion is the #1073 half and is the reason this test matters: there used to be a
    // second Back control that released fullscreen but NOT the claim, so a match still looked claimed
    // by an umpire who had walked away. That control is gone; this one goes through `leave()`. Without
    // the releaseMutate assertion, deleting the wrong one of the two would have passed.
    const user = userEvent.setup();
    renderPage();
    await start(user);

    await user.click(screen.getByRole("button", { name: "← Back" }));
    expect(exitFullscreen).toHaveBeenCalled();
    expect(releaseMutate).toHaveBeenCalled();
  });

  it("is a two-state side toggle, named by side rather than by player (#1072)", async () => {
    const user = userEvent.setup();
    renderPage();
    await start(user);

    // Visible label is static, so the button stops being as wide as the longest name — and "Toggle"
    // is accurate because the designation is the serving TEAM, which really is two-state.
    const control = screen.getByRole("button", { name: /Serving: Ana/ });
    expect(control).toHaveTextContent("Toggle server");
    expect(control).not.toHaveTextContent("Ana");
    // Singles: the side IS the player, so the spoken name is just the name — no "Team" prefix.
    expect(control).toHaveAttribute(
      "aria-label",
      "Serving: Ana. Tap to switch sides.",
    );
  });

  it("speaks a doubles side as a team, not a player (#1072)", async () => {
    const user = userEvent.setup();
    useGetApiV1MatchesMatchIdLive.mockReturnValue({
      data: {
        ...liveView,
        players: [
          { userId: "p-1", name: "Ana", side: "TEAM1" },
          { userId: "p-1b", name: "Bea", side: "TEAM1" },
          { userId: "p-2", name: "Bob", side: "TEAM2" },
          { userId: "p-2b", name: "Cal", side: "TEAM2" },
        ],
      },
      isLoading: false,
    });
    renderPage();
    await start(user);

    // Naming a single player would be a claim the app does not make: only the team is tracked, and
    // the umpire calls out which partner is up. "and" rather than "&" because this is read aloud.
    expect(
      screen.getByRole("button", { name: /Serving: Team Ana and Bea/ }),
    ).toHaveTextContent("Toggle server");
  });

  it("switches the serving SIDE, not to the next player (#1072)", async () => {
    const user = userEvent.setup();
    useGetApiV1MatchesMatchIdLive.mockReturnValue({
      data: {
        ...liveView,
        players: [
          { userId: "p-1", name: "Ana", side: "TEAM1" },
          { userId: "p-1b", name: "Bea", side: "TEAM1" },
          { userId: "p-2", name: "Bob", side: "TEAM2" },
          { userId: "p-2b", name: "Cal", side: "TEAM2" },
        ],
      },
      isLoading: false,
    });
    renderPage();
    await start(user);

    // Ana (TEAM1) is serving, so one tap must hand over to TEAM2 — NOT advance to Bea, her partner.
    // Doubles serving order changes at tiebreaks and alternates across games, so the app tracks only
    // the team and leaves the partner to the umpire's call.
    await user.click(screen.getByRole("button", { name: /Serving: Team Ana and Bea/ }));
    expect(recordMutate).toHaveBeenCalledWith({
      matchId: "m-1",
      data: { kind: "SERVER_ASSIGNED", playerId: "p-2" },
    });
  });

  it('says "Set server" while nothing is assigned, not "Change server" (#1072)', async () => {
    const user = userEvent.setup();
    useGetApiV1MatchesMatchIdLive.mockReturnValue({
      data: { ...liveView, hasStarted: false, serverId: null, serverName: null },
      isLoading: false,
    });
    renderPage();
    await start(user);

    // An outstanding step reads differently from a change — and #1070's prompt relies on it.
    expect(
      screen.getByRole("button", { name: "Set which side is serving" }),
    ).toHaveTextContent("Set server");
  });

  it("marks the serving side with a ball that has a text alternative (#1072)", async () => {
    const user = userEvent.setup();
    renderPage();
    await start(user);

    // Ana (TEAM1) is serving in the fixture. A shape alone tells a screen reader nothing, so the
    // graphic is aria-hidden and carries an adjacent sr-only word.
    expect(screen.getAllByText("serving")).toHaveLength(1);
  });

  it("splits doubles partners onto their own rows, ready for a per-player ball (#1072)", async () => {
    const user = userEvent.setup();
    useGetApiV1MatchesMatchIdLive.mockReturnValue({
      data: {
        ...liveView,
        players: [
          { userId: "p-1", name: "Ana", side: "TEAM1" },
          { userId: "p-1b", name: "Bea", side: "TEAM1" },
          { userId: "p-2", name: "Bob", side: "TEAM2" },
          { userId: "p-2b", name: "Cal", side: "TEAM2" },
        ],
      },
      isLoading: false,
    });
    renderPage();
    await start(user);

    // Four separate rows, not two joined strings. Nothing hangs off them yet — the ball still marks
    // the side — but a per-player indicator can attach later without moving anything.
    for (const name of ["Ana", "Bea", "Bob", "Cal"]) {
      expect(screen.getByText(name)).toBeInTheDocument();
    }
    // Still exactly one ball: it marks the serving SIDE, and doubling it onto both partners would
    // claim something the umpire has not told us.
    expect(screen.getAllByText("serving")).toHaveLength(1);
  });

  it("keeps singles on a single full-size row (#1072)", async () => {
    const user = userEvent.setup();
    renderPage();
    await start(user);

    // The common case must not regress: one name, one row, original size. Two smaller rows exist
    // only for doubles, and cost no extra height so the action bar stays on screen.
    const label = screen.getByText("Ana");
    expect(label.className).toContain("text-[3.4dvh]");
  });

  it("offers Start next set in the header between sets (#1075)", async () => {
    const user = userEvent.setup();
    useGetApiV1MatchesMatchIdLive.mockReturnValue({
      data: { ...liveView, isBetweenSets: true },
      isLoading: false,
    });
    renderPage();
    await start(user);

    // It used to live in the bottom action row while Start match sat top-right — same kind of action,
    // two places to look. Both are "begin play", so both belong in one slot.
    await user.click(screen.getByRole("button", { name: "Start next set" }));
    expect(recordMutate).toHaveBeenCalledWith({
      matchId: "m-1",
      data: { kind: "SET_STARTED" },
    });
  });

  it("never renders both start controls at once (#1075)", async () => {
    const user = userEvent.setup();

    // `!hasStarted` and `isBetweenSets` are mutually exclusive, which is what lets one header slot
    // hold both. Asserting it means a future state change cannot quietly put two in a full cluster.
    useGetApiV1MatchesMatchIdLive.mockReturnValue({
      data: { ...liveView, hasStarted: false },
      isLoading: false,
    });
    const first = renderPage();
    await start(user);
    expect(screen.getByRole("button", { name: "Start match" })).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Start next set" }),
    ).not.toBeInTheDocument();
    first.unmount();

    useGetApiV1MatchesMatchIdLive.mockReturnValue({
      data: { ...liveView, isBetweenSets: true },
      isLoading: false,
    });
    renderPage();
    await start(user);
    expect(
      screen.getByRole("button", { name: "Start next set" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Start match" }),
    ).not.toBeInTheDocument();
  });

  it("keeps Retire and Default live between sets (#1075, #984/#986)", async () => {
    const user = userEvent.setup();
    // HOST as well as SCORER: Finalize is gated on match-management (`canFinalize`), so a plain
    // SCORER never sees it. Asserting it needs a user who could finalize — otherwise the test would
    // "pass" on a button that was never rendered for a reason unrelated to the between-sets state.
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: "u1", capabilities: ["PLAYER", "SCORER", "HOST"] },
    });
    useGetApiV1MatchesMatchIdLive.mockReturnValue({
      data: { ...liveView, isBetweenSets: true },
      isLoading: false,
    });
    renderPage();
    await start(user);

    // A DECISION, not an oversight: these are transitions rather than scoring actions, and a player
    // retiring after losing a set is ordinary tennis — the most common way a match ends early.
    // Disabling them "for consistency" would make a frequent, legitimate event unrecordable, leaving
    // the umpire to start a set they know will not be played just to retire out of it.
    expect(screen.getByLabelText("Ana retires")).toBeEnabled();
    expect(screen.getByLabelText("Ana defaults")).toBeEnabled();
    expect(screen.getByLabelText("Bob retires")).toBeEnabled();
    // While the scoring actions stay correctly inert.
    expect(screen.getByLabelText("Point to Ana")).toBeDisabled();
    expect(screen.getByLabelText("Game to Ana")).toBeDisabled();
    // And the two the request named as needing to stay usable.
    expect(screen.getByRole("button", { name: "Undo" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Finalize" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "← Back" })).toBeEnabled();
  });

  it("prompts for the server before the match starts, then for Start match (#1070)", async () => {
    const user = userEvent.setup();
    useGetApiV1MatchesMatchIdLive.mockReturnValue({
      data: { ...liveView, hasStarted: false, serverId: null, serverName: null },
      isLoading: false,
    });
    renderPage();
    await start(user);

    // Said BEFORE the umpire probes a dead control — the whole complaint in #1070 was silence.
    expect(
      screen.getByText("Set which side is serving to begin."),
    ).toBeInTheDocument();
    // And Start match is gated on the server, which is the ordering that was never enforced: it used
    // to be clickable with nobody serving, leaving the point buttons dead for a second silent reason.
    const startMatch = screen.getByRole("button", { name: "Start match" });
    expect(startMatch).toBeDisabled();
    expect(startMatch).toHaveAttribute(
      "title",
      "Set which side is serving before starting the match",
    );
  });

  it("advances the prompt once a server is set (#1070)", async () => {
    const user = userEvent.setup();
    useGetApiV1MatchesMatchIdLive.mockReturnValue({
      data: { ...liveView, hasStarted: false },
      isLoading: false,
    });
    renderPage();
    await start(user);

    expect(screen.getByText("Ready — press Start match.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Start match" })).toBeEnabled();
  });

  it("says nothing once play is under way (#1070)", async () => {
    const user = userEvent.setup();
    renderPage();
    await start(user);

    // A prompt that is always on screen is furniture, and furniture is not read.
    expect(screen.queryByText(/Set which side is serving/)).not.toBeInTheDocument();
    expect(screen.queryByText(/press Start match/)).not.toBeInTheDocument();
  });

  it("shows the server's own refusal instead of a generic toast (#1070)", async () => {
    const user = userEvent.setup();
    renderPage();
    await start(user);

    // Pull the handler the page actually wired up and hand it a real ErrorResponse shape. Before
    // #1070 every one of these became "Could not record that", so LiveScoringRules' sentences —
    // written expressly so the view could explain itself — were never seen by anyone.
    const options = usePostApiV1MatchesMatchIdLiveEvents.mock.calls.at(-1)?.[0];
    options?.mutation?.onError?.({
      response: {
        data: {
          message:
            "That set has ended. Start the next set, or finalize the match, before scoring again.",
        },
      },
    });
    expect(toast.error).toHaveBeenCalledWith(
      "That set has ended. Start the next set, or finalize the match, before scoring again.",
      expect.anything(),
    );
  });

  it("falls back to its own copy when the failure carries no message (#1070)", async () => {
    const user = userEvent.setup();
    renderPage();
    await start(user);

    // A network failure or a 500 has nothing worth showing, so the generic string is still right —
    // surfacing "undefined" would be worse than the toast it replaced.
    const options = usePostApiV1MatchesMatchIdLiveEvents.mock.calls.at(-1)?.[0];
    options?.mutation?.onError?.(new Error("Network Error"));
    expect(toast.error).toHaveBeenCalledWith(
      "Could not record that",
      expect.anything(),
    );
  });

  it("floats the current-set label away from the banked-set chips (#1074)", async () => {
    // The bug: the label sat immediately before the chips, so after a 6-4 first set the band read
    // "Set 2  [6-4]" and the chip parsed as the CURRENT set's score.
    const user = userEvent.setup();
    useGetApiV1MatchesMatchIdLive.mockReturnValue({
      data: { ...liveView, sets: [{ gamesTeam1: 6, gamesTeam2: 4 }] },
      isLoading: false,
    });
    renderPage();
    await start(user);

    // The chip now identifies itself, so a bare score cannot be mistaken for the live one.
    expect(screen.getByText("S1")).toBeInTheDocument();
    expect(screen.getByText("6-4")).toBeInTheDocument();
    // And the current set is announced separately, as a status region.
    const label = screen.getByRole("status");
    expect(label).toHaveTextContent("Set 2");
    // Floating over the header: it MUST NOT be hit-testable, or it could swallow a tap on the server
    // toggle or Start match at narrow widths, with no visible cause.
    expect(label).toHaveClass("pointer-events-none");
  });

  it("says a set is NEXT rather than in progress between sets (#1074)", async () => {
    // Announcing "Set 2" the instant set 1 is awarded is the same overclaim as the old layout, just
    // relocated: between sets nothing is in progress — it is a decision point (#984).
    const user = userEvent.setup();
    useGetApiV1MatchesMatchIdLive.mockReturnValue({
      data: {
        ...liveView,
        isBetweenSets: true,
        sets: [{ gamesTeam1: 6, gamesTeam2: 4 }],
      },
      isLoading: false,
    });
    renderPage();
    await start(user);

    // Two live regions between sets, announcing different things: the label says WHICH set is next,
    // the prompt (#1070/#1075) says what to do about it. Queried by text rather than by role for
    // exactly that reason — `getByRole("status")` would find both and throw.
    const statuses = screen
      .getAllByRole("status")
      .map((node) => node.textContent?.trim());
    expect(statuses).toContain("Next: Set 2");
    expect(statuses).toContain(
      "Set complete. Start the next set, or finalize the match.",
    );
  });

  it("numbers every banked set, so three chips are not ambiguous (#1074)", async () => {
    const user = userEvent.setup();
    useGetApiV1MatchesMatchIdLive.mockReturnValue({
      data: {
        ...liveView,
        sets: [
          { gamesTeam1: 6, gamesTeam2: 4 },
          { gamesTeam1: 3, gamesTeam2: 6 },
        ],
      },
      isLoading: false,
    });
    renderPage();
    await start(user);

    expect(screen.getByText("S1")).toBeInTheDocument();
    expect(screen.getByText("S2")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Set 3");
  });

  it("renders Retire, Default and Switch sides as buttons, not text (#1071)", async () => {
    const user = userEvent.setup();
    renderPage();
    await start(user);

    // `ghost` has only a hover state — no border, no shadow — so these read as text at rest. A border
    // is the affordance, and it is exactly what ghost lacks, so that is what this asserts.
    for (const name of ["Ana retires", "Ana defaults", "Bob retires", "Bob defaults"]) {
      expect(screen.getByLabelText(name)).toHaveClass("border");
    }
    expect(screen.getByRole("button", { name: "Switch sides" })).toHaveClass("border");

    // Retire/Default keep a caution cue as well: the border is affordance, the red is danger, and
    // ending a match deserves both. Switch sides changes nothing, so it gets no warning colour.
    expect(screen.getByLabelText("Ana retires")).toHaveClass("text-destructive");
    expect(screen.getByRole("button", { name: "Switch sides" })).not.toHaveClass(
      "text-destructive",
    );
  });

  it("does not let disabled controls out-signal live ones between sets (#1071)", async () => {
    // The inversion this fixes: `disabled:opacity-50` applies to every variant, so a disabled
    // `outline` Game kept its border while a live `ghost` Retire had none — the control the umpire
    // could NOT use looked more pressable than the one they could. Both now carry a border, so the
    // difference between them is the disabled state alone rather than the presence of a button shape.
    const user = userEvent.setup();
    useGetApiV1MatchesMatchIdLive.mockReturnValue({
      data: { ...liveView, isBetweenSets: true },
      isLoading: false,
    });
    renderPage();
    await start(user);

    expect(screen.getByLabelText("Game to Ana")).toBeDisabled();
    expect(screen.getByLabelText("Ana retires")).toBeEnabled();
    expect(screen.getByLabelText("Game to Ana")).toHaveClass("border");
    expect(screen.getByLabelText("Ana retires")).toHaveClass("border");
  });

  it("stays silent about full screen while it is actually held (#1076)", async () => {
    const user = userEvent.setup();
    renderPage();
    await start(user);

    // `fullscreenElement` is set in the harness, and the hook now seeds its initial state from the
    // DOM — so a view that IS full-screen offers nothing. Before that seeding it reported `false`
    // until an event fired, which on a re-entered match would have been permanently wrong.
    expect(
      screen.queryByRole("button", { name: "Full screen" }),
    ).not.toBeInTheDocument();
  });

  it("offers a way back into full screen when it is not held (#1076)", async () => {
    Object.defineProperty(document, "fullscreenElement", {
      value: null,
      configurable: true,
    });
    const user = userEvent.setup();
    renderPage();
    await start(user);

    // The reading that makes the reported system bar attributable: a refused request, an iOS Safari
    // tab where element full-screen does not exist, or an installed PWA whose status bar is by
    // design. `enter()` swallows the rejection deliberately, so without this the failure is invisible.
    const control = screen.getByRole("button", { name: "Full screen" });
    expect(control).toHaveAttribute(
      "title",
      "This view is not full-screen, so the system bar is taking height from the board",
    );
    // A control rather than a warning, because re-entering needs a user gesture.
    await user.click(control);
    expect(document.documentElement.requestFullscreen).toHaveBeenCalled();
  });

  it("suggests installing on the confirm screen, and only when not installed (#1076)", async () => {
    // Installing is the only lever that helps iOS at all, and this screen is already a tap-gated
    // step seen once per match (#956).
    renderPage();
    expect(
      screen.getByText(/add Skopeo to your home screen/i),
    ).toBeInTheDocument();
    expect(screen.getByText(/Share → Add to Home Screen/)).toBeInTheDocument();
  });

  it("hides the install tip once the app is installed (#1076)", async () => {
    // A standing instruction to do something already done is noise.
    window.matchMedia = vi.fn().mockImplementation((query: string) => ({
      matches: query === "(display-mode: standalone)",
      media: query,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }));
    renderPage();
    expect(
      screen.queryByText(/add Skopeo to your home screen/i),
    ).not.toBeInTheDocument();
  });

  it("has exactly one Back control in the scoring view (#1073)", async () => {
    const user = userEvent.setup();
    renderPage();
    await start(user);

    // Two used to render side by side in the same header row. Asserting the count, not just the
    // survivor, is what stops a future change quietly reintroducing a claim-leaking second one.
    expect(screen.getAllByRole("button", { name: /Back/ })).toHaveLength(1);
  });

  it("keeps Back available even when everything else is inert (#986)", async () => {
    // The one control the gates must never touch: on a match that has not started, it is the only
    // way out.
    const user = userEvent.setup();
    useGetApiV1MatchesMatchIdLive.mockReturnValue({
      data: { ...liveView, hasStarted: false },
      isLoading: false,
    });
    renderPage();
    await start(user);

    expect(screen.getByRole("button", { name: "← Back" })).toBeEnabled();
  });

});
