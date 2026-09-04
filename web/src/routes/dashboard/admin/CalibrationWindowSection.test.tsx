import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { CalibrationWindowSection } from "./CalibrationWindowSection";

const { useGet, usePut, mutate, invalidateQueries, toastSuccess, toastErrorFn } = vi.hoisted(() => ({
  useGet: vi.fn(),
  usePut: vi.fn(),
  mutate: vi.fn(),
  invalidateQueries: vi.fn(),
  toastSuccess: vi.fn(),
  toastErrorFn: vi.fn(),
}));
vi.mock("@/api/generated/settings/settings", () => ({
  useGetApiV1SettingsCalibrationMatches: useGet,
  usePutApiV1SettingsCalibrationMatches: usePut,
  getGetApiV1SettingsCalibrationMatchesQueryKey: () => ["calibration-matches"],
}));
vi.mock("@tanstack/react-query", () => ({
  useQueryClient: () => ({ invalidateQueries }),
}));
vi.mock("sonner", () => ({ toast: { success: toastSuccess } }));
vi.mock("@/observability/toastError", () => ({ toastError: toastErrorFn }));

describe("CalibrationWindowSection", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    usePut.mockReturnValue({ mutate, isPending: false });
    useGet.mockReturnValue({ data: { matches: 10 } });
  });

  it("shows the saved value, with Save disabled until it changes", () => {
    render(<CalibrationWindowSection />);

    expect(screen.getByLabelText(/Calibration window/)).toHaveValue(10);
    // Nothing to save yet — an enabled button here invites a pointless write that would still audit.
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
  });

  it("saves a valid new value", async () => {
    const user = userEvent.setup();
    render(<CalibrationWindowSection />);

    const field = screen.getByLabelText(/Calibration window/);
    await user.clear(field);
    await user.type(field, "5");
    await user.click(screen.getByRole("button", { name: "Save" }));

    expect(mutate).toHaveBeenCalledWith({ data: { matches: 5 } });
  });

  it("refuses a value outside 1 to 100 rather than sending it", async () => {
    const user = userEvent.setup();
    render(<CalibrationWindowSection />);

    const field = screen.getByLabelText(/Calibration window/);
    await user.clear(field);
    await user.type(field, "0");

    // 0 would read as "calibration is off", which is a separate decision — not a value to smuggle in
    // through this field. The server rejects it too; this just avoids a pointless round trip.
    expect(screen.getByText(/between 1 and 100/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
  });

  it("warns that lowering it ends calibrations already in flight", async () => {
    const user = userEvent.setup();
    render(<CalibrationWindowSection />);

    const field = screen.getByLabelText(/Calibration window/);
    await user.clear(field);
    await user.type(field, "3");

    // The consequence an administrator would not otherwise expect: N is evaluated live, so this is not a
    // setting that only affects future designations.
    expect(screen.getByText(/end calibration for players/)).toBeInTheDocument();
  });

  it("warns that raising it puts players back into calibration", async () => {
    const user = userEvent.setup();
    render(<CalibrationWindowSection />);

    const field = screen.getByLabelText(/Calibration window/);
    await user.clear(field);
    await user.type(field, "20");

    expect(screen.getByText(/back into it/)).toBeInTheDocument();
  });

  it("explains what calibration does, using the configured number", () => {
    useGet.mockReturnValue({ data: { matches: 7 } });
    render(<CalibrationWindowSection />);

    expect(screen.getByText(/next 7 rated matches/)).toBeInTheDocument();
    // The two effects, stated where the number is set — a rater reading only this card should understand
    // what they are turning on.
    expect(screen.getByText(/opponents' and partners' do not/)).toBeInTheDocument();
    expect(screen.getByText(/earn no ranking points/)).toBeInTheDocument();
  });

  it("renders while the value is still loading", () => {
    useGet.mockReturnValue({ data: undefined });
    render(<CalibrationWindowSection />);

    expect(screen.getByLabelText(/Calibration window/)).toHaveValue(null);
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
  });

  it("shows a saving state", () => {
    usePut.mockReturnValue({ mutate, isPending: true });
    render(<CalibrationWindowSection />);

    expect(screen.getByRole("button", { name: "Saving…" })).toBeInTheDocument();
  });

  it("confirms a save, clears the edit, and refreshes the value", () => {
    render(<CalibrationWindowSection />);

    // The mutation callbacks are behaviour, not wiring: without the invalidation the card would keep
    // showing the old number after a successful save, and without resetting the draft the field would
    // stay "edited" so Save would remain enabled against a value already stored.
    const options = usePut.mock.calls[0][0];
    options.mutation.onSuccess();

    expect(toastSuccess).toHaveBeenCalledWith("Saved");
    expect(invalidateQueries).toHaveBeenCalledWith({ queryKey: ["calibration-matches"] });
  });

  it("surfaces a failed save instead of silently leaving the old value", () => {
    render(<CalibrationWindowSection />);

    const options = usePut.mock.calls[0][0];
    const cause = new Error("boom");
    options.mutation.onError(cause);

    expect(toastErrorFn).toHaveBeenCalledWith(
      "Could not update the calibration window. Try again.",
      { cause, duration: 8000 },
    );
  });
});
