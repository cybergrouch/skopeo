/**
 * How a player's calibration reads to staff (#1126). The verdict and its inputs all come from the server
 * (`CalibrationService` is the one place the rule is evaluated); this only phrases them.
 */
export type CalibrationOverrideValue = "AUTOMATIC" | "FORCED_OFF" | "FORCED_ON";

export const CALIBRATION_OVERRIDES: readonly { value: CalibrationOverrideValue; label: string; hint: string }[] = [
  { value: "AUTOMATIC", label: "Automatic", hint: "Calibrating until the usual number of rated matches." },
  { value: "FORCED_OFF", label: "Forced off", hint: "Out of calibration now: opponents' ratings move and points are earned." },
  { value: "FORCED_ON", label: "Forced on", hint: "Kept in calibration even past the usual number of matches." },
];

export function overrideLabel(value: string | null | undefined): string {
  return CALIBRATION_OVERRIDES.find((o) => o.value === value)?.label ?? "Automatic";
}

export interface CalibrationFacts {
  inCalibration?: boolean | null;
  matchesRated?: number | null;
  matchesRequired?: number | null;
  override?: string | null;
}

/**
 * "Calibrating · 3 of 10", "Calibrating (forced on)", "Out of calibration (forced off)" or "Settled".
 * Null when the server did not say (an unrated player, or a surface that did not ask).
 */
export function calibrationLabel(facts: CalibrationFacts): string | null {
  if (facts.inCalibration == null) return null;
  if (facts.override === "FORCED_ON") return "Calibrating (forced on)";
  if (facts.override === "FORCED_OFF") return "Out of calibration (forced off)";
  if (!facts.inCalibration) return "Settled";
  return facts.matchesRated != null && facts.matchesRequired != null
    ? `Calibrating · ${facts.matchesRated} of ${facts.matchesRequired}`
    : "Calibrating";
}
