import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
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
    // Otherwise a stubbed `location` leaks into every later test in the run.
    vi.unstubAllGlobals();
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

  it("reloads the page when the primary action is used", async () => {
    // The reason the fallback exists: asserting the button renders says nothing about recovery working.
    // A full reload (not a router navigation) is deliberate — React has unmounted a broken tree, and
    // this is what fixes the likeliest cause, a stale tab requesting a replaced chunk (#752/#277).
    const reload = vi.fn();
    vi.stubGlobal("location", { ...window.location, reload, assign: vi.fn() });

    render(
      <ErrorBoundary>
        <Boom />
      </ErrorBoundary>,
    );
    await userEvent.click(
      screen.getByRole("button", { name: /reload the page/i }),
    );

    expect(reload).toHaveBeenCalledOnce();
  });

  it("navigates to the home page when the secondary action is used", async () => {
    const assign = vi.fn();
    vi.stubGlobal("location", { ...window.location, assign, reload: vi.fn() });

    render(
      <ErrorBoundary>
        <Boom />
      </ErrorBoundary>,
    );
    await userEvent.click(
      screen.getByRole("button", { name: /go to the home page/i }),
    );

    expect(assign).toHaveBeenCalledWith("/");
  });

  it("still reports when React supplies no component stack", () => {
    // ErrorInfo.componentStack is typed nullable, so the fallback is a real branch. Driven directly
    // because React always populates it — the point is that a null does not become "null" in a tag.
    const boundary = new ErrorBoundary({ children: null });
    boundary.componentDidCatch(new Error("no stack"), { componentStack: null });

    expect(captured).toHaveLength(1);
    expect(captured[0].context?.componentStack).toBe("(unavailable)");
  });
})
