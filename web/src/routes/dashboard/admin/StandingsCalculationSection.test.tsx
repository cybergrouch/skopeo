import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { StandingsCalculationSection } from "./StandingsCalculationSection";
import { Capability } from "@/auth/capabilities";
import type { StandingsCalculationResponse } from "@/api/generated/model";

const { usePostApiV1StandingsCalculations, calcMutate } = vi.hoisted(() => ({
  usePostApiV1StandingsCalculations: vi.fn(),
  calcMutate: vi.fn(),
}));

type MutationOpts = {
  mutation: {
    onSuccess: (data: StandingsCalculationResponse) => void;
    onError?: (e: unknown) => void;
  };
};

vi.mock("@/api/generated/standings/standings", () => ({
  usePostApiV1StandingsCalculations,
  getGetApiV1StandingsQueryKey: () => ["standings"],
}));

const dryRunResponse: StandingsCalculationResponse = {
  dryRun: true,
  groupsComputed: 2,
  groups: [
    {
      band: "4.0",
      sex: "Male",
      entries: [
        {
          rank: 1,
          userId: "u1",
          publicCode: "AAA",
          points: "12",
          displayName: "Alice",
          currentRating: "4.1",
        },
      ],
    },
    {
      band: "3.5",
      sex: null,
      entries: [],
    },
  ],
};

const committedResponse: StandingsCalculationResponse = {
  ...dryRunResponse,
  dryRun: false,
};

/**
 * Wire the mocked hook: on mutate, invoke onError when [fail], else onSuccess with a response whose
 * dryRun echoes the request (dry-run → preview payload, commit → committed payload) — so the same
 * mock drives both Preview and Commit clicks without re-mocking mid-render.
 */
function mockCalc(fail = false) {
  usePostApiV1StandingsCalculations.mockImplementation(
    (options: MutationOpts) => ({
      isPending: false,
      mutate: (vars: { data?: { dryRun?: boolean } }) => {
        calcMutate(vars);
        if (fail) {
          options.mutation.onError?.(new Error("boom"));
          return;
        }
        options.mutation.onSuccess(
          vars.data?.dryRun === false ? committedResponse : dryRunResponse,
        );
      },
    }),
  );
}

function renderSection(capabilities: Capability[]) {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <StandingsCalculationSection capabilities={capabilities} />
    </QueryClientProvider>,
  );
}

describe("StandingsCalculationSection", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("previews with dryRun:true and renders the projected groups", async () => {
    mockCalc();
    const user = userEvent.setup();
    renderSection([Capability.ADMINISTRATOR]);

    await user.click(screen.getByRole("button", { name: "Preview" }));

    expect(calcMutate).toHaveBeenCalledWith({ data: { dryRun: true } });
    expect(screen.getByTestId("standings-preview")).toHaveTextContent(
      "2 groups, no changes saved yet",
    );
    // One group with a sex label + player count, one empty group.
    expect(screen.getByText(/4\.0/)).toBeInTheDocument();
    expect(screen.getByText(/Male/)).toBeInTheDocument();
  });

  it("commits with dryRun:false and shows the published-snapshot success", async () => {
    // The mock echoes the request's dryRun: Preview yields the dry-run payload (revealing Commit),
    // Commit yields the committed payload.
    mockCalc();
    const user = userEvent.setup();
    renderSection([Capability.ADMINISTRATOR]);

    await user.click(screen.getByRole("button", { name: "Preview" }));
    await user.click(screen.getByRole("button", { name: "Commit" }));

    expect(calcMutate).toHaveBeenLastCalledWith({ data: { dryRun: false } });
    expect(screen.getByRole("status")).toHaveTextContent(
      "Published a Points snapshot with 2 groups",
    );
  });

  it("shows inline error feedback when the calculation fails", async () => {
    mockCalc(true);
    const user = userEvent.setup();
    renderSection([Capability.ADMINISTRATOR]);

    await user.click(screen.getByRole("button", { name: "Preview" }));

    expect(screen.getByRole("alert")).toHaveTextContent(
      "Could not run the standings calculation",
    );
  });

  it("hides the trigger and shows a hint for a non-admin points manager", () => {
    mockCalc();
    renderSection([Capability.POINTS_MANAGER]);

    expect(
      screen.queryByRole("button", { name: "Preview" }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("note")).toHaveTextContent(
      "requires the Administrator capability",
    );
  });
});
