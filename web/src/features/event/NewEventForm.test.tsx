import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { NewEventForm } from "./NewEventForm";

const {
  useGetApiV1Clubs,
  useGetApiV1Circuits,
  useGetApiV1UsersMe,
  useAwardFlag,
  createMutate,
  invalidate,
  state,
  toastError,
} = vi.hoisted(() => ({
  useGetApiV1Clubs: vi.fn(),
  useGetApiV1Circuits: vi.fn(),
  useGetApiV1UsersMe: vi.fn(),
  useAwardFlag: vi.fn(),
  createMutate: vi.fn(),
  invalidate: vi.fn(),
  state: { createFails: false },
  toastError: vi.fn(),
}));

vi.mock("@/api/generated/events/events", () => ({
  getGetApiV1EventsQueryKey: () => ["events"],
  usePostApiV1Events: (opts?: { mutation?: { onSuccess?: () => void } }) => ({
    isPending: false,
    mutate: (vars: unknown, handlers?: { onError?: () => void }) => {
      createMutate(vars);
      if (state.createFails) handlers?.onError?.();
      else opts?.mutation?.onSuccess?.();
    },
  }),
}));
vi.mock("@/api/generated/clubs/clubs", () => ({
  useGetApiV1Clubs,
  getGetApiV1ClubsCodeCodeQueryKey: (code: string) => ["clubs", code],
}));
vi.mock("@/api/generated/circuits/circuits", () => ({ useGetApiV1Circuits }));
vi.mock("@/api/generated/users/users", () => ({ useGetApiV1UsersMe }));
vi.mock("@/api/generated/settings/settings", () => ({
  useGetApiV1SettingsAwardRankingPoints: useAwardFlag,
}));
vi.mock("sonner", () => ({ toast: { error: toastError, success: vi.fn() } }));
vi.mock("@/components/PlayerPicker", () => ({
  PlayerPicker: ({
    placeholder,
    onSelect,
  }: {
    placeholder?: string;
    onSelect: (u: { id: string; publicCode: string; displayName: string }) => void;
  }) => (
    <button
      type="button"
      onClick={() => onSelect({ id: "u1", publicCode: "AAA111", displayName: "Ana" })}
    >
      {placeholder}
    </button>
  ),
}));
vi.mock("@tanstack/react-query", async () => {
  const actual =
    await vi.importActual<typeof import("@tanstack/react-query")>(
      "@tanstack/react-query",
    );
  return {
    ...actual,
    useQueryClient: () => ({ invalidateQueries: invalidate }),
  };
});

// "me" is a named owner of both clubs: after #789 the selector only lists clubs the caller may
// actually file under, so a caller who owns nothing would see no selector at all.
const OWNER = [{ userId: "me", publicCode: "OWN001" }];
const clubs = [
  {
    id: "c1",
    name: "Downtown TC",
    publicCode: "CLB001",
    isActive: true,
    owners: OWNER,
  },
  {
    id: "c2",
    name: "West End",
    publicCode: "CLB002",
    isActive: true,
    owners: OWNER,
  },
];

function renderForm(props?: { clubPublicCode?: string; publicCodeToRefresh?: string }) {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <NewEventForm clubPublicCode="CLB001" {...props} />
    </QueryClientProvider>,
  );
}

/** Fill the required fields and submit; the club comes from the surface, not the form. */
async function fillAndSubmit() {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText("Name"), "Club Cup");
  await user.type(screen.getByLabelText("Start date"), "2026-06-01");
  await user.type(screen.getByLabelText("End date"), "2026-06-02");
  await user.click(screen.getByRole("button", { name: "Create event" }));
}

describe("NewEventForm — fixed club (#780)", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.createFails = false;
    useGetApiV1Clubs.mockReturnValue({ data: clubs, isLoading: false });
    useGetApiV1Circuits.mockReturnValue({ data: [], isLoading: false });
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: "me", capabilities: ["HOST"] },
    });
    useAwardFlag.mockReturnValue({ data: { enabled: false } });
  });

  it("hides the club selector and files under the fixed club", async () => {
    renderForm({ clubPublicCode: "CLB001" });

    // The page already answers "which club", so the choice isn't offered again.
    expect(screen.queryByLabelText("Club")).not.toBeInTheDocument();

    await fillAndSubmit();

    expect(createMutate).toHaveBeenCalledWith({
      data: {
        name: "Club Cup",
        startDate: "2026-06-01",
        endDate: "2026-06-02",
        type: "OPEN_PLAY",
        format: "SINGLES",
        participantIds: [],
        // Resolved from the public code, not chosen in the form.
        clubId: "c1",
        awardRankingPoints: false,
      },
    });
  });



  it("hides the selector entirely from a host who owns no club (#789)", () => {
    useGetApiV1Clubs.mockReturnValue({
      data: clubs.map((c) => ({ ...c, owners: [] })),
      isLoading: false,
    });
    renderForm();

    expect(screen.queryByLabelText("Club")).not.toBeInTheDocument();
  });


  it("blocks submission until the fixed club's id resolves", async () => {
    // Clubs not loaded yet: submitting now would silently file the event as "Open".
    useGetApiV1Clubs.mockReturnValue({ data: undefined, isLoading: true });
    renderForm({ clubPublicCode: "CLB001" });

    const user = userEvent.setup();
    await user.type(screen.getByLabelText("Name"), "Club Cup");
    await user.type(screen.getByLabelText("Start date"), "2026-06-01");
    await user.type(screen.getByLabelText("End date"), "2026-06-02");

    expect(screen.getByRole("button", { name: "Create event" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Create event" }));
    expect(createMutate).not.toHaveBeenCalled();
  });

  it("refreshes the club page's own query after creating", async () => {
    renderForm({ clubPublicCode: "CLB001", publicCodeToRefresh: "CLB001" });
    await fillAndSubmit();

    // The new event must appear in the club page's listing without a reload.
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["clubs", "CLB001"] });
  });

  it("does not touch a club page query when none was named", async () => {
    renderForm();
    await fillAndSubmit();

    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["events"] });
    expect(invalidate).not.toHaveBeenCalledWith({
      queryKey: ["clubs", "CLB001"],
    });
  });



  // These moved here with the form (#794): the Event Organizer tab no longer renders NewEventForm, so its
  // test can't cover it any more. They belong with the component regardless.
  describe("create payload", () => {
    async function fillBasics(user: ReturnType<typeof userEvent.setup>) {
      await user.type(screen.getByLabelText("Name"), "Summer Open");
      await user.type(screen.getByLabelText("Start date"), "2026-06-01");
      await user.type(screen.getByLabelText("End date"), "2026-06-02");
          }

    it("stages a roster and sends it, de-duplicating repeats", async () => {
      const user = userEvent.setup();
      renderForm();
      await fillBasics(user);

      // The mocked picker always returns the same player; adding twice must not duplicate them.
      await user.click(screen.getByRole("button", { name: /Search players/ }));
      await user.click(screen.getByRole("button", { name: /Search players/ }));
      await user.click(screen.getByRole("button", { name: "Create event" }));

      expect(createMutate).toHaveBeenCalledWith({
        data: expect.objectContaining({ participantIds: ["u1"] }),
      });
    });

    it("sends the chosen event type (#403)", async () => {
      const user = userEvent.setup();
      useGetApiV1Circuits.mockReturnValue({
        data: [{ id: "ci1", name: "NORTH" }],
        isLoading: false,
      });
      renderForm();
      await fillBasics(user);
      await user.selectOptions(screen.getByLabelText("Type"), "TOURNAMENT");
      // A tournament must name a circuit (#525), so it gates submission until one is chosen.
      expect(screen.getByRole("button", { name: "Create event" })).toBeDisabled();
      await user.selectOptions(screen.getByLabelText("Circuit"), "ci1");
      await user.click(screen.getByRole("button", { name: "Create event" }));

      expect(createMutate).toHaveBeenCalledWith({
        data: expect.objectContaining({ type: "TOURNAMENT", circuitId: "ci1" }),
      });
    });

    it("sends the chosen organizing format (#720)", async () => {
      const user = userEvent.setup();
      renderForm();
      await fillBasics(user);
      await user.selectOptions(screen.getByLabelText("Format"), "DOUBLES");
      await user.click(screen.getByRole("button", { name: "Create event" }));

      expect(createMutate).toHaveBeenCalledWith({
        data: expect.objectContaining({ format: "DOUBLES" }),
      });
    });

    it("defaults award-ranking-points off and only offers it when the flag is on (#567, #641)", async () => {
      const user = userEvent.setup();
      renderForm();
      // Flag off: no checkbox, and the payload opts out.
      expect(
        screen.queryByLabelText(/Award Ranking Points/i),
      ).not.toBeInTheDocument();
      await fillBasics(user);
      await user.click(screen.getByRole("button", { name: "Create event" }));

      expect(createMutate).toHaveBeenCalledWith({
        data: expect.objectContaining({ awardRankingPoints: false }),
      });
    });

    it("surfaces an error when the create fails", async () => {
      const user = userEvent.setup();
      state.createFails = true;
      renderForm();
      await fillBasics(user);
      await user.click(screen.getByRole("button", { name: "Create event" }));

      expect(toastError).toHaveBeenCalled();
    });
  });

  it("offers and sends the award-points opt-in when the flag is enabled (#641)", async () => {
    useAwardFlag.mockReturnValue({ data: { enabled: true } });
    const user = userEvent.setup();
    renderForm();

    await user.type(screen.getByLabelText("Name"), "Summer Open");
    await user.type(screen.getByLabelText("Start date"), "2026-06-01");
    await user.type(screen.getByLabelText("End date"), "2026-06-02");
        await user.click(screen.getByLabelText("Award Ranking Points"));
    await user.click(screen.getByRole("button", { name: "Create event" }));

    expect(createMutate).toHaveBeenCalledWith({
      data: expect.objectContaining({ awardRankingPoints: true }),
    });
  });

  it("removes a staged participant before creating", async () => {
    const user = userEvent.setup();
    renderForm();

    await user.type(screen.getByLabelText("Name"), "Summer Open");
    await user.type(screen.getByLabelText("Start date"), "2026-06-01");
    await user.type(screen.getByLabelText("End date"), "2026-06-02");
        await user.click(screen.getByRole("button", { name: /Search players/ }));

    // Clicking the staged chip takes them back off the roster.
    await user.click(screen.getByRole("button", { name: /Ana/ }));
    await user.click(screen.getByRole("button", { name: "Create event" }));

    expect(createMutate).toHaveBeenCalledWith({
      data: expect.objectContaining({ participantIds: [] }),
    });
  });
});
