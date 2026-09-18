// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { QueryClient } from "@tanstack/react-query";
import { getGetApiV1ClubsCodeCodeEventsQueryKey } from "@/api/generated/clubs/clubs";

/**
 * Invalidate the club page's per-bucket event lists (#1055).
 *
 * A club has **two** queries and it is easy to refresh the wrong one. `GET /clubs/code/{code}` is the
 * club *detail* — it renders the page header. `GET /clubs/code/{code}/events?bucket=…` is what the
 * Upcoming / Unfinalized / Finalized cards read (#786), and it is the one a mutation has to touch.
 * Before this existed, nothing in the app invalidated it at all, so a newly created event stayed
 * invisible until a manual refresh.
 *
 * Passing [code] invalidates one club; omitting it invalidates every club's event lists. The
 * code-less form is not laziness — see below.
 */
export function invalidateClubEvents(
  queryClient: QueryClient,
  code?: string,
): void {
  if (code) {
    // Partial key: the generated builder omits the params element when none is given, leaving a bare
    // path that prefix-matches all three buckets at every page offset in one call. Covering every
    // offset matters as much as covering every bucket — a new event changes the bucket's `total`,
    // which is what the pager reads to say "Showing 1–10 of 37".
    void queryClient.invalidateQueries({
      queryKey: getGetApiV1ClubsCodeCodeEventsQueryKey(code),
    });
    return;
  }
  // No code available: match on the URL shape instead. The event endpoints carry the club's `clubId`,
  // not its public code, while these queries are keyed BY public code — so an event-side mutation
  // cannot name the club whose cards it just invalidated.
  //
  // This is also the correct semantics rather than a fallback: re-filing an event under a different
  // club (#319) changes TWO clubs' lists, the one it left and the one it joined, and naming either
  // alone would leave the other stale. Invalidating marks queries stale; only mounted ones refetch.
  void queryClient.invalidateQueries({
    predicate: (query) => {
      const path = query.queryKey[0];
      return (
        typeof path === "string" &&
        /^\/api\/v1\/clubs\/code\/[^/]+\/events$/.test(path)
      );
    },
  });
}
