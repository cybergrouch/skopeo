import { describe, it, expect, beforeEach, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { PointsSchedulesSection } from "./PointsSchedulesSection";

const { toastSuccess, toastError } = vi.hoisted(() => ({
  toastSuccess: vi.fn(),
  toastError: vi.fn(),
}));
vi.mock("sonner", () => ({ toast: { success: toastSuccess, error: toastError } }));

const {
  useGetOpenPlay,
  useGetTournament,
  useGetFullMatch,
  putOpenPlay,
  putTournament,
  putFullMatch,
  shouldFail,
  pendingSave,
} = vi.hoisted(() => ({
  useGetOpenPlay: vi.fn(),
  useGetTournament: vi.fn(),
  useGetFullMatch: vi.fn(),
  putOpenPlay: vi.fn(),
  putTournament: vi.fn(),
  putFullMatch: vi.fn(),
  shouldFail: { value: false },
  pendingSave: { value: false },
}));

// The PUT mocks drive the real mutation callbacks: on success they record the payload and fire
// onSuccess (→ toast.success); when shouldFail is set they fire onError (→ toast.error).
vi.mock("@/api/generated/settings/settings", () => ({
  useGetApiV1SettingsPointsOpenPlay: useGetOpenPlay,
  useGetApiV1SettingsPointsTournament: useGetTournament,
  useGetApiV1SettingsPointsFullMatch: useGetFullMatch,
  usePutApiV1SettingsPointsOpenPlay: (opts: { mutation: { onSuccess: () => void; onError: () => void } }) => ({
    isPending: pendingSave.value,
    mutate: (vars: unknown) => {
      if (shouldFail.value) opts.mutation.onError();
      else {
        putOpenPlay(vars);
        opts.mutation.onSuccess();
      }
    },
  }),
  usePutApiV1SettingsPointsTournament: (opts: { mutation: { onSuccess: () => void; onError: () => void } }) => ({
    isPending: pendingSave.value,
    mutate: (vars: unknown) => {
      if (shouldFail.value) opts.mutation.onError();
      else {
        putTournament(vars);
        opts.mutation.onSuccess();
      }
    },
  }),
  usePutApiV1SettingsPointsFullMatch: (opts: { mutation: { onSuccess: () => void; onError: () => void } }) => ({
    isPending: pendingSave.value,
    mutate: (vars: unknown) => {
      if (shouldFail.value) opts.mutation.onError();
      else {
        putFullMatch(vars);
        opts.mutation.onSuccess();
      }
    },
  }),
  getGetApiV1SettingsPointsOpenPlayQueryKey: () => ["open-play"],
  getGetApiV1SettingsPointsTournamentQueryKey: () => ["tournament"],
  getGetApiV1SettingsPointsFullMatchQueryKey: () => ["full-match"],
}));

// A small 2-margin open-play config (3 relations × 2 margins) mirroring the current defaults.
const openPlayConfig = {
  maxMargin: 2,
  validityDays: 61,
  rows: [
    { relation: "EQUAL", margin: 1, winnerPoints: 3, loserPoints: 0 },
    { relation: "FAVORITE", margin: 1, winnerPoints: 2, loserPoints: 1 },
    { relation: "UPSET", margin: 1, winnerPoints: 5, loserPoints: -2 },
    { relation: "EQUAL", margin: 2, winnerPoints: 3, loserPoints: 0 },
    { relation: "FAVORITE", margin: 2, winnerPoints: 2, loserPoints: 1 },
    { relation: "UPSET", margin: 2, winnerPoints: 5, loserPoints: -2 },
  ],
};

const tournamentConfig = {
  sanctioned: [80, 60, 40, 30],
  unsanctioned: [40, 30, 20, 15],
  validityDays: 365,
};

function renderSection() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <PointsSchedulesSection />
    </QueryClientProvider>,
  );
}

// The Save buttons in DOM order, which follows the cards' ascending scale:
// [0] = open-play, [1] = full-match (#840), [2] = tournament.
const saveButtons = () => screen.getAllByRole("button", { name: "Save" });

describe("PointsSchedulesSection", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    shouldFail.value = false;
    pendingSave.value = false;
    useGetOpenPlay.mockReturnValue({ data: { config: openPlayConfig }, isLoading: false });
    useGetTournament.mockReturnValue({ data: { config: tournamentConfig }, isLoading: false });
    useGetFullMatch.mockReturnValue({ data: { config: { validityDays: 182 } }, isLoading: false });
  });

  it("renders the open-play margin grid and tournament schedule from config", () => {
    renderSection();
    expect((screen.getByLabelText("Upset margin 1 winner points") as HTMLInputElement).value).toBe("5");
    expect((screen.getByLabelText("Upset margin 1 loser points") as HTMLInputElement).value).toBe("-2");
    expect((screen.getByLabelText("sanctioned 1st points") as HTMLInputElement).value).toBe("80");
    expect((screen.getByLabelText("unsanctioned 3rd points") as HTMLInputElement).value).toBe("20");
  });

  it("edits open-play winner, loser and validity, saves, and confirms (diverse increments allowed)", async () => {
    renderSection();
    // Fibonacci-style dominance on a winner cell, a loser edit, and the study's 3-month validity.
    fireEvent.change(screen.getByLabelText("Upset margin 2 winner points"), { target: { value: "13" } });
    fireEvent.change(screen.getByLabelText("Favorite margin 1 loser points"), { target: { value: "2" } });
    fireEvent.change(screen.getByLabelText("open-play validity days"), { target: { value: "91" } });
    fireEvent.click(saveButtons()[0]);

    expect(putOpenPlay).toHaveBeenCalledTimes(1);
    const sent = putOpenPlay.mock.calls[0][0].data;
    expect(
      sent.rows.find((r: { relation: string; margin: number }) => r.relation === "UPSET" && r.margin === 2).winnerPoints,
    ).toBe(13);
    expect(
      sent.rows.find((r: { relation: string; margin: number }) => r.relation === "FAVORITE" && r.margin === 1).loserPoints,
    ).toBe(2);
    expect(sent.validityDays).toBe(91);
    // onSuccess fired → a success toast shows.
    await waitFor(() => expect(toastSuccess).toHaveBeenCalledWith("Saved"));
  });

  it("edits tournament sanctioned, unsanctioned and validity, then saves", () => {
    renderSection();
    fireEvent.change(screen.getByLabelText("sanctioned 1st points"), { target: { value: "100" } });
    fireEvent.change(screen.getByLabelText("unsanctioned 4th points"), { target: { value: "18" } });
    fireEvent.change(screen.getByLabelText("tournament validity days"), { target: { value: "180" } });
    fireEvent.click(saveButtons()[2]);

    expect(putTournament).toHaveBeenCalledTimes(1);
    const sent = putTournament.mock.calls[0][0].data;
    expect(sent.sanctioned[0]).toBe(100);
    expect(sent.unsanctioned[3]).toBe(18);
    expect(sent.validityDays).toBe(180);
  });

  it("shows an error toast when either save is unauthorized", async () => {
    shouldFail.value = true;
    renderSection();
    fireEvent.click(saveButtons()[0]); // open-play → onError
    fireEvent.click(saveButtons()[2]); // tournament → onError
    await waitFor(() => expect(toastError).toHaveBeenCalledTimes(2));
    expect(toastError).toHaveBeenCalledWith(
      expect.stringMatching(/administrator access/i),
      { duration: 8000 },
    );
    expect(putOpenPlay).not.toHaveBeenCalled();
    expect(putTournament).not.toHaveBeenCalled();
  });

  it("falls back to 0 for a margin cell missing from the config", () => {
    // maxMargin 2 but only margin-1 rows → the margin-2 cells hit the `?? 0` fallback.
    useGetOpenPlay.mockReturnValue({
      data: {
        config: {
          maxMargin: 2,
          validityDays: 61,
          rows: [
            { relation: "EQUAL", margin: 1, winnerPoints: 3, loserPoints: 0 },
            { relation: "FAVORITE", margin: 1, winnerPoints: 2, loserPoints: 1 },
            { relation: "UPSET", margin: 1, winnerPoints: 5, loserPoints: -2 },
          ],
        },
      },
      isLoading: false,
    });
    renderSection();
    expect((screen.getByLabelText("Equal margin 2 winner points") as HTMLInputElement).value).toBe("0");
    expect((screen.getByLabelText("Upset margin 2 loser points") as HTMLInputElement).value).toBe("0");
  });

  it("shows 'Saving…' and disables Save while a save is in flight", () => {
    pendingSave.value = true;
    renderSection();
    const saving = screen.getAllByRole("button", { name: "Saving…" });
    expect(saving.length).toBe(3);
    saving.forEach((b) => expect(b).toBeDisabled());
  });

  it("saves the full-match validity window on its own, without touching the shared amounts (#840)", () => {
    renderSection();
    // The card offers only a window — a full match earns the open-play amounts, so there is no second
    // amount table to edit here. Guards against a Full Match schedule creeping back in.
    const window = screen.getByLabelText("full-match validity days") as HTMLInputElement;
    expect(window.value).toBe("182");

    fireEvent.change(window, { target: { value: "200" } });
    fireEvent.click(saveButtons()[1]);

    expect(putFullMatch).toHaveBeenCalledWith({ data: { validityDays: 200 } });
    // And the shared open-play amounts were not resaved as a side effect.
    expect(putOpenPlay).not.toHaveBeenCalled();
  });

  it("shows a loading state until config arrives", () => {
    useGetOpenPlay.mockReturnValue({ data: undefined, isLoading: true });
    useGetTournament.mockReturnValue({ data: undefined, isLoading: true });
    renderSection();
    expect(screen.getAllByText("Loading…").length).toBeGreaterThan(0);
  });
});
