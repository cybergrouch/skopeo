import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { FeatureFlagsSection } from "./FeatureFlagsSection";

const { useMe, usePutRaw, rawMutate, useFbFlag, usePutFb, fbMutate } = vi.hoisted(() => ({
  useMe: vi.fn(),
  usePutRaw: vi.fn(),
  rawMutate: vi.fn(),
  useFbFlag: vi.fn(),
  usePutFb: vi.fn(),
  fbMutate: vi.fn(),
}));

vi.mock("@/api/generated/users/users", () => ({
  useGetApiV1UsersMe: useMe,
  getGetApiV1UsersMeQueryKey: () => ["me"],
}));
vi.mock("@/api/generated/settings/settings", () => ({
  usePutApiV1UsersMeRatingPreview: usePutRaw,
  useGetApiV1SettingsFacebookLogin: useFbFlag,
  usePutApiV1SettingsFacebookLogin: usePutFb,
  getGetApiV1SettingsFacebookLoginQueryKey: () => ["fb"],
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
    expect(screen.getByRole("status")).toHaveTextContent("Saved");
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
      expect(screen.getByRole("alert")).toHaveTextContent("Could not update the setting"),
    );
  });

  it("shows the raw-ratings toggle checked when not previewing as non-admin", () => {
    renderSection();
    expect(screen.getByLabelText("Show raw NTRP ratings")).toBeChecked();
  });

  it("unchecking raw ratings sends previewAsNonAdmin=true and shows Saved", async () => {
    const user = userEvent.setup();
    renderSection();
    await user.click(screen.getByLabelText("Show raw NTRP ratings"));
    await waitFor(() => expect(rawMutate).toHaveBeenCalledWith({ data: { previewAsNonAdmin: true } }));
    expect(screen.getByRole("status")).toHaveTextContent("Saved");
  });

  it("surfaces an error when saving the raw-ratings preference fails", async () => {
    usePutRaw.mockImplementation((options: MutationOpts) => ({
      isPending: false,
      mutate: () => options.mutation.onError?.(new Error("boom")),
    }));
    const user = userEvent.setup();
    renderSection();
    await user.click(screen.getByLabelText("Show raw NTRP ratings"));
    await waitFor(() =>
      expect(screen.getByRole("alert")).toHaveTextContent("Could not update the setting"),
    );
  });
});
