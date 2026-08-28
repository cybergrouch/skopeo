import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { toast } from "sonner";
import { toastError } from "./toastError";
import { noopReporter, setReporter, type ErrorReporter } from "./reporter";

vi.mock("sonner", () => ({ toast: { error: vi.fn() } }));

describe("toastError", () => {
  let captured: Array<{ error: unknown; context?: Record<string, string> }>;

  beforeEach(() => {
    captured = [];
    const spy: ErrorReporter = {
      captureException: (error, context) => captured.push({ error, context }),
      captureMessage: () => {},
      setUser: () => {},
    };
    setReporter(spy);
    vi.mocked(toast.error).mockClear();
  });

  afterEach(() => setReporter(noopReporter));

  it("always shows the toast, whatever the cause", () => {
    toastError("Could not save.", { cause: { response: { status: 400 } } });
    expect(toast.error).toHaveBeenCalledWith("Could not save.", {});
  });

  it("reports a 5xx", () => {
    const cause = { response: { status: 500 } };
    toastError("Could not save.", { cause, duration: 8000 });

    expect(captured).toHaveLength(1);
    expect(captured[0].error).toBe(cause);
    // The toast copy identifies which user-facing action failed — a stack trace alone does not.
    expect(captured[0].context).toEqual({ toast: "Could not save." });
  });

  it("does not report a deliberate 4xx", () => {
    toastError("Sets are tied.", { cause: { response: { status: 400 } } });
    expect(captured).toHaveLength(0);
  });

  it("does not report when there is no cause, i.e. client-side validation", () => {
    // "Each set needs two whole, non-negative game counts" describes the form, not a failure.
    toastError("Each set needs two whole game counts.");
    expect(captured).toHaveLength(0);
    expect(toast.error).toHaveBeenCalled();
  });

  it("does not leak `cause` into the toast options passed to sonner", () => {
    toastError("Could not save.", { cause: new Error("boom"), duration: 8000 });
    expect(toast.error).toHaveBeenCalledWith("Could not save.", {
      duration: 8000,
    });
  });
});
