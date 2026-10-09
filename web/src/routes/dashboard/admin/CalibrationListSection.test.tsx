import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { setupUser } from "@/test/user";
import { CalibrationListSection } from "./CalibrationListSection";

const { useList, formProps } = vi.hoisted(() => ({ useList: vi.fn(), formProps: vi.fn() }));
vi.mock("@/api/generated/ratings/ratings", () => ({ useGetApiV1RatingsCalibrations: useList }));
// The form's own behaviour is CalibrationOverrideForm.test.tsx's; here only its wiring.
vi.mock("@/components/CalibrationOverrideForm", () => ({
  CalibrationOverrideForm: (props: { userId: string; current: string }) => {
    formProps(props);
    return <span data-testid={`form-${props.userId}`} />;
  },
}));

function result(items: unknown[], total = items.length) {
  return { data: { items, total }, isLoading: false, isError: false };
}

const fresh = {
  userId: "u1",
  publicCode: "AAA111",
  displayName: "Ana",
  level: "4.0",
  inCalibration: true,
  matchesRated: 2,
  matchesRequired: 10,
  override: "AUTOMATIC",
};
const forced = {
  userId: "u2",
  publicCode: "BBB222",
  displayName: null,
  level: null,
  inCalibration: true,
  matchesRated: 12,
  matchesRequired: 10,
  override: "FORCED_ON",
  overrideReason: "Still unsure",
  overrideByName: "Rae Rater",
  overrideAt: "2026-10-09T10:00:00",
};

function renderSection() {
  return render(
    <MemoryRouter>
      <CalibrationListSection />
    </MemoryRouter>,
  );
}

describe("CalibrationListSection", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useList.mockReturnValue(result([fresh, forced]));
  });

  it("lists each player's progress, override and reason, with the override control", () => {
    renderSection();

    expect(useList).toHaveBeenCalledWith({ includeForcedOff: false, limit: 20, offset: 0 });
    expect(screen.getByRole("link", { name: /Ana/ })).toHaveAttribute("href", "/players/AAA111");
    expect(screen.getByText("Calibrating · 2 of 10")).toBeInTheDocument();
    expect(screen.getByText("Calibrating (forced on)")).toBeInTheDocument();
    // Who, when and why, for a forced override; nothing of the kind for an untouched one.
    expect(screen.getByText("Forced on by Rae Rater on 2026-10-09: Still unsure")).toBeInTheDocument();
    expect(formProps).toHaveBeenCalledWith({ userId: "u2", current: "FORCED_ON" });
    expect(screen.getByRole("link", { name: /BBB222/ })).toBeInTheDocument();
  });

  it("asks for players forced out too when ticked, back on the first page", async () => {
    const user = setupUser();
    renderSection();
    await user.click(screen.getByRole("checkbox", { name: /forced out of calibration/ }));
    expect(useList).toHaveBeenLastCalledWith({ includeForcedOff: true, limit: 20, offset: 0 });
  });

  it("shows loading, error and empty states", () => {
    useList.mockReturnValue({ data: undefined, isLoading: true, isError: false });
    const { unmount } = renderSection();
    expect(screen.getByText("Loading…")).toBeInTheDocument();
    unmount();

    useList.mockReturnValue({ data: undefined, isLoading: false, isError: true });
    const second = renderSection();
    expect(screen.getByRole("alert")).toHaveTextContent("Could not load");
    second.unmount();

    useList.mockReturnValue(result([]));
    renderSection();
    expect(screen.getByText("Nobody is in calibration.")).toBeInTheDocument();
  });

  it("reads a reason with no setter or date on record, and pages", async () => {
    const user = setupUser();
    useList.mockReturnValue(result([{ ...forced, overrideByName: null, overrideByPublicCode: null, overrideAt: null }], 45));
    renderSection();
    expect(screen.getByText("Forced on: Still unsure")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "2" }));
    expect(useList).toHaveBeenLastCalledWith({ includeForcedOff: false, limit: 20, offset: 20 });
  });
});
