import { afterEach, describe, expect, it, vi } from "vitest";
import {
  consoleReporter,
  noopReporter,
  reporter,
  setReporter,
} from "./reporter";

describe("reporter", () => {
  afterEach(() => {
    setReporter(noopReporter);
    vi.restoreAllMocks();
  });

  it("defaults to the no-op reporter, so an unconfigured app routes signals nowhere", () => {
    expect(reporter()).toBe(noopReporter);
  });

  it("noopReporter swallows every call without throwing", () => {
    // It sits on the error path, so throwing here would turn a handled failure into a crash.
    expect(() => noopReporter.captureException(new Error("x"))).not.toThrow();
    expect(() => noopReporter.captureMessage("x", "warning")).not.toThrow();
    expect(() => noopReporter.setUser("u1")).not.toThrow();
  });

  it("setReporter is read at call time, so already-imported modules see the change", () => {
    // toastError and the boundary import `reporter` at module load; if the port were captured by value
    // rather than read per call, installing an adapter in main.tsx would have no effect on them.
    setReporter(consoleReporter);
    expect(reporter()).toBe(consoleReporter);
  });

  it("consoleReporter writes to the console, so dev wiring is observable", () => {
    const error = vi.spyOn(console, "error").mockImplementation(() => {});
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    const info = vi.spyOn(console, "info").mockImplementation(() => {});

    const boom = new Error("boom");
    consoleReporter.captureException(boom, { toast: "Could not save." });
    consoleReporter.captureMessage("handled but wrong", "warning");
    consoleReporter.setUser("u1");

    expect(error).toHaveBeenCalledWith("[reporter] captureException", boom, {
      toast: "Could not save.",
    });
    expect(warn).toHaveBeenCalledWith(
      "[reporter] captureMessage(warning)",
      "handled but wrong",
      {},
    );
    expect(info).toHaveBeenCalledWith("[reporter] setUser", "u1");
  });
})
