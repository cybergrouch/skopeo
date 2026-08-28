import { Component, type ErrorInfo, type ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { reporter } from "./reporter";

type Props = { children: ReactNode };
type State = { crashed: boolean };

/**
 * The recoverable page shown instead of a white screen.
 *
 * Deliberately plain: it must render when something in the app is already broken, so it depends on one
 * primitive and no data, no hooks and no context. A fallback that needs the theme provider or a query
 * client is a fallback that can fail for the same reason as the page it replaced.
 */
function CrashFallback() {
  return (
    <div className="flex min-h-svh flex-col items-center justify-center gap-4 p-6 text-center">
      <div className="space-y-2">
        <h1 className="text-lg font-semibold">Something went wrong</h1>
        <p className="max-w-md text-sm text-muted-foreground">
          This page failed to load. Reloading usually fixes it — if it keeps
          happening, the problem is on our side and we&apos;ve been told about
          it.
        </p>
      </div>
      <div className="flex gap-2">
        {/*
          A full reload, not a router navigation: React has unmounted a broken tree and the app's state
          may be inconsistent, so re-rendering in place is not reliably a recovery. It also fixes the
          most likely cause — a long-lived tab asking for a code-split chunk that a newer deploy has
          replaced (#752/#277).
        */}
        <Button onClick={() => window.location.reload()}>
          Reload the page
        </Button>
        <Button variant="outline" onClick={() => window.location.assign("/")}>
          Go to the home page
        </Button>
      </div>
    </div>
  );
}

/**
 * Catches render errors anywhere below it and shows [CrashFallback] instead of an empty document (#807).
 *
 * Before this, a render crash produced a white screen: the user saw nothing actionable and no signal
 * reached anyone. It is worth doing on its own merits, independent of any error-tracking vendor — the
 * reporting below goes through the #807 seam, so it is a no-op until one is installed while the
 * user-facing recovery works immediately.
 *
 * A class component because `componentDidCatch` has no hook equivalent; React provides no other way to
 * catch a descendant's render error.
 */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { crashed: false };

  static getDerivedStateFromError(): State {
    return { crashed: true };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // `componentStack` is what makes a minified production trace legible — it names the component tree,
    // which survives minification better than the JS frames do.
    reporter().captureException(error, {
      componentStack: info.componentStack ?? "(unavailable)",
    });
  }

  render() {
    return this.state.crashed ? <CrashFallback /> : this.props.children;
  }
}
