import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { RawRatingsSection } from "./RawRatingsSection";

const { useMe, usePut, putMutate } = vi.hoisted(() => ({
  useMe: vi.fn(),
  usePut: vi.fn(),
  putMutate: vi.fn(),
}));

vi.mock("@/api/generated/users/users", () => ({
  useGetApiV1UsersMe: useMe,
  getGetApiV1UsersMeQueryKey: () => ["me"],
}));
vi.mock("@/api/generated/settings/settings", () => ({
  usePutApiV1UsersMeRatingPreview: usePut,
}));

type MutationOpts = { mutation: { onSuccess: () => void; onError?: (e: unknown) => void } };

function renderSection() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <RawRatingsSection />
    </QueryClientProvider>,
  );
}

describe("RawRatingsSection", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useMe.mockReturnValue({ data: { previewRatingsAsNonAdmin: false }, isLoading: false });
    usePut.mockImplementation((options: MutationOpts) => ({
      isPending: false,
      mutate: (vars: unknown) => {
        putMutate(vars);
        options.mutation.onSuccess();
      },
    }));
  });

  it("shows the toggle checked (raw shown) when not previewing as non-admin", () => {
    renderSection();
    expect(screen.getByLabelText("Show raw NTRP ratings")).toBeChecked();
  });

  it("shows the toggle unchecked when the admin is previewing as non-admin", () => {
    useMe.mockReturnValue({ data: { previewRatingsAsNonAdmin: true }, isLoading: false });
    renderSection();
    expect(screen.getByLabelText("Show raw NTRP ratings")).not.toBeChecked();
  });

  it("unchecking sends previewAsNonAdmin=true (preview the non-admin view)", async () => {
    const user = userEvent.setup();
    renderSection();
    await user.click(screen.getByLabelText("Show raw NTRP ratings"));
    await waitFor(() => expect(putMutate).toHaveBeenCalledWith({ data: { previewAsNonAdmin: true } }));
    expect(screen.getByRole("status")).toHaveTextContent("Saved");
  });

  it("surfaces an error when the save fails", async () => {
    // Drive the mutation's onError path.
    usePut.mockImplementation((options: MutationOpts) => ({
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
