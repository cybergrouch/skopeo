import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { PointsManagementSection } from "./PointsManagementSection";
import { Capability } from "@/auth/capabilities";

const {
  useGetApiV1PointsPolicies,
  useGetApiV1PointsBudgets,
  useGetApiV1Clubs,
  policyMutate,
  budgetMutate,
} = vi.hoisted(() => ({
  useGetApiV1PointsPolicies: vi.fn(),
  useGetApiV1PointsBudgets: vi.fn(),
  useGetApiV1Clubs: vi.fn(),
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

// The embedded standings-calculation trigger (#447) is unit-tested in its own file; here we just
// stub its hook so PointsManagementSection renders.
vi.mock("@/api/generated/standings/standings", () => ({
  usePostApiV1StandingsCalculations: () => ({ isPending: false, mutate: vi.fn() }),
  getGetApiV1StandingsQueryKey: () => ["standings"],
}));

function renderSection() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <PointsManagementSection capabilities={[Capability.ADMINISTRATOR]} />
    </QueryClientProvider>,
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
  });

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
});
