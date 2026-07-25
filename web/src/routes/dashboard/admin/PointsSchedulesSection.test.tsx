import { describe, it, expect, beforeEach, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { PointsSchedulesSection } from "./PointsSchedulesSection";

const { useGetOpenPlay, useGetTournament, putOpenPlay, putTournament } = vi.hoisted(() => ({
  useGetOpenPlay: vi.fn(),
  useGetTournament: vi.fn(),
  putOpenPlay: vi.fn(),
  putTournament: vi.fn(),
}));

vi.mock("@/api/generated/settings/settings", () => ({
  useGetApiV1SettingsPointsOpenPlay: useGetOpenPlay,
  useGetApiV1SettingsPointsTournament: useGetTournament,
  usePutApiV1SettingsPointsOpenPlay: () => ({ mutate: putOpenPlay, isPending: false }),
  usePutApiV1SettingsPointsTournament: () => ({ mutate: putTournament, isPending: false }),
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

describe("PointsSchedulesSection", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useGetOpenPlay.mockReturnValue({ data: { config: openPlayConfig }, isLoading: false });
    useGetTournament.mockReturnValue({ data: { config: tournamentConfig }, isLoading: false });
  });

  it("renders the open-play margin grid and tournament schedule from config", () => {
    renderSection();
    // Open-play: the upset margin-1 winner cell shows 5, its loser cell −2.
    expect((screen.getByLabelText("Upset margin 1 winner points") as HTMLInputElement).value).toBe("5");
    expect((screen.getByLabelText("Upset margin 1 loser points") as HTMLInputElement).value).toBe("-2");
    // Tournament: sanctioned 1st = 80, unsanctioned 3rd = 20.
    expect((screen.getByLabelText("sanctioned 1st points") as HTMLInputElement).value).toBe("80");
    expect((screen.getByLabelText("unsanctioned 3rd points") as HTMLInputElement).value).toBe("20");
  });

  it("saves an edited open-play cell (diverse increments allowed)", () => {
    renderSection();
    // Enter a Fibonacci-style dominance value on the upset margin-2 winner cell.
    fireEvent.change(screen.getByLabelText("Upset margin 2 winner points"), {
      target: { value: "13" },
    });
    // The open-play card's Save is the first of the two Save buttons.
    fireEvent.click(screen.getAllByRole("button", { name: "Save" })[0]);

    expect(putOpenPlay).toHaveBeenCalledTimes(1);
    const sent = putOpenPlay.mock.calls[0][0].data;
    expect(sent.rows.find((r: { relation: string; margin: number }) => r.relation === "UPSET" && r.margin === 2).winnerPoints).toBe(13);
  });

  it("saves an edited tournament placement value", () => {
    renderSection();
    fireEvent.change(screen.getByLabelText("sanctioned 1st points"), { target: { value: "100" } });
    // The tournament card's Save is the second Save button.
    fireEvent.click(screen.getAllByRole("button", { name: "Save" })[1]);

    expect(putTournament).toHaveBeenCalledTimes(1);
    expect(putTournament.mock.calls[0][0].data.sanctioned[0]).toBe(100);
  });

  it("shows a loading state until config arrives", () => {
    useGetOpenPlay.mockReturnValue({ data: undefined, isLoading: true });
    useGetTournament.mockReturnValue({ data: undefined, isLoading: true });
    renderSection();
    expect(screen.getAllByText("Loading…").length).toBeGreaterThan(0);
  });
});
