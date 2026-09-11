import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { EditFixturePlayersDialog } from "./EditFixturePlayersDialog";
import type {
  EventParticipantResponse,
  MatchResponse,
} from "@/api/generated/model";

const { usePutApiV1MatchesIdPlayers } = vi.hoisted(() => ({
  usePutApiV1MatchesIdPlayers: vi.fn(),
}));
vi.mock("@/api/generated/matches/matches", () => ({
  usePutApiV1MatchesIdPlayers,
}));
vi.mock("sonner", () => ({ toast: { success: vi.fn(), error: vi.fn() } }));
vi.mock("@tanstack/react-query", () => ({
  useQueryClient: () => ({ invalidateQueries: vi.fn() }),
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

// The event's roster. Ana and Bob are the two already playing; Cara is a spare, and the unnamed one
// is a placeholder player (#496), who is a legitimate pick.
const participants: EventParticipantResponse[] = [
  { userId: "u-1", displayName: "Ana", publicCode: "AAA111" },
  { userId: "u-2", displayName: "Bob", publicCode: "BBB222" },
  { userId: "u-new", displayName: "Cara", publicCode: "CCC333" },
  {
    userId: "u-anon",
    displayName: null,
    publicCode: "DDD444",
    isPlaceholder: true,
  },
];

const names: Record<string, string> = {
  "u-1": "Ana",
  "u-2": "Bob",
  "u-new": "Cara",
};

function renderDialog(
  m: MatchResponse = match,
  roster: EventParticipantResponse[] = participants,
) {
  return render(
    <EditFixturePlayersDialog
      match={m}
      participants={roster}
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

  it("offers only the event's participants, never every player in the system", async () => {
    // #971: the server refuses a non-participant, so anyone off the roster is an option that can
    // only answer 400. The old global search made that the common case rather than the exception.
    const user = userEvent.setup();
    renderDialog();

    await user.click(
      screen.getByRole("button", { name: "Remove Ana from Side 1" }),
    );
    const options = within(screen.getByLabelText("Add to Side 1"))
      .getAllByRole("option")
      .map((o) => (o as HTMLOptionElement).value);

    expect(options).toEqual(["", "u-1", "u-new", "u-anon"]);
  });

  it("does not offer anyone already on either side", async () => {
    // Both sides, not just this one: the server rejects a player listed twice in one fixture.
    const user = userEvent.setup();
    renderDialog();

    await user.click(
      screen.getByRole("button", { name: "Remove Ana from Side 1" }),
    );
    const options = within(screen.getByLabelText("Add to Side 1"))
      .getAllByRole("option")
      .map((o) => (o as HTMLOptionElement).value);

    expect(options).not.toContain("u-2");
  });

  it("swaps a player and sends both sides whole", async () => {
    const user = userEvent.setup();
    renderDialog();

    await user.click(
      screen.getByRole("button", { name: "Remove Ana from Side 1" }),
    );
    await user.selectOptions(screen.getByLabelText("Add to Side 1"), "u-new");
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

    await user.click(
      screen.getByRole("button", { name: "Remove Ana from Side 1" }),
    );
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

  it("surfaces the server's reason verbatim", async () => {
    // Scoping the picker narrows the gap but does not close it: the roster was fetched before the
    // dialog opened, and someone may have been withdrawn since. Swallowing the message would leave
    // the organizer with no idea why the save failed.
    const { toast } = await import("sonner");
    renderDialog();

    saveOnError?.({
      response: {
        data: { message: "All players must be participants of the event" },
      },
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
        participants={participants}
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
        participants={participants}
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

    await user.click(
      screen.getByRole("button", { name: "Remove Bob from Side 2" }),
    );
    await user.selectOptions(screen.getByLabelText("Add to Side 2"), "u-new");
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

    await user.click(
      screen.getByRole("button", { name: "Remove Ana from Side 1" }),
    );
    await user.selectOptions(screen.getByLabelText("Add to Side 1"), "u-anon");

    expect(screen.getByText("Unknown")).toBeInTheDocument();
  });

  it("offers nothing when the roster is empty", async () => {
    // An event with no approved participants has nobody to field. The select still renders, so the
    // organizer sees an empty roster rather than a missing control.
    const user = userEvent.setup();
    renderDialog(match, []);

    await user.click(
      screen.getByRole("button", { name: "Remove Ana from Side 1" }),
    );
    expect(
      within(screen.getByLabelText("Add to Side 1")).getAllByRole("option"),
    ).toHaveLength(1);
  });
});
