import { FirebaseError } from "firebase/app";

/**
 * Firebase auth codes that mean *our configuration or the provider is broken*, not that the user typed
 * the wrong thing (#807).
 *
 * This distinction exists because of #647, the Facebook-login outage: users could not sign in, and
 * nothing alerted. It is worth being precise about why a backend metric would not have caught it —
 * **a failing federated sign-in never reaches our API at all.** The Firebase SDK talks to Google
 * directly, so there is no request to produce a 401, and the per-endpoint 4xx panel (#809) would show
 * nothing. The only place that failure is observable is the browser.
 *
 * `auth/operation-not-allowed` is precisely the #647 shape — a sign-in provider disabled in the Firebase
 * console. The rest are configuration or transport faults with the same property: no user action can
 * cause them, so any occurrence is a real signal.
 *
 * Everything else Firebase raises is a user outcome (wrong password, cancelled popup, email already
 * registered). Those are mapped to friendly sentences by `authErrorMessage` and must not be reported —
 * a steady drip of wrong-password events would exhaust a quota and bury the codes above.
 */
const FIREBASE_FAULT_CODES: ReadonlySet<string> = new Set([
  "auth/operation-not-allowed",
  "auth/configuration-not-found",
  "auth/invalid-api-key",
  "auth/unauthorized-domain",
  "auth/internal-error",
  "auth/network-request-failed",
]);

/** The HTTP status of an axios-shaped error, or undefined when the request never got a response. */
function httpStatus(error: unknown): number | undefined {
  const status = (error as { response?: { status?: unknown } })?.response
    ?.status;
  return typeof status === "number" ? status : undefined;
}

/**
 * Whether [error] indicates something is actually wrong with the system, as opposed to the API or the
 * auth provider behaving exactly as designed.
 *
 * The reasoning follows #751's outage definition, so the frontend and the dashboard agree on what counts:
 *
 * - **4xx is not a fault.** Deliberate 4xx are this API's normal contract — 409 "match is not rated yet",
 *   404 for a mistyped public code (those pages are shared by QR, so stale links are expected traffic),
 *   400 for server-side form validation. Reporting them would drown the real signal, and on the free
 *   tier would exhaust the quota outright.
 * - **401 especially is not a fault.** Every anonymous visit to a public page produces one by design:
 *   `ClubPage`, `MatchPage` and `PlayerProfilePage` all call `useGetApiV1UsersMe()` unguarded so they can
 *   detect a manager while still rendering for a logged-out visitor.
 * - **5xx is a fault** — that is #751's definition of an outage.
 * - **No response at all is a fault**: a network failure, a CORS rejection, a DNS problem, a dead
 *   revision. Indistinguishable from the browser's point of view, and all worth knowing about.
 * - **A thrown value that is not an HTTP error** is a fault by default — a TypeError in a handler, a
 *   failed chunk load. Defaulting to "report" here is deliberate: an unrecognised shape is exactly the
 *   case we have not thought about.
 */
export function isUnexpected(error: unknown): boolean {
  if (error instanceof FirebaseError) {
    return FIREBASE_FAULT_CODES.has(error.code);
  }

  const status = httpStatus(error);
  if (status === undefined) return true;
  return status >= 500;
}
