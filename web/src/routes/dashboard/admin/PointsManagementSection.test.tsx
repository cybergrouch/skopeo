import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { PointsManagementSection } from "./PointsManagementSection";
import { Capability } from "@/auth/capabilities";

const {
  useGetApiV1PointsPolicies,
  useGetApiV1PointsBudgets,
  useGetApiV1Clubs,
  useGetApiV1RankingPoints,
  policyMutate,
  budgetMutate,
} = vi.hoisted(() => ({
  useGetApiV1PointsPolicies: vi.fn(),
  useGetApiV1PointsBudgets: vi.fn(),
  useGetApiV1Clubs: vi.fn(),
  useGetApiV1RankingPoints: vi.fn(),
  policyMutate: vi.fn(),
  budgetMutate: vi.fn(),
}));

type MutationOpts = {
  mutation: { onSuccess: () => void; onError?: (e: unknown) => void };
};

vi.mock("@/api/generated/points-budget/points-budget", () => ({
  useGetApiV1PointsPolicies,
  useGetApiV1PointsBudgets,
  getGetApiV1PointsPoliciesQueryKey: () => ["points-policies"],
  getGetApiV1PointsBudgetsQueryKey: () => ["points-budgets"],
  usePutApiV1PointsPoliciesEventType: (options: MutationOpts) => ({
    isPending: false,
    mutate: (vars: unknown) => {
      policyMutate(vars);
      options.mutation.onSuccess();
    },
  }),
  usePutApiV1ClubsClubIdPointBudgetsEventType: (options: MutationOpts) => ({
    isPending: false,
    mutate: (vars: unknown) => {
      budgetMutate(vars);
      options.mutation.onSuccess();
    },
  }),
}));

vi.mock("@/api/generated/clubs/clubs", () => ({
  useGetApiV1Clubs,
}));

vi.mock("@/api/generated/ranking-points/ranking-points", () => ({
  useGetApiV1RankingPoints,
}));

// The embedded standings-calculation trigger (#447) is unit-tested in its own file; here we just
// stub its hook so PointsManagementSection renders.
vi.mock("@/api/generated/standings/standings", () => ({
  usePostApiV1StandingsCalculations: () => ({ isPending: false, mutate: vi.fn() }),
  getGetApiV1StandingsQueryKey: () => ["standings"],
}));

function renderSection() {
  return render(
    <MemoryRouter>
      <QueryClientProvider client={new QueryClient()}>
        <PointsManagementSection capabilities={[Capability.ADMINISTRATOR]} />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe("PointsManagementSection", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useGetApiV1PointsPolicies.mockReturnValue({
      data: [
        {
          eventType: "OPEN_PLAY",
          minPoints: 1,
          maxPoints: 10,
          maxValidityDays: 30,
        },
      ],
      isLoading: false,
    });
    useGetApiV1PointsBudgets.mockReturnValue({
      data: [
        {
          clubId: "club-1",
          eventType: "LEAGUE",
          budgeted: 200,
          allocated: 0,
          free: 200,
        },
      ],
      isLoading: false,
    });
    useGetApiV1Clubs.mockReturnValue({
      data: [{ id: "club-1", name: "Downtown TC" }],
    });
    useGetApiV1RankingPoints.mockReturnValue({
      data: { rows: [], total: 0, limit: 25, offset: 0 },
      isLoading: false,
    });
  });

  function awardRow(overrides: Record<string, unknown> = {}) {
    return {
      id: "award-1",
      userId: "user-1",
      playerDisplayName: "Ada Lovelace",
      playerPublicCode: "ADA123",
      points: "240.0000",
      pointClass: "ANNUAL_TOURNAMENT",
      band: "4.0",
      sex: "Female",
      sourceType: "INTERNAL",
      source: "manual",
      matchPublicCode: null,
      eventPublicCode: null,
      reason: null,
      grantedBy: "admin-1",
      awardedAt: "2026-06-01T10:00:00",
      validFrom: "2026-06-01T10:00:00",
      validUntil: "2027-06-01T10:00:00",
      status: "ACTIVE",
      ...overrides,
    };
  }

  it("renders the policy row with editable fields", () => {
    renderSection();
    expect(screen.getByText("OPEN_PLAY")).toBeInTheDocument();
    expect(
      (screen.getByLabelText("Min") as HTMLInputElement).value,
    ).toBe("1");
    expect(
      (screen.getByLabelText("Max") as HTMLInputElement).value,
    ).toBe("10");
    expect(
      (screen.getByLabelText("Validity (days)") as HTMLInputElement).value,
    ).toBe("30");
  });

  it("renders the budget row with the club name, Allocated 0 and Free", () => {
    renderSection();
    expect(screen.getByText("Downtown TC")).toBeInTheDocument();
    expect(screen.getByText("LEAGUE")).toBeInTheDocument();
    expect(
      (screen.getByLabelText("Budget for Downtown TC LEAGUE") as HTMLInputElement)
        .value,
    ).toBe("200");
    // Allocated is 0 for now.
    const cells = screen.getAllByRole("cell");
    expect(cells.some((c) => c.textContent === "0")).toBe(true);
    expect(cells.some((c) => c.textContent === "200")).toBe(true);
  });

  it("saves a policy edit with the entered values", async () => {
    const user = userEvent.setup();
    renderSection();
    const max = screen.getByLabelText("Max");
    await user.clear(max);
    await user.type(max, "42");
    await user.click(screen.getAllByRole("button", { name: "Save" })[0]);

    expect(policyMutate).toHaveBeenCalledWith({
      eventType: "OPEN_PLAY",
      data: { minPoints: 1, maxPoints: 42, maxValidityDays: 30 },
    });
    expect(screen.getAllByRole("status")[0]).toHaveTextContent("Saved");
  });

  it("saves a club budget with the entered value", async () => {
    const user = userEvent.setup();
    renderSection();
    const budget = screen.getByLabelText("Budget for Downtown TC LEAGUE");
    await user.clear(budget);
    await user.type(budget, "300");
    // The budget row's Save button is the last one.
    const saveButtons = screen.getAllByRole("button", { name: "Save" });
    await user.click(saveButtons[saveButtons.length - 1]);

    expect(budgetMutate).toHaveBeenCalledWith({
      clubId: "club-1",
      eventType: "LEAGUE",
      data: { budgetedPoints: 300 },
    });
  });

  it("shows a loading state while policies load", () => {
    useGetApiV1PointsPolicies.mockReturnValue({ data: undefined, isLoading: true });
    renderSection();
    expect(screen.getAllByText("Loading…").length).toBeGreaterThan(0);
  });

  it("renders an awarded-points row with a player link, signed points and source", () => {
    useGetApiV1RankingPoints.mockReturnValue({
      data: { rows: [awardRow()], total: 1, limit: 25, offset: 0 },
      isLoading: false,
    });
    renderSection();

    expect(screen.getByText("Points awarded")).toBeInTheDocument();
    const link = screen.getByRole("link", { name: "Ada Lovelace" });
    expect(link).toHaveAttribute("href", "/players/ADA123");
    // Points render as a signed integer via formatPoints.
    expect(screen.getByText("+240")).toBeInTheDocument();
    // A manual grant shows "manual" as its source.
    expect(screen.getByText("manual")).toBeInTheDocument();
    expect(screen.getByText("ACTIVE")).toBeInTheDocument();
  });

  it("paginates the awarded-points list, requesting the next offset", async () => {
    const user = userEvent.setup();
    // 30 total across 2 pages of 25.
    useGetApiV1RankingPoints.mockReturnValue({
      data: { rows: [awardRow()], total: 30, limit: 25, offset: 0 },
      isLoading: false,
    });
    renderSection();

    await user.click(screen.getByRole("button", { name: "Next" }));
    // The hook is re-called with the second page's offset (25).
    expect(useGetApiV1RankingPoints).toHaveBeenLastCalledWith(
      { limit: 25, offset: 25 },
      expect.anything(),
    );
  });

  it("hides the awarded-points card when the caller cannot manage points", () => {
    render(
      <MemoryRouter>
        <QueryClientProvider client={new QueryClient()}>
          <PointsManagementSection capabilities={[Capability.PLAYER]} />
        </QueryClientProvider>
      </MemoryRouter>,
    );
    expect(screen.queryByText("Points awarded")).not.toBeInTheDocument();
  });
});
