import { createContext, useContext, useEffect, useState } from "react";
import {
  useGetApiV1Theme,
  useGetApiV1UsersMeTheme,
} from "@/api/generated/settings/settings";
import { useAuth } from "@/auth/useAuth";
import {
  resolveEffectiveTheme,
  type EffectiveThemeInputs,
  type ThemeName,
} from "@/lib/season";
import { applyThemeFavicon } from "./favicon";

// Re-resolve hourly so an AUTO day-rollover (and the #514 season boundary) swaps without a refetch.
const REAPPLY_INTERVAL_MS = 60 * 60_000;

/** Apply a resolved theme name to the document + favicon — the single write point (#378, #514). */
function applyTheme(theme: ThemeName) {
  document.documentElement.dataset.theme = theme;
  // Keep the tab favicon in lockstep with the theme (#386): same single source of truth.
  applyThemeFavicon(theme);
}

/** The caller's local theme, reported up to {@link ThemeProvider} by {@link LocalThemeApplier}. */
type LocalTheme = Pick<EffectiveThemeInputs, "localTheme" | "localSetAt">;

/**
 * Lets the authenticated {@link LocalThemeApplier} report the caller's local theme UP to
 * {@link ThemeProvider}, which is the single owner of the `data-theme` write. Null local theme (logged
 * out or no choice) → the effective theme collapses to the global one.
 */
const LocalThemeContext = createContext<(local: LocalTheme) => void>(() => {});

/**
 * Global theme provider (#378) and single `data-theme` writer (#514). Polls the public `GET
 * /api/v1/theme` setting (~60s, plus on tab focus) and live-swaps the CSS token set on
 * `document.documentElement.dataset.theme` — no reload, no flash. An hourly tick re-resolves AUTO
 * across a day-rollover. Mounted ABOVE AuthProvider (the GET needs no auth token), so it drives the
 * logged-OUT path on its own.
 *
 * The per-user "local theme" (#514) needs the authenticated caller, which isn't available here. Rather
 * than move this provider below AuthProvider (breaking the public/logged-out skinning), a nested
 * {@link LocalThemeApplier} — mounted INSIDE the authed tree — fetches the caller's local theme and
 * reports it back up via {@link LocalThemeContext}. This provider then computes the EFFECTIVE theme
 * (`resolveEffectiveTheme`) from the global setting + that local theme, so there is exactly ONE writer
 * and no parent/child clobbering. With no local theme (logged out or unset) the effective theme equals
 * the global one, so the logged-out behavior is unchanged.
 */
export function ThemeProvider({ children }: { children: React.ReactNode }) {
  const { data } = useGetApiV1Theme({
    query: {
      refetchInterval: 60_000,
      refetchOnWindowFocus: true,
      staleTime: 30_000,
      retry: false,
    },
  });
  const global = data?.theme;
  const [local, setLocal] = useState<LocalTheme>({
    localTheme: null,
    localSetAt: null,
  });

  useEffect(() => {
    const apply = () =>
      applyTheme(
        resolveEffectiveTheme(
          { global, localTheme: local.localTheme, localSetAt: local.localSetAt },
          new Date(),
        ),
      );
    apply();
    const id = setInterval(apply, REAPPLY_INTERVAL_MS);
    return () => clearInterval(id);
  }, [global, local.localTheme, local.localSetAt]);

  return (
    <LocalThemeContext.Provider value={setLocal}>
      {children}
    </LocalThemeContext.Provider>
  );
}

/**
 * The authenticated local-theme layer (#514). Mounted inside AuthProvider (so its `GET
 * /api/v1/users/me/theme` carries the Firebase token). Fetches the caller's local theme and reports it
 * up to {@link ThemeProvider} — the sole `data-theme` writer — which folds it into the effective theme.
 * The query is disabled while logged out (the request would 401), and any prior local theme is cleared
 * so ThemeProvider falls back to the global-only value. Renders nothing.
 */
export function LocalThemeApplier() {
  const report = useContext(LocalThemeContext);
  const { user } = useAuth();
  const { data } = useGetApiV1UsersMeTheme({
    query: {
      enabled: Boolean(user),
      refetchOnWindowFocus: true,
      staleTime: 30_000,
      retry: false,
    },
  });
  const localTheme = user ? (data?.theme ?? null) : null;
  const localSetAt = user ? (data?.setAt ?? null) : null;

  useEffect(() => {
    report({ localTheme, localSetAt });
  }, [report, localTheme, localSetAt]);

  return null;
}
