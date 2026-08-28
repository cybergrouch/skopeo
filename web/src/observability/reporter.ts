/**
 * The vendor seam for frontend error reporting (#807).
 *
 * #751 deliberately defers the error-tracking vendor until the cost question is settled, but the
 * frontend cannot be made vendor-neutral by configuration the way the backend can: on the backend every
 * candidate integrates as a Logback appender, so adding one is a dependency plus XML. An SDK in the
 * browser is a code dependency.
 *
 * So the code depends on this port instead. Everything that produces an error signal — the error
 * boundary, the global handlers, and all `toastError` call sites — talks to `reporter()`. Adding a vendor
 * later is: install the SDK, write one adapter implementing this interface, call `setReporter` once in
 * `main.tsx`. None of the call sites change.
 *
 * **The context contract is deliberately narrow.** `ErrorContext` is `Record<string, string>`, not
 * `unknown` or an arbitrary object. The same reasoning as the backend's MDC allowlist (#806): whatever is
 * attached here is published to whatever sink is installed, so a signature that accepts a domain object
 * would let a caller pass a whole user profile without noticing. Callers must pick fields and stringify
 * them, which makes the decision visible at the call site.
 */
export type ErrorContext = Record<string, string>;

export type ReportLevel = "warning" | "error";

export interface ErrorReporter {
  /** A thrown value with a stack — a render crash, a rejected promise, an unexpected API failure. */
  captureException(error: unknown, context?: ErrorContext): void;
  /** A described condition with no throwable behind it. */
  captureMessage(
    message: string,
    level: ReportLevel,
    context?: ErrorContext,
  ): void;
  /**
   * Associate later reports with a user, or clear on sign-out.
   *
   * Takes an **opaque id only** — never an email or a display name. A vendor's UI will happily show
   * whatever it is given, and an id is enough to correlate a report with a support conversation.
   */
  setUser(id: string | null): void;
}

/** The production default until a vendor exists: signals are routed, and go nowhere. */
export const noopReporter: ErrorReporter = {
  captureException: () => {},
  captureMessage: () => {},
  setUser: () => {},
};

/**
 * Development default, so the wiring is observable from day one.
 *
 * Without this the seam would be invisible until a vendor lands, and a miswired boundary or handler
 * would look identical to one that works.
 */
export const consoleReporter: ErrorReporter = {
  captureException: (error, context) =>
    console.error("[reporter] captureException", error, context ?? {}),
  captureMessage: (message, level, context) =>
    console.warn(`[reporter] captureMessage(${level})`, message, context ?? {}),
  setUser: (id) =>
    console.info("[reporter] setUser", id),
};

let active: ErrorReporter = noopReporter;

/** Install the active reporter. Called once from `main.tsx`. */
export function setReporter(next: ErrorReporter): void {
  active = next;
}

/** The active reporter. Read at call time, so `setReporter` applies to already-imported modules. */
export function reporter(): ErrorReporter {
  return active;
}
