import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { MatchPublicResponse } from "@/api/generated/model";
import { MatchScoreCorrectionCard } from "./MatchScoreCorrectionCard";

const { usePostApiV1MatchesIdScoreCorrection, correctMutate } = vi.hoisted(
  () => ({
    usePostApiV1MatchesIdScoreCorrection: vi.fn(),
    correctMutate: vi.fn(),
  }),
);
vi.mock("@/api/generated/matches/matches", () => ({
  usePostApiV1MatchesIdScoreCorrection,
  getGetApiV1MatchesCodeCodeQueryKey: (code: string) => ["matches", code],
}));

const { toastSuccess, toastError } = vi.hoisted(() => ({
  toastSuccess: vi.fn(),
  toastError: vi.fn(),
}));
vi.mock("sonner", () => ({
  toast: { success: toastSuccess, error: toastError },
}));

const match = {
  id: "m-1",
  publicCode: "MTCH01",
  matchFormat: "SINGLES",
  matchType: "OPEN_PLAY",
  matchDate: "2026-02-03",
  status: "COMPLETED",
  rated: true,
  team1: [{ displayName: "Ana", publicCode: "AAA111" }],
  team2: [{ displayName: "Bob", publicCode: "BBB222" }],
  winner: "TEAM1",
  sets: [{ setNumber: 1, team1Games: 6, team2Games: 4 }],
} as unknown as MatchPublicResponse;

const preview = {
  dryRun: true,
  matchPublicCode: "MTCH01",
  previousScore: "6-4",
  newScore: "6-0",
  winnerChanged: false,
  impacts: [
    {
      userId: "u-1",
      displayName: "Ana",
      currentRating: "4.100000",
      reversedChange: "0.100000",
      newChange: "0.160000",
      netAdjustment: "0.060000",
      resultingRating: "4.160000",
      previousLevel: "4.0",
      resultingLevel: "4.0",
      levelChanged: false,
    },
  ],
};

function renderCard(data: MatchPublicResponse = match) {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <MatchScoreCorrectionCard match={data} />
    </QueryClientProvider>,
  );
}

describe("MatchScoreCorrectionCard (#776)", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    usePostApiV1MatchesIdScoreCorrection.mockReturnValue({
      isPending: false,
      mutateAsync: async (vars: unknown) => correctMutate(vars),
    });
    correctMutate.mockResolvedValue(preview);
  });

  it("prefills the current score and offers a preview before any apply action", () => {
    renderCard();

    expect(screen.getByLabelText("Set 1 side 1 games")).toHaveValue("6");
    expect(screen.getByLabelText("Set 1 side 2 games")).toHaveValue("4");
    expect(
      screen.getByRole("button", { name: /preview correction/i }),
    ).toBeInTheDocument();
    // Nothing is appliable until a preview has been taken — this is the whole point of the two steps.
    expect(
      screen.queryByRole("button", { name: /apply correction/i }),
    ).not.toBeInTheDocument();
  });

  it("previews with dryRun true, writing nothing, and shows the per-player impact", async () => {
    const user = userEvent.setup();
    renderCard();

    await user.clear(screen.getByLabelText("Set 1 side 2 games"));
    await user.type(screen.getByLabelText("Set 1 side 2 games"), "0");
    await user.click(screen.getByRole("button", { name: /preview correction/i }));

    await waitFor(() =>
      expect(correctMutate).toHaveBeenCalledWith({
        id: "m-1",
        data: { sets: [{ team1Games: 6, team2Games: 0 }], dryRun: true },
      }),
    );
    expect(screen.getByText(/6-4 → 6-0/)).toBeInTheDocument();
    expect(screen.getByText(/\+0\.060000/)).toBeInTheDocument();
    // A preview is not a write, so no success toast fires yet.
    expect(toastSuccess).not.toHaveBeenCalled();
  });

  it("applies only on the explicit confirm, sending dryRun false", async () => {
    const user = userEvent.setup();
    renderCard();

    await user.click(screen.getByRole("button", { name: /preview correction/i }));
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: /apply correction/i }),
      ).toBeInTheDocument(),
    );

    correctMutate.mockResolvedValue({ ...preview, dryRun: false });
    await user.click(screen.getByRole("button", { name: /apply correction/i }));

    await waitFor(() =>
      expect(correctMutate).toHaveBeenLastCalledWith({
        id: "m-1",
        data: { sets: [{ team1Games: 6, team2Games: 4 }], dryRun: false },
      }),
    );
    expect(toastSuccess).toHaveBeenCalled();
  });

  it("warns in the preview when the correction flips who won", async () => {
    const user = userEvent.setup();
    correctMutate.mockResolvedValue({ ...preview, winnerChanged: true });
    renderCard();

    await user.click(screen.getByRole("button", { name: /preview correction/i }));

    await waitFor(() =>
      expect(
        screen.getByText(/changes who won the match/i),
      ).toBeInTheDocument(),
    );
  });

  it("discards a stale preview as soon as the score is edited again", async () => {
    const user = userEvent.setup();
    renderCard();

    await user.click(screen.getByRole("button", { name: /preview correction/i }));
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: /apply correction/i }),
      ).toBeInTheDocument(),
    );

    // Editing after previewing must retract the Apply button, or the admin could apply numbers they
    // never previewed.
    await user.type(screen.getByLabelText("Set 1 side 2 games"), "1");
    expect(
      screen.queryByRole("button", { name: /apply correction/i }),
    ).not.toBeInTheDocument();
  });

  it("refuses a blank game count without calling the API — blank is not zero", async () => {
    const user = userEvent.setup();
    renderCard();

    await user.clear(screen.getByLabelText("Set 1 side 2 games"));
    await user.click(screen.getByRole("button", { name: /preview correction/i }));

    expect(correctMutate).not.toHaveBeenCalled();
    expect(toastError).toHaveBeenCalled();
  });

  it("refuses a non-numeric game count without calling the API", async () => {
    const user = userEvent.setup();
    renderCard();

    await user.clear(screen.getByLabelText("Set 1 side 2 games"));
    await user.type(screen.getByLabelText("Set 1 side 2 games"), "abc");
    await user.click(screen.getByRole("button", { name: /preview correction/i }));

    expect(correctMutate).not.toHaveBeenCalled();
    expect(toastError).toHaveBeenCalled();
  });

  it("surfaces a failed apply as an error and keeps the preview", async () => {
    const user = userEvent.setup();
    renderCard();

    await user.click(screen.getByRole("button", { name: /preview correction/i }));
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: /apply correction/i }),
      ).toBeInTheDocument(),
    );

    correctMutate.mockRejectedValue(new Error("boom"));
    await user.click(screen.getByRole("button", { name: /apply correction/i }));

    await waitFor(() => expect(toastError).toHaveBeenCalled());
    expect(toastSuccess).not.toHaveBeenCalled();
  });

  it("surfaces a failed preview with its own message and offers no apply", async () => {
    const user = userEvent.setup();
    correctMutate.mockRejectedValue(new Error("boom"));
    renderCard();

    await user.click(screen.getByRole("button", { name: /preview correction/i }));

    await waitFor(() =>
      expect(toastError).toHaveBeenCalledWith(
        expect.stringMatching(/could not preview/i),
        expect.anything(),
      ),
    );
    expect(
      screen.queryByRole("button", { name: /apply correction/i }),
    ).not.toBeInTheDocument();
  });

  it("edits the first side's games too, not just the second", async () => {
    const user = userEvent.setup();
    renderCard();

    await user.clear(screen.getByLabelText("Set 1 side 1 games"));
    await user.type(screen.getByLabelText("Set 1 side 1 games"), "7");
    await user.click(screen.getByRole("button", { name: /preview correction/i }));

    await waitFor(() =>
      expect(correctMutate).toHaveBeenCalledWith({
        id: "m-1",
        data: { sets: [{ team1Games: 7, team2Games: 4 }], dryRun: true },
      }),
    );
  });

  it("shows the band move when the correction changes a player's NTRP band", async () => {
    const user = userEvent.setup();
    correctMutate.mockResolvedValue({
      ...preview,
      impacts: [
        {
          ...preview.impacts[0],
          // No display name: the preview falls back to the id rather than rendering a blank row.
          displayName: undefined,
          levelChanged: true,
          previousLevel: "4.0",
          resultingLevel: "4.5",
        },
      ],
    });
    renderCard();

    await user.click(screen.getByRole("button", { name: /preview correction/i }));

    await waitFor(() => expect(screen.getByText("u-1")).toBeInTheDocument());
    expect(screen.getByText(/Band:/)).toBeInTheDocument();
    expect(screen.getByText(/4\.0/)).toBeInTheDocument();
    expect(screen.getByText(/4\.5/)).toBeInTheDocument();
  });

  it("falls back to an em dash for a band change whose labels are missing", async () => {
    const user = userEvent.setup();
    // An older history row can carry no band label, so a level change can be reported without both
    // sides being named. The row must still read sensibly rather than rendering blanks.
    correctMutate.mockResolvedValue({
      ...preview,
      impacts: [
        {
          ...preview.impacts[0],
          levelChanged: true,
          previousLevel: undefined,
          resultingLevel: undefined,
        },
      ],
    });
    renderCard();

    await user.click(screen.getByRole("button", { name: /preview correction/i }));

    await waitFor(() =>
      expect(screen.getByText(/Band:/)).toBeInTheDocument(),
    );
    expect(screen.getByText(/— →/)).toBeInTheDocument();
  });

  it("shows a working indicator and disables the inputs while a request is in flight", () => {
    usePostApiV1MatchesIdScoreCorrection.mockReturnValue({
      isPending: true,
      mutateAsync: async (vars: unknown) => correctMutate(vars),
    });
    renderCard();

    expect(screen.getByText("Working…")).toBeInTheDocument();
    expect(screen.getByLabelText("Set 1 side 1 games")).toBeDisabled();
    expect(
      screen.getByRole("button", { name: /preview correction/i }),
    ).toBeDisabled();
  });

  it("keeps the minus sign on a negative net adjustment rather than prefixing a plus", async () => {
    const user = userEvent.setup();
    // One side of a correction almost always moves down, so this is the common case, not an edge one.
    correctMutate.mockResolvedValue({
      ...preview,
      impacts: [{ ...preview.impacts[0], netAdjustment: "-0.060000" }],
    });
    renderCard();

    await user.click(screen.getByRole("button", { name: /preview correction/i }));

    await waitFor(() =>
      expect(screen.getByText(/-0\.060000/)).toBeInTheDocument(),
    );
    expect(screen.queryByText(/\+-/)).not.toBeInTheDocument();
  });

  it("edits one set without disturbing the others", async () => {
    const user = userEvent.setup();
    renderCard({
      ...match,
      sets: [
        { setNumber: 1, team1Games: 6, team2Games: 4 },
        { setNumber: 2, team1Games: 3, team2Games: 6 },
      ],
    } as MatchPublicResponse);

    await user.clear(screen.getByLabelText("Set 1 side 2 games"));
    await user.type(screen.getByLabelText("Set 1 side 2 games"), "0");
    await user.click(screen.getByRole("button", { name: /preview correction/i }));

    await waitFor(() =>
      expect(correctMutate).toHaveBeenCalledWith({
        id: "m-1",
        data: {
          sets: [
            { team1Games: 6, team2Games: 0 },
            // Set 2 must survive the edit to set 1 untouched.
            { team1Games: 3, team2Games: 6 },
          ],
          dryRun: true,
        },
      }),
    );
  });

  it("refuses to submit a match that somehow has no sets", async () => {
    const user = userEvent.setup();
    renderCard({ ...match, sets: [] } as MatchPublicResponse);

    await user.click(screen.getByRole("button", { name: /preview correction/i }));

    expect(correctMutate).not.toHaveBeenCalled();
    expect(toastError).toHaveBeenCalled();
  });

  it("renders nothing when the match id was not revealed to this viewer", () => {
    renderCard({ ...match, id: undefined } as MatchPublicResponse);

    expect(screen.queryByText("Correct this score")).not.toBeInTheDocument();
  });
});
