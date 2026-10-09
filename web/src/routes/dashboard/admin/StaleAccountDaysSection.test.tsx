import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { StaleAccountDaysSection } from "./StaleAccountDaysSection";

const { useGet, usePut, mutate, invalidateQueries, toastSuccess, toastErrorFn, putOptions } = vi.hoisted(() => ({
  useGet: vi.fn(),
  usePut: vi.fn(),
  mutate: vi.fn(),
  invalidateQueries: vi.fn(),
  toastSuccess: vi.fn(),
  toastErrorFn: vi.fn(),
  putOptions: { current: undefined as undefined | { mutation: { onSuccess: () => void; onError: (e: unknown) => void } } },
}));
vi.mock("@/api/generated/settings/settings", () => ({
  useGetApiV1SettingsStaleAccountDays: useGet,
  usePutApiV1SettingsStaleAccountDays: usePut,
  getGetApiV1SettingsStaleAccountDaysQueryKey: () => ["stale-account-days"],
}));
vi.mock("@/api/generated/ratings/ratings", () => ({
  getGetApiV1UsersPendingAssessmentQueryKey: () => ["pending-assessment"],
}));
vi.mock("@tanstack/react-query", () => ({
  useQueryClient: () => ({ invalidateQueries }),
}));
vi.mock("sonner", () => ({ toast: { success: toastSuccess } }));
vi.mock("@/observability/toastError", () => ({ toastError: toastErrorFn }));

describe("StaleAccountDaysSection", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    usePut.mockImplementation((options) => {
      putOptions.current = options;
      return { mutate, isPending: false };
    });
    useGet.mockReturnValue({ data: { days: 30 } });
  });

  it("shows the saved value, with Save disabled until it changes", () => {
    render(<StaleAccountDaysSection />);

    expect(screen.getByLabelText(/Stale-account threshold/)).toHaveValue(30);
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
    expect(screen.getByText(/still unrated 30 days after/)).toBeInTheDocument();
  });

  it("saves a valid new value", async () => {
    const user = userEvent.setup();
    render(<StaleAccountDaysSection />);

    const field = screen.getByLabelText(/Stale-account threshold/);
    await user.clear(field);
    await user.type(field, "45");
    await user.click(screen.getByRole("button", { name: "Save" }));

    expect(mutate).toHaveBeenCalledWith({ data: { days: 45 } });
  });

  it("refuses a value outside 7 to 365 rather than sending it", async () => {
    const user = userEvent.setup();
    render(<StaleAccountDaysSection />);

    const field = screen.getByLabelText(/Stale-account threshold/);
    await user.clear(field);
    await user.type(field, "3");

    expect(screen.getByText(/between 7 and 365/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
  });

  it("warns that lowering it brings removals forward, and says nothing when raising it", async () => {
    const user = userEvent.setup();
    render(<StaleAccountDaysSection />);
    const field = screen.getByLabelText(/Stale-account threshold/);

    await user.clear(field);
    await user.type(field, "14");
    expect(screen.getByText(/brings removal dates forward/)).toBeInTheDocument();

    await user.clear(field);
    await user.type(field, "60");
    expect(screen.queryByText(/brings removal dates forward/)).not.toBeInTheDocument();
  });

  it("refreshes the setting and the pending list on save, and reports a failure", () => {
    render(<StaleAccountDaysSection />);

    putOptions.current?.mutation.onSuccess();
    expect(toastSuccess).toHaveBeenCalledWith("Saved");
    // The pending list's removal dates are derived from this number, so they are stale the moment it changes.
    expect(invalidateQueries).toHaveBeenCalledWith({ queryKey: ["stale-account-days"] });
    expect(invalidateQueries).toHaveBeenCalledWith({ queryKey: ["pending-assessment"] });

    putOptions.current?.mutation.onError(new Error("boom"));
    expect(toastErrorFn).toHaveBeenCalledWith(
      "Could not update the stale-account threshold. Try again.",
      expect.objectContaining({ duration: 8000 }),
    );
  });

  it("shows a busy label while saving, and a placeholder before the value loads", () => {
    usePut.mockReturnValue({ mutate, isPending: true });
    useGet.mockReturnValue({ data: undefined });
    render(<StaleAccountDaysSection />);

    expect(screen.getByRole("button", { name: "Saving…" })).toBeDisabled();
    expect(screen.getByText(/still unrated N days after/)).toBeInTheDocument();
  });
});
