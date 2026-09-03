import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { FeatureFlagsSection } from "./FeatureFlagsSection";

const {
  useMe,
  usePutRaw,
  rawMutate,
  useFbFlag,
  usePutFb,
  fbMutate,
  useAwardFlag,
  usePutAward,
  awardMutate,
  useHideFlag,
  usePutHide,
  hideMutate,
} =
  vi.hoisted(() => ({
    useMe: vi.fn(),
    usePutRaw: vi.fn(),
    rawMutate: vi.fn(),
    useFbFlag: vi.fn(),
    usePutFb: vi.fn(),
    fbMutate: vi.fn(),
    useAwardFlag: vi.fn(),
    usePutAward: vi.fn(),
    awardMutate: vi.fn(),
    useHideFlag: vi.fn(),
    usePutHide: vi.fn(),
    hideMutate: vi.fn(),
  }));

const { toastSuccess, toastError } = vi.hoisted(() => ({
  toastSuccess: vi.fn(),
  toastError: vi.fn(),
}));
vi.mock("sonner", () => ({ toast: { success: toastSuccess, error: toastError } }));

vi.mock("@/api/generated/users/users", () => ({
  useGetApiV1UsersMe: useMe,
  getGetApiV1UsersMeQueryKey: () => ["me"],
}));
vi.mock("@/api/generated/settings/settings", () => ({
  usePutApiV1UsersMeRatingPreview: usePutRaw,
  useGetApiV1SettingsFacebookLogin: useFbFlag,
  usePutApiV1SettingsFacebookLogin: usePutFb,
  getGetApiV1SettingsFacebookLoginQueryKey: () => ["fb"],
  useGetApiV1SettingsAwardRankingPoints: useAwardFlag,
  usePutApiV1SettingsAwardRankingPoints: usePutAward,
  getGetApiV1SettingsAwardRankingPointsQueryKey: () => ["award"],
  useGetApiV1SettingsHideRankingPoints: useHideFlag,
  usePutApiV1SettingsHideRankingPoints: usePutHide,
  getGetApiV1SettingsHideRankingPointsQueryKey: () => ["hide"],
}));

type MutationOpts = { mutation: { onSuccess: () => void; onError?: (e: unknown) => void } };

function renderSection() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <FeatureFlagsSection />
    </QueryClientProvider>,
  );
}

describe("FeatureFlagsSection", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useMe.mockReturnValue({ data: { previewRatingsAsNonAdmin: false }, isLoading: false });
    useFbFlag.mockReturnValue({ data: { enabled: true }, isLoading: false });
    useAwardFlag.mockReturnValue({ data: { enabled: false }, isLoading: false });
    usePutAward.mockImplementation((options: MutationOpts) => ({
      isPending: false,
      mutate: (vars: unknown) => {
        awardMutate(vars);
        options.mutation.onSuccess();
      },
    }));
    useHideFlag.mockReturnValue({ data: { hidden: false }, isLoading: false });
    usePutHide.mockImplementation((options: MutationOpts) => ({
      isPending: false,
      mutate: (vars: unknown) => {
        hideMutate(vars);
        options.mutation.onSuccess();
      },
    }));
    usePutRaw.mockImplementation((options: MutationOpts) => ({
      isPending: false,
      mutate: (vars: unknown) => {
        rawMutate(vars);
        options.mutation.onSuccess();
      },
    }));
    usePutFb.mockImplementation((options: MutationOpts) => ({
      isPending: false,
      mutate: (vars: unknown) => {
        fbMutate(vars);
        options.mutation.onSuccess();
      },
    }));
  });

  it("shows the Facebook toggle checked when the flag is enabled", () => {
    renderSection();
    expect(screen.getByLabelText("Enable Facebook login")).toBeChecked();
  });

  it("shows the Facebook toggle unchecked when the flag is disabled", () => {
    useFbFlag.mockReturnValue({ data: { enabled: false }, isLoading: false });
    renderSection();
    expect(screen.getByLabelText("Enable Facebook login")).not.toBeChecked();
  });

  it("unchecking Facebook login sends enabled=false and shows Saved", async () => {
    const user = userEvent.setup();
    renderSection();
    await user.click(screen.getByLabelText("Enable Facebook login"));
    await waitFor(() => expect(fbMutate).toHaveBeenCalledWith({ data: { enabled: false } }));
    await waitFor(() => expect(toastSuccess).toHaveBeenCalledWith("Saved"));
  });

  it("surfaces an error when saving the Facebook flag fails", async () => {
    usePutFb.mockImplementation((options: MutationOpts) => ({
      isPending: false,
      mutate: () => options.mutation.onError?.(new Error("boom")),
    }));
    const user = userEvent.setup();
    renderSection();
    await user.click(screen.getByLabelText("Enable Facebook login"));
    await waitFor(() =>
      expect(toastError).toHaveBeenCalledWith("Could not update the setting. Try again.", {
        duration: 8000,
      }),
    );
  });

  it("shows the hide-raw-ratings toggle unchecked by default (#743)", () => {
    // Seeing raw values is the default for an admin, so the opt-out starts off.
    renderSection();
    expect(screen.getByLabelText("Hide raw NTRP ratings")).not.toBeChecked();
  });

  it("reflects the stored flag without inverting it (#743)", () => {
    useMe.mockReturnValue({ data: { previewRatingsAsNonAdmin: true }, isLoading: false });
    renderSection();
    expect(screen.getByLabelText("Hide raw NTRP ratings")).toBeChecked();
  });

  it("checking hide-raw-ratings sends previewAsNonAdmin=true and shows Saved (#743)", async () => {
    const user = userEvent.setup();
    renderSection();
    await user.click(screen.getByLabelText("Hide raw NTRP ratings"));
    await waitFor(() => expect(rawMutate).toHaveBeenCalledWith({ data: { previewAsNonAdmin: true } }));
    await waitFor(() => expect(toastSuccess).toHaveBeenCalledWith("Saved"));
  });

  it("unchecking it sends previewAsNonAdmin=false, restoring raw values (#743)", async () => {
    useMe.mockReturnValue({ data: { previewRatingsAsNonAdmin: true }, isLoading: false });
    const user = userEvent.setup();
    renderSection();
    await user.click(screen.getByLabelText("Hide raw NTRP ratings"));
    await waitFor(() =>
      expect(rawMutate).toHaveBeenCalledWith({ data: { previewAsNonAdmin: false } }),
    );
  });

  it("surfaces an error when saving the raw-ratings preference fails", async () => {
    usePutRaw.mockImplementation((options: MutationOpts) => ({
      isPending: false,
      mutate: () => options.mutation.onError?.(new Error("boom")),
    }));
    const user = userEvent.setup();
    renderSection();
    await user.click(screen.getByLabelText("Hide raw NTRP ratings"));
    await waitFor(() =>
      expect(toastError).toHaveBeenCalledWith("Could not update the setting. Try again.", {
        duration: 8000,
      }),
    );
  });

  it("shows the award-ranking-points toggle unchecked when the flag is off (#641)", () => {
    renderSection(); // beforeEach default: award flag disabled
    expect(screen.getByLabelText("Enable award ranking points")).not.toBeChecked();
  });

  it("enabling award ranking points sends enabled=true and shows Saved (#641)", async () => {
    const user = userEvent.setup();
    renderSection();
    await user.click(screen.getByLabelText("Enable award ranking points"));
    await waitFor(() => expect(awardMutate).toHaveBeenCalledWith({ data: { enabled: true } }));
    expect(toastSuccess).toHaveBeenCalledWith("Saved");
  });

  it("surfaces an error when saving the award-ranking-points flag fails (#641)", async () => {
    usePutAward.mockImplementation((options: MutationOpts) => ({
      isPending: false,
      mutate: () => options.mutation.onError?.(new Error("boom")),
    }));
    const user = userEvent.setup();
    renderSection();
    await user.click(screen.getByLabelText("Enable award ranking points"));
    await waitFor(() =>
      expect(toastError).toHaveBeenCalledWith("Could not update the setting. Try again.", {
        duration: 8000,
      }),
    );
  });

  it("surfaces an error when saving the hide-ranking-points flag fails (#865)", async () => {
    usePutHide.mockImplementation((options: MutationOpts) => ({
      isPending: false,
      mutate: () => options.mutation.onError?.(new Error("boom")),
    }));
    const user = userEvent.setup();
    renderSection();
    await user.click(screen.getByLabelText("Hide ranking points from players"));
    await waitFor(() =>
      expect(toastError).toHaveBeenCalledWith("Could not update the setting. Try again.", {
        duration: 8000,
      }),
    );
  });

  it("shows the hide-ranking-points flag unticked by default (#865)", () => {
    renderSection();
    // Opt-in suppression: unticked is the default and preserves the original behaviour, so shipping the
    // flag changes nothing until an admin ticks it.
    const box = screen.getByLabelText("Hide ranking points from players");
    expect(box).not.toBeChecked();
  });

  it("ticking it sends hidden true (#865)", async () => {
    const user = userEvent.setup();
    renderSection();

    await user.click(screen.getByLabelText("Hide ranking points from players"));

    expect(hideMutate).toHaveBeenCalledWith({ data: { hidden: true } });
    expect(toastSuccess).toHaveBeenCalledWith("Saved");
  });

  it("unticking it sends hidden false (#865)", async () => {
    useHideFlag.mockReturnValue({ data: { hidden: true }, isLoading: false });
    const user = userEvent.setup();
    renderSection();

    expect(screen.getByLabelText("Hide ranking points from players")).toBeChecked();
    await user.click(screen.getByLabelText("Hide ranking points from players"));

    expect(hideMutate).toHaveBeenCalledWith({ data: { hidden: false } });
  });

  it("says who is unaffected, and that a player's own points are hidden too (#865)", () => {
    renderSection();
    // The two surprising parts of the rule: rank and band survive, and there is no owner-self exemption.
    // An admin ticking this without knowing the second would be misled about what they are doing.
    const description = screen.getByText(/point figures are hidden/);
    expect(description).toHaveTextContent("including a player's own points");
    expect(description).toHaveTextContent("Rank and band stay visible");
    expect(description).toHaveTextContent(
      "Administrators, hosts, club owners, raters and points managers are unaffected",
    );
  });

  it("keeps the two points flags distinguishable (#865)", () => {
    renderSection();
    // award-ranking-points suppresses AWARDING; hide-ranking-points suppresses DISPLAY. Two checkboxes
    // whose labels differ by one word would be a trap, so assert they read differently.
    expect(screen.getByLabelText("Enable award ranking points")).toBeInTheDocument();
    expect(screen.getByLabelText("Hide ranking points from players")).toBeInTheDocument();
  });

});
