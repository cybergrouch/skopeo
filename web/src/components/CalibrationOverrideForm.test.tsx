import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { setupUser } from "@/test/user";
import { CalibrationOverrideForm } from "./CalibrationOverrideForm";

const { usePut, mutate, invalidateQueries, toastSuccess, toastErrorFn, putOptions } = vi.hoisted(() => ({
  usePut: vi.fn(),
  mutate: vi.fn(),
  invalidateQueries: vi.fn(),
  toastSuccess: vi.fn(),
  toastErrorFn: vi.fn(),
  putOptions: { current: undefined as undefined | { mutation: { onSuccess: () => void; onError: (e: unknown) => void } } },
}));
vi.mock("@/api/generated/ratings/ratings", () => ({
  usePutApiV1UsersUserIdCalibrationOverride: usePut,
  getGetApiV1RatingsCalibrationsQueryKey: () => ["calibrations"],
}));
vi.mock("@/api/generated/users/users", () => ({
  getGetApiV1UsersSearchQueryKey: () => ["users-search"],
}));
vi.mock("@tanstack/react-query", () => ({ useQueryClient: () => ({ invalidateQueries }) }));
vi.mock("sonner", () => ({ toast: { success: toastSuccess } }));
vi.mock("@/observability/toastError", () => ({ toastError: toastErrorFn }));

describe("CalibrationOverrideForm", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    usePut.mockImplementation((options) => {
      putOptions.current = options;
      return { mutate, isPending: false };
    });
  });

  it("stays a single button until asked for", () => {
    render(<CalibrationOverrideForm userId="u1" current="AUTOMATIC" />);
    expect(screen.getByRole("button", { name: "Change calibration" })).toBeInTheDocument();
    expect(screen.queryByRole("radio")).not.toBeInTheDocument();
  });

  it("requires a reason, then saves the chosen override with the trimmed reason", async () => {
    const user = setupUser();
    render(<CalibrationOverrideForm userId="u1" current="AUTOMATIC" />);
    await user.click(screen.getByRole("button", { name: "Change calibration" }));

    expect(screen.getByRole("radio", { name: /Automatic/ })).toBeChecked();
    // Not retroactive, and said so before saving.
    expect(screen.getByText(/do not change/)).toBeInTheDocument();
    await user.click(screen.getByRole("radio", { name: /Forced off/ }));
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
    await user.type(screen.getByLabelText("Reason for the calibration change"), "   ");
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();

    await user.type(screen.getByLabelText("Reason for the calibration change"), "Rated elsewhere ");
    await user.click(screen.getByRole("button", { name: "Save" }));

    expect(mutate).toHaveBeenCalledWith({ userId: "u1", data: { override: "FORCED_OFF", reason: "Rated elsewhere" } });
  });

  it("refreshes both calibration lists on success, collapses, and reports a failure", async () => {
    const user = setupUser();
    render(<CalibrationOverrideForm userId="u1" current="FORCED_ON" />);
    await user.click(screen.getByRole("button", { name: "Change calibration" }));
    expect(screen.getByRole("radio", { name: /Forced on/ })).toBeChecked();

    putOptions.current?.mutation.onError(new Error("boom"));
    expect(toastErrorFn).toHaveBeenCalledWith("Could not update calibration. Try again.", expect.objectContaining({ duration: 8000 }));

    await vi.waitFor(() => putOptions.current?.mutation.onSuccess());
    expect(toastSuccess).toHaveBeenCalledWith("Calibration updated");
    expect(invalidateQueries).toHaveBeenCalledWith({ queryKey: ["calibrations"] });
    expect(invalidateQueries).toHaveBeenCalledWith({ queryKey: ["users-search"] });
    expect(await screen.findByRole("button", { name: "Change calibration" })).toBeInTheDocument();
  });

  it("cancels back to the button, and shows a busy label while saving", async () => {
    const user = setupUser();
    const { unmount } = render(<CalibrationOverrideForm userId="u1" current="AUTOMATIC" />);
    await user.click(screen.getByRole("button", { name: "Change calibration" }));
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.getByRole("button", { name: "Change calibration" })).toBeInTheDocument();
    unmount();

    usePut.mockReturnValue({ mutate, isPending: true });
    render(<CalibrationOverrideForm userId="u1" current="AUTOMATIC" />);
    await user.click(screen.getByRole("button", { name: "Change calibration" }));
    expect(screen.getByRole("button", { name: "Saving…" })).toBeDisabled();
  });
});
