import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { EditFixturePlayersDialog } from "./EditFixturePlayersDialog";
import type { MatchResponse } from "@/api/generated/model";

const { usePutApiV1MatchesIdPlayers } = vi.hoisted(() => ({
  usePutApiV1MatchesIdPlayers: vi.fn(),
}));
vi.mock("@/api/generated/matches/matches", () => ({ usePutApiV1MatchesIdPlayers }));
vi.mock("sonner", () => ({ toast: { success: vi.fn(), error: vi.fn() } }));
vi.mock("@tanstack/react-query", () => ({
  useQueryClient: () => ({ invalidateQueries: vi.fn() }),
}));
// The picker has its own tests; here it only needs to hand back a chosen player.
vi.mock("@/components/PlayerPicker", () => ({
  PlayerPicker: ({
    label,
    onSelect,
  }: {
    label: string;
    onSelect: (p: { id: string; displayName: string | null }) => void;
  }) => (
    <>
      <button onClick={() => onSelect({ id: "u-new", displayName: "Cara" })}>
        {label}
      </button>
      {/* A placeholder player legitimately has no display name (#496). */}
      <button onClick={() => onSelect({ id: "u-anon", displayName: null })}>
        {label} (unnamed)
      </button>
    </>
  ),
}));

const saveMutate = vi.fn();
let saveOnError: ((e: unknown) => void) | undefined;
let saveOnSuccess: (() => void) | undefined;

const match = {
  id: "m-1",
  matchFormat: "SINGLES",
  team1: { teamId: "t1", userIds: ["u-1"] },
  team2: { teamId: "t2", userIds: ["u-2"] },
  sets: [],
} as unknown as MatchResponse;

const names: Record<string, string> = { "u-1": "Ana", "u-2": "Bob", "u-new": "Cara" };

function renderDialog(m: MatchResponse = match) {
  return render(
    <EditFixturePlayersDialog
      match={m}
      nameOf={(id) => names[id] ?? id}
      onClose={vi.fn()}
    />,
  );
}

describe("EditFixturePlayersDialog", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    usePutApiV1MatchesIdPlayers.mockImplementation(
      (opts?: {
        mutation?: { onError?: (e: unknown) => void; onSuccess?: () => void };
      }) => {
        saveOnError = opts?.mutation?.onError;
        saveOnSuccess = opts?.mutation?.onSuccess;
        return { mutate: saveMutate, isPending: false };
      },
    );
  });

  it("shows the current players", () => {
    renderDialog();
    expect(screen.getByText("Ana")).toBeInTheDocument();
    expect(screen.getByText("Bob")).toBeInTheDocument();
  });

  it("cannot save until something has actually changed", () => {
    // Saving an unchanged line-up would write an audit entry recording a change that did not happen.
    renderDialog();
    expect(screen.getByRole("button", { name: "Save players" })).toBeDisabled();
  });

  it("swaps a player and sends both sides whole", async () => {
    const user = userEvent.setup();
    renderDialog();

    await user.click(screen.getByRole("button", { name: "Remove Ana from Side 1" }));
    await user.click(screen.getByRole("button", { name: "Add to Side 1" }));
    await user.click(screen.getByRole("button", { name: "Save players" }));

    // Both sides, not a patch: "unchanged" and "cleared" would otherwise look the same.
    expect(saveMutate).toHaveBeenCalledWith({
      id: "m-1",
      data: { team1: ["u-new"], team2: ["u-2"] },
    });
  });

  it("cannot save a side that is short of players", async () => {
    const user = userEvent.setup();
    renderDialog();

    await user.click(screen.getByRole("button", { name: "Remove Ana from Side 1" }));
    // A singles fixture with an empty side must not be submittable — the server refuses it, and
    // offering the button would be a control that answers 400.
    expect(screen.getByRole("button", { name: "Save players" })).toBeDisabled();
    expect(screen.getByText(/One player per side/)).toBeInTheDocument();
  });

  it("requires two per side for doubles", async () => {
    const doubles = {
      ...match,
      matchFormat: "DOUBLES",
      team1: { teamId: "t1", userIds: ["u-1"] },
      team2: { teamId: "t2", userIds: ["u-2"] },
    } as unknown as MatchResponse;
    renderDialog(doubles);

    // Silently turning doubles into singles would break every count that trusts the format.
    expect(screen.getByRole("button", { name: "Save players" })).toBeDisabled();
    expect(screen.getByText(/Two players per side/)).toBeInTheDocument();
  });

  it("surfaces the server's reason verbatim, since the picker cannot pre-filter", async () => {
    // The picker has no event filter, so a non-participant IS reachable here and the server rejects
    // it. Swallowing that message would leave the organizer with no idea why the save failed.
    const { toast } = await import("sonner");
    renderDialog();

    saveOnError?.({
      response: { data: { message: "All players must be participants of the event" } },
    });
    expect(toast.error).toHaveBeenCalledWith(
      "All players must be participants of the event",
    );
  });

  it("falls back to a generic message when the server gives no reason", async () => {
    const { toast } = await import("sonner");
    renderDialog();

    saveOnError?.({});
    expect(toast.error).toHaveBeenCalledWith("Could not change the players");
  });

  it("closes on success", async () => {
    const onClose = vi.fn();
    render(
      <EditFixturePlayersDialog
        match={match}
        nameOf={(id) => names[id] ?? id}
        onClose={onClose}
      />,
    );

    saveOnSuccess?.();
    expect(onClose).toHaveBeenCalled();
  });

  it("cancelling leaves without saving", async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(
      <EditFixturePlayersDialog
        match={match}
        nameOf={(id) => names[id] ?? id}
        onClose={onClose}
      />,
    );

    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(onClose).toHaveBeenCalled();
    expect(saveMutate).not.toHaveBeenCalled();
  });

  it("edits side 2 as well as side 1", async () => {
    // The row renderer is shared, so a slip that wired both sides to the same state would be invisible
    // from side 1 alone.
    const user = userEvent.setup();
    renderDialog();

    await user.click(screen.getByRole("button", { name: "Remove Bob from Side 2" }));
    await user.click(screen.getByRole("button", { name: "Add to Side 2" }));
    await user.click(screen.getByRole("button", { name: "Save players" }));

    expect(saveMutate).toHaveBeenCalledWith({
      id: "m-1",
      data: { team1: ["u-1"], team2: ["u-new"] },
    });
  });

  it("shows a placeholder with no display name as Unknown rather than blank", async () => {
    // Placeholder players (#496) have no display name, and a blank row would look like a rendering
    // fault rather than a real, selectable person.
    const user = userEvent.setup();
    renderDialog();

    await user.click(screen.getByRole("button", { name: "Remove Ana from Side 1" }));
    await user.click(screen.getByRole("button", { name: "Add to Side 1 (unnamed)" }));

    expect(screen.getByText("Unknown")).toBeInTheDocument();
  });
});
