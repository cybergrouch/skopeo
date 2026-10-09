import { describe, expect, it } from "vitest";
import { calibrationLabel, overrideLabel } from "./calibration";

describe("calibrationLabel", () => {
  it("says nothing when the server did not say", () => {
    expect(calibrationLabel({})).toBeNull();
    expect(calibrationLabel({ inCalibration: null })).toBeNull();
  });

  it("shows progress for an automatic calibration", () => {
    expect(calibrationLabel({ inCalibration: true, matchesRated: 3, matchesRequired: 10, override: "AUTOMATIC" })).toBe(
      "Calibrating · 3 of 10",
    );
    expect(calibrationLabel({ inCalibration: true })).toBe("Calibrating");
  });

  it("names a forced override rather than a misleading count", () => {
    // Forced on past N would otherwise read "12 of 10".
    expect(calibrationLabel({ inCalibration: true, matchesRated: 12, matchesRequired: 10, override: "FORCED_ON" })).toBe(
      "Calibrating (forced on)",
    );
    expect(calibrationLabel({ inCalibration: false, matchesRated: 1, matchesRequired: 10, override: "FORCED_OFF" })).toBe(
      "Out of calibration (forced off)",
    );
  });

  it("reads a finished automatic calibration as settled", () => {
    expect(calibrationLabel({ inCalibration: false, override: "AUTOMATIC" })).toBe("Settled");
  });
});

describe("overrideLabel", () => {
  it("maps each value, defaulting to Automatic", () => {
    expect(overrideLabel("FORCED_OFF")).toBe("Forced off");
    expect(overrideLabel("FORCED_ON")).toBe("Forced on");
    expect(overrideLabel("AUTOMATIC")).toBe("Automatic");
    expect(overrideLabel(undefined)).toBe("Automatic");
  });
});
