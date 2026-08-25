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
} = vi.hoisted(() => ({
  useGetApiV1Clubs: vi.fn(),
  useGetApiV1Circuits: vi.fn(),
  useGetApiV1UsersMe: vi.fn(),
  useAwardFlag: vi.fn(),
  createMutate: vi.fn(),
  invalidate: vi.fn(),
}));

vi.mock("@/api/generated/events/events", () => ({
  getGetApiV1EventsQueryKey: () => ["events"],
  usePostApiV1Events: (opts?: { mutation?: { onSuccess?: () => void } }) => ({
    isPending: false,
    mutate: (vars: unknown) => {
      createMutate(vars);
      opts?.mutation?.onSuccess?.();
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
vi.mock("sonner", () => ({ toast: { error: vi.fn(), success: vi.fn() } }));
vi.mock("@/components/PlayerPicker", () => ({
  PlayerPicker: () => <div>picker</div>,
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

const clubs = [
  { id: "c1", name: "Downtown TC", publicCode: "CLB001", isActive: true, owners: [] },
  { id: "c2", name: "West End", publicCode: "CLB002", isActive: true, owners: [] },
];

function renderForm(props?: {
  fixedClubPublicCode?: string;
  publicCodeToRefresh?: string;
}) {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <NewEventForm {...props} />
    </QueryClientProvider>,
  );
}

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
    useGetApiV1Clubs.mockReturnValue({ data: clubs, isLoading: false });
    useGetApiV1Circuits.mockReturnValue({ data: [], isLoading: false });
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: "me", capabilities: ["HOST"] },
    });
    useAwardFlag.mockReturnValue({ data: { enabled: false } });
  });

  it("hides the club selector and files under the fixed club", async () => {
    renderForm({ fixedClubPublicCode: "CLB001" });

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

  it("still offers the selector when no club is fixed", () => {
    renderForm();
    expect(screen.getByLabelText("Club")).toBeInTheDocument();
  });

  it("blocks submission until the fixed club's id resolves", async () => {
    // Clubs not loaded yet: submitting now would silently file the event as "Open".
    useGetApiV1Clubs.mockReturnValue({ data: undefined, isLoading: true });
    renderForm({ fixedClubPublicCode: "CLB001" });

    const user = userEvent.setup();
    await user.type(screen.getByLabelText("Name"), "Club Cup");
    await user.type(screen.getByLabelText("Start date"), "2026-06-01");
    await user.type(screen.getByLabelText("End date"), "2026-06-02");

    expect(screen.getByRole("button", { name: "Create event" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Create event" }));
    expect(createMutate).not.toHaveBeenCalled();
  });

  it("refreshes the club page's own query after creating", async () => {
    renderForm({ fixedClubPublicCode: "CLB001", publicCodeToRefresh: "CLB001" });
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
});
