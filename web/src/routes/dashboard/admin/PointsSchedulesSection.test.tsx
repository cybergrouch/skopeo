import { describe, it, expect, beforeEach, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { PointsSchedulesSection } from "./PointsSchedulesSection";

const { useGetOpenPlay, useGetTournament, putOpenPlay, putTournament, shouldFail } = vi.hoisted(() => ({
  useGetOpenPlay: vi.fn(),
  useGetTournament: vi.fn(),
  putOpenPlay: vi.fn(),
  putTournament: vi.fn(),
  shouldFail: { value: false },
}));

// The PUT mocks drive the real mutation callbacks: on success they record the payload and fire
// onSuccess (→ "Saved"); when shouldFail is set they fire onError (→ the inline error).
vi.mock("@/api/generated/settings/settings", () => ({
  useGetApiV1SettingsPointsOpenPlay: useGetOpenPlay,
  useGetApiV1SettingsPointsTournament: useGetTournament,
  usePutApiV1SettingsPointsOpenPlay: (opts: { mutation: { onSuccess: () => void; onError: () => void } }) => ({
    isPending: false,
    mutate: (vars: unknown) => {
      if (shouldFail.value) opts.mutation.onError();
      else {
        putOpenPlay(vars);
        opts.mutation.onSuccess();
      }
    },
  }),
  usePutApiV1SettingsPointsTournament: (opts: { mutation: { onSuccess: () => void; onError: () => void } }) => ({
    isPending: false,
    mutate: (vars: unknown) => {
      if (shouldFail.value) opts.mutation.onError();
      else {
        putTournament(vars);
        opts.mutation.onSuccess();
      }
    },
  }),
  getGetApiV1SettingsPointsOpenPlayQueryKey: () => ["open-play"],
  getGetApiV1SettingsPointsTournamentQueryKey: () => ["tournament"],
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

// The two Save buttons in DOM order: [0] = open-play card, [1] = tournament card.
const saveButtons = () => screen.getAllByRole("button", { name: "Save" });

describe("PointsSchedulesSection", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    shouldFail.value = false;
    useGetOpenPlay.mockReturnValue({ data: { config: openPlayConfig }, isLoading: false });
    useGetTournament.mockReturnValue({ data: { config: tournamentConfig }, isLoading: false });
  });

  it("renders the open-play margin grid and tournament schedule from config", () => {
    renderSection();
    expect((screen.getByLabelText("Upset margin 1 winner points") as HTMLInputElement).value).toBe("5");
    expect((screen.getByLabelText("Upset margin 1 loser points") as HTMLInputElement).value).toBe("-2");
    expect((screen.getByLabelText("sanctioned 1st points") as HTMLInputElement).value).toBe("80");
    expect((screen.getByLabelText("unsanctioned 3rd points") as HTMLInputElement).value).toBe("20");
  });

  it("edits open-play winner, loser and validity, saves, and confirms (diverse increments allowed)", () => {
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
    // onSuccess fired → the "Saved" status shows.
    expect(screen.getAllByRole("status").some((el) => el.textContent === "Saved")).toBe(true);
  });

  it("edits tournament sanctioned, unsanctioned and validity, then saves", () => {
    renderSection();
    fireEvent.change(screen.getByLabelText("sanctioned 1st points"), { target: { value: "100" } });
    fireEvent.change(screen.getByLabelText("unsanctioned 4th points"), { target: { value: "18" } });
    fireEvent.change(screen.getByLabelText("tournament validity days"), { target: { value: "180" } });
    fireEvent.click(saveButtons()[1]);

    expect(putTournament).toHaveBeenCalledTimes(1);
    const sent = putTournament.mock.calls[0][0].data;
    expect(sent.sanctioned[0]).toBe(100);
    expect(sent.unsanctioned[3]).toBe(18);
    expect(sent.validityDays).toBe(180);
  });

  it("shows an inline error when either save is unauthorized", () => {
    shouldFail.value = true;
    renderSection();
    fireEvent.click(saveButtons()[0]); // open-play → onError
    fireEvent.click(saveButtons()[1]); // tournament → onError
    const alerts = screen.getAllByRole("alert");
    expect(alerts.length).toBeGreaterThanOrEqual(2);
    expect(alerts[0].textContent).toMatch(/administrator access/i);
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

  it("shows a loading state until config arrives", () => {
    useGetOpenPlay.mockReturnValue({ data: undefined, isLoading: true });
    useGetTournament.mockReturnValue({ data: undefined, isLoading: true });
    renderSection();
    expect(screen.getAllByText("Loading…").length).toBeGreaterThan(0);
  });
});
