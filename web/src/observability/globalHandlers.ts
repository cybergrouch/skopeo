import { reporter } from "./reporter";

/**
 * Catch the two error classes a React error boundary cannot see (#807).
 *
 * A boundary only catches errors thrown during **render**. These two escape it entirely and are, between
 * them, most of what actually goes wrong in a browser:
 *
 * - `error` — a throw from an event handler, a `setTimeout`, or any callback outside the render pass.
 * - `unhandledrejection` — a rejected promise nobody awaited. In this app that covers a failed
 *   `mutateAsync` whose `catch` was forgotten, and a dynamic `import()` for a code-split route that
 *   fails to load (#277) when a stale tab asks for a chunk a newer deploy replaced (#752).
 *
 * Returns a teardown function so a test can install and remove the listeners without leaking them
 * between cases.
 */
export function installGlobalErrorHandlers(
  target: Window = window,
): () => void {
  const onError = (event: ErrorEvent) => {
    // Prefer `event.error`: it carries the stack. `event.message` is all that survives a cross-origin
    // script error, where the browser deliberately withholds detail.
    reporter().captureException(event.error ?? event.message, {
      source: "window.onerror",
    });
  };

  const onRejection = (event: PromiseRejectionEvent) => {
    reporter().captureException(event.reason, { source: "unhandledrejection" });
  };

  target.addEventListener("error", onError);
  target.addEventListener("unhandledrejection", onRejection);

  return () => {
    target.removeEventListener("error", onError);
    target.removeEventListener("unhandledrejection", onRejection);
  };
}
