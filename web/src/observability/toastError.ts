import { toast } from "sonner";
import type { ExternalToast } from "sonner";
import { isUnexpected } from "./classify";
import { reporter } from "./reporter";

export type ToastErrorOptions = ExternalToast & {
  /**
   * The error behind this toast, when there is one.
   *
   * Supply it wherever a caught error is in scope; omit it for a purely client-side message ("each set
   * needs two whole game counts"), which describes the form rather than a failure and has nothing to
   * report. Whether the error is then reported is [isUnexpected]'s decision, not the call site's — so no
   * call site has to remember the 4xx-versus-5xx rule.
   */
  cause?: unknown;
};

/**
 * Show an error toast and, when the underlying failure is genuinely unexpected, report it (#807).
 *
 * Replaces bare `toast.error` across the app. Before this, 64 call sites told the user and forgot: a 500
 * from the API looked exactly like a validation message to everyone except the user in front of it.
 *
 * The reported level is `warning`, not `error` — these are failures the app *handled*, and keeping them
 * distinguishable from an uncaught crash is the point of the "handled but wrong" category in #751.
 */
export function toastError(message: string, options?: ToastErrorOptions): void {
  const { cause, ...toastOptions } = options ?? {};

  if (cause !== undefined && isUnexpected(cause)) {
    // The toast text is attached deliberately: it says which user-facing action failed, which a stack
    // trace alone does not. It is our own copy, so it carries nothing from the request.
    reporter().captureException(cause, { toast: message });
  }

  toast.error(message, toastOptions);
}
