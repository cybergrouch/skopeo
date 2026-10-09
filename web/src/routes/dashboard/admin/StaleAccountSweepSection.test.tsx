import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { setupUser } from "@/test/user";
import { StaleAccountSweepSection } from "./StaleAccountSweepSection";

type Options = { mutation: { onSuccess: (r: unknown) => void; onError: (e: unknown) => void } };
const { usePost, mutate, invalidateQueries, toastSuccess, toastErrorFn, state } = vi.hoisted(() => ({
  usePost: vi.fn(),
  mutate: vi.fn(),
  invalidateQueries: vi.fn(),
  toastSuccess: vi.fn(),
  toastErrorFn: vi.fn(),
  state: { options: undefined as Options | undefined, isPending: false },
}));
vi.mock("@/api/generated/users/users", () => ({
  usePostApiV1UsersStaleSweeps: usePost,
  getGetApiV1UsersSearchQueryKey: () => ["users-search"],
}));
vi.mock("@/api/generated/ratings/ratings", () => ({
  getGetApiV1UsersPendingAssessmentQueryKey: () => ["pending-assessment"],
}));
vi.mock("@tanstack/react-query", () => ({ useQueryClient: () => ({ invalidateQueries }) }));
vi.mock("sonner", () => ({ toast: { success: toastSuccess } }));
vi.mock("@/observability/toastError", () => ({ toastError: toastErrorFn }));

const account = (code: string, createdAt: string) => ({ userId: `id-${code}`, publicCode: code, createdAt });
const previewOf = (accounts: unknown[]) => ({ dryRun: true, thresholdDays: 30, cutoff: "2026-09-09T12:00", accounts });

function renderSection() {
  return render(
    <MemoryRouter>
      <StaleAccountSweepSection />
    </MemoryRouter>,
  );
}

describe("StaleAccountSweepSection", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.isPending = false;
    // A dry run answers with a preview; a commit with the swept accounts. onSuccess is fed the same way
    // the real mutation would, so the component's own branching is what is under test.
    usePost.mockImplementation((options: Options) => {
      state.options = options;
      return { mutate, isPending: state.isPending };
    });
  });

  it("offers only Preview until there is something previewed to remove", () => {
    renderSection();
    expect(screen.getByRole("button", { name: "Preview" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Remove/ })).not.toBeInTheDocument();
  });

  it("previews with a dry run and lists who would be removed, changing nothing", async () => {
    const user = setupUser();
    renderSection();
    await user.click(screen.getByRole("button", { name: "Preview" }));
    expect(mutate).toHaveBeenCalledWith({ data: { dryRun: true } });

    state.options?.mutation.onSuccess(previewOf([account("AAA111", "2026-08-11T09:00"), account("BBB222", "2026-08-22T09:00")]));

    expect(await screen.findByText(/2 accounts would be removed/)).toBeInTheDocument();
    expect(screen.getByText(/over 30 days ago/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "AAA111" })).toHaveAttribute("href", "/players/AAA111");
    expect(screen.getByText(/signed up 2026-08-22/)).toBeInTheDocument();
    expect(screen.getByText(/anyone rated in the meantime is kept/)).toBeInTheDocument();
    expect(toastSuccess).not.toHaveBeenCalled();
    expect(invalidateQueries).not.toHaveBeenCalled();
  });

  it("removes the previewed accounts on commit, then clears the preview and refreshes the lists", async () => {
    const user = setupUser();
    renderSection();
    await user.click(screen.getByRole("button", { name: "Preview" }));
    state.options?.mutation.onSuccess(previewOf([account("AAA111", "2026-08-11T09:00")]));

    await user.click(await screen.findByRole("button", { name: "Remove 1 account now" }));
    expect(mutate).toHaveBeenLastCalledWith({ data: { dryRun: false } });

    state.options?.mutation.onSuccess({ ...previewOf([account("AAA111", "2026-08-11T09:00")]), dryRun: false });

    expect(toastSuccess).toHaveBeenCalledWith("Removed 1 stale account. Deleted Accounts can restore any of them.");
    expect(invalidateQueries).toHaveBeenCalledWith({ queryKey: ["pending-assessment"] });
    expect(invalidateQueries).toHaveBeenCalledWith({ queryKey: ["users-search"] });
    expect(await screen.findByRole("button", { name: "Preview" })).toBeInTheDocument();
    expect(screen.queryByTestId("stale-sweep-preview")).not.toBeInTheDocument();
  });

  it("says so when nobody matches, offering nothing to remove", async () => {
    const user = setupUser();
    renderSection();
    await user.click(screen.getByRole("button", { name: "Preview" }));
    state.options?.mutation.onSuccess(previewOf([]));

    expect(await screen.findByText(/No account matches the rule/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Remove/ })).not.toBeInTheDocument();
  });

  it("reports a commit that found nothing left, a failure, and lets a preview be discarded", async () => {
    const user = setupUser();
    renderSection();
    await user.click(screen.getByRole("button", { name: "Preview" }));
    state.options?.mutation.onSuccess(previewOf([account("AAA111", "2026-08-11T09:00")]));
    await user.click(await screen.findByRole("button", { name: "Discard" }));
    expect(screen.queryByTestId("stale-sweep-preview")).not.toBeInTheDocument();

    // Everyone was rated between preview and commit.
    state.options?.mutation.onSuccess({ ...previewOf([]), dryRun: false });
    expect(toastSuccess).toHaveBeenCalledWith("Nothing to remove: no account matched the rule any more.");

    state.options?.mutation.onError(new Error("boom"));
    expect(toastErrorFn).toHaveBeenCalledWith(
      "Could not run the stale-account sweep. Try again.",
      expect.objectContaining({ duration: 8000 }),
    );
  });

  it("disables the buttons while a run is in flight", () => {
    state.isPending = true;
    renderSection();
    expect(screen.getByRole("button", { name: "Preview" })).toBeDisabled();
  });
});
