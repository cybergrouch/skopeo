import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { ErrorBoundary } from "./ErrorBoundary";
import { noopReporter, setReporter, type ErrorReporter } from "./reporter";

function Boom(): React.ReactNode {
  throw new Error("render exploded");
}

describe("ErrorBoundary", () => {
  let captured: Array<{ error: unknown; context?: Record<string, string> }>;
  let consoleError: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    captured = [];
    const spy: ErrorReporter = {
      captureException: (error, context) => captured.push({ error, context }),
      captureMessage: () => {},
      setUser: () => {},
    };
    setReporter(spy);
    // React logs caught render errors to console.error by design; silence it so a passing run is quiet.
    consoleError = vi.spyOn(console, "error").mockImplementation(() => {});
  });

  afterEach(() => {
    consoleError.mockRestore();
    setReporter(noopReporter);
  });

  it("renders its children when nothing throws", () => {
    render(
      <ErrorBoundary>
        <p>all good</p>
      </ErrorBoundary>,
    );
    expect(screen.getByText("all good")).toBeInTheDocument();
  });

  it("shows a recoverable page instead of a white screen", () => {
    render(
      <ErrorBoundary>
        <Boom />
      </ErrorBoundary>,
    );

    expect(screen.getByText("Something went wrong")).toBeInTheDocument();
    // Recoverable is the point: the user needs an action, not just an apology.
    expect(
      screen.getByRole("button", { name: /reload the page/i }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /go to the home page/i }),
    ).toBeInTheDocument();
  });

  it("reports the crash with the component stack", () => {
    render(
      <ErrorBoundary>
        <Boom />
      </ErrorBoundary>,
    );

    expect(captured).toHaveLength(1);
    expect((captured[0].error as Error).message).toBe("render exploded");
    // componentStack names the component tree, which survives minification better than JS frames.
    expect(captured[0].context?.componentStack).toContain("Boom");
  });
})
