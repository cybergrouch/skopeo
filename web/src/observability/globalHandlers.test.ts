import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { installGlobalErrorHandlers } from "./globalHandlers";
import { noopReporter, setReporter, type ErrorReporter } from "./reporter";

describe("installGlobalErrorHandlers", () => {
  let captured: Array<{ error: unknown; context?: Record<string, string> }>;
  let teardown: () => void;

  beforeEach(() => {
    captured = [];
    const spy: ErrorReporter = {
      captureException: (error, context) => captured.push({ error, context }),
      captureMessage: () => {},
      setUser: () => {},
    };
    setReporter(spy);
    teardown = installGlobalErrorHandlers();
  });

  afterEach(() => {
    teardown();
    setReporter(noopReporter);
  });

  it("captures a throw from outside the render pass, which a boundary cannot see", () => {
    const error = new Error("from an event handler");
    window.dispatchEvent(new ErrorEvent("error", { error }));

    expect(captured).toHaveLength(1);
    expect(captured[0].error).toBe(error);
    expect(captured[0].context).toEqual({ source: "window.onerror" });
  });

  it("falls back to the message when a cross-origin script withholds the error", () => {
    window.dispatchEvent(new ErrorEvent("error", { message: "Script error." }));
    expect(captured[0].error).toBe("Script error.");
  });

  it("captures an unhandled rejection, e.g. a forgotten mutateAsync catch", () => {
    const reason = new Error("nobody awaited this");
    // jsdom does not fire unhandledrejection natively, so the event is dispatched directly.
    const event = new Event("unhandledrejection") as Event & { reason?: unknown };
    event.reason = reason;
    window.dispatchEvent(event);

    expect(captured).toHaveLength(1);
    expect(captured[0].error).toBe(reason);
    expect(captured[0].context).toEqual({ source: "unhandledrejection" });
  });

  it("stops capturing after teardown, so listeners do not leak between tests", () => {
    teardown();

    // With our listener gone, an unhandled `error` event is reported by jsdom as an uncaught error and
    // fails the run. Mark it handled for the duration of the dispatch so the assertion is about the
    // teardown, not about jsdom's reporting.
    const swallow = (event: Event) => event.preventDefault();
    window.addEventListener("error", swallow);
    try {
      window.dispatchEvent(
        new ErrorEvent("error", { error: new Error("x"), cancelable: true }),
      );
    } finally {
      window.removeEventListener("error", swallow);
    }

    expect(captured).toHaveLength(0);
  });
});
