import { describe, expect, it } from "vitest";
import { removalNotice } from "./staleAccount";

// Local noon, so the test reads the same in every time zone.
const today = new Date(2026, 9, 9, 12, 0);

describe("removalNotice", () => {
  it("says nothing for an account the sweep's rule does not match", () => {
    expect(removalNotice(null, today)).toBeNull();
    expect(removalNotice(undefined, today)).toBeNull();
  });

  it("counts the days to the removal date", () => {
    expect(removalNotice("2026-10-30", today)).toEqual({ text: "Removed in 21 days unless rated", urgent: false });
  });

  it("flags the last week as urgent", () => {
    expect(removalNotice("2026-10-16", today)).toEqual({ text: "Removed in 7 days unless rated", urgent: true });
    expect(removalNotice("2026-10-10", today)).toEqual({ text: "Removed tomorrow unless rated", urgent: true });
  });

  it("reads a date already reached as the next cleanup, not a negative count", () => {
    expect(removalNotice("2026-10-09", today)?.text).toBe("Removed at the next account cleanup unless rated");
    expect(removalNotice("2026-09-20", today)?.text).toBe("Removed at the next account cleanup unless rated");
  });

  it("compares calendar dates, so the time of day does not shift the count", () => {
    const lateEvening = new Date(2026, 9, 9, 23, 59);
    expect(removalNotice("2026-10-30", lateEvening)?.text).toBe("Removed in 21 days unless rated");
  });

  it("defaults to today", () => {
    expect(removalNotice("2999-01-01")?.urgent).toBe(false);
  });
});
