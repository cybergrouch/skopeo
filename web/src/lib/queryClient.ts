import { QueryClient } from '@tanstack/react-query'

/** The app-wide staleness window. 30s — see [createQueryClient]. */
export const DEFAULT_STALE_TIME_MS = 30_000

/**
 * The app's QueryClient, with the defaults every query inherits (#1093).
 *
 * `staleTime` was unset, so **0** — every query stale the instant it resolved. With
 * `refetchOnWindowFocus: false` alongside it the two defaults pulled in opposite directions: a
 * dashboard tab round trip refired every query on the tab (ProfileTab alone has 8), while a tab left
 * open all afternoon never refreshed at all. The one case where refetching is actually wanted was
 * the case switched off.
 *
 * 30 seconds is not a new number — `ThemeProvider` and the health check had already reached for it
 * locally, which is what a missing default looks like from the inside.
 *
 * **Both halves move together, deliberately.** `refetchOnWindowFocus` was almost certainly disabled
 * *because* staleness was 0: focus-refetching with no window is a request storm on every alt-tab.
 * Give it a window and the flag becomes cheap and fixes the stale-tab half. Changing one without the
 * other would repeat the original mistake pointing the other way.
 *
 * Anything needing fresher data than this says so at the call site — see the live board in
 * `LiveScoringPage`, which pins `staleTime: 0` because an umpire must never read a cached score.
 */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: 1, staleTime: DEFAULT_STALE_TIME_MS, refetchOnWindowFocus: true },
    },
  })
}
