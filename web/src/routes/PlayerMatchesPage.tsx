import { useEffect, useState } from "react";
import { NtrpLabel } from "@/components/NtrpLabel";
import { useParams } from "react-router-dom";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { useGetApiV1PlayersCodeMatchHistory } from "@/api/generated/users/users";
import { IgnoredParamsNotice } from "@/components/IgnoredParamsNotice";
import { MatchHistoryRow } from "@/components/MatchHistoryRow";
import { NumberedPager } from "@/components/NumberedPager";
import { PublicPageShell } from "@/components/PublicPageShell";
import { useDebouncedValue } from "@/hooks/useDebouncedValue";
import {
  choiceField,
  pageField,
  textField,
  useUrlViewState,
} from "@/hooks/useUrlViewState";
import { NTRP_LEVELS } from "@/lib/ntrp";

const PAGE_SIZE = 20;

/**
 * The full, paginated + searchable match-history page (#284), reached from the profile's "View all
 * matches" link at `/players/:code/matches`. Auth-gated like the public profile; search matches an
 * opponent/partner name or code server-side, and the pager loads one page at a time.
 *
 * Page, search term and band filter all live in the URL (#1056), un-namespaced: this page owns its
 * view state alone, so its params read plainly — `?page=2&search=ben&band=4.0`. That makes "this
 * player's matches vs 4.0 opponents" a link you can send, and makes a reload keep your place.
 */
export function PlayerMatchesPage() {
  const { code = "" } = useParams();
  const { view, setView, ignored } = useUrlViewState({
    fields: { page: pageField(), search: textField(), band: choiceField(NTRP_LEVELS) },
  });
  // The raw input stays local; only the SETTLED value reaches the URL (#1056), because syncing each
  // keystroke would rewrite the URL per character. Seeded once from the URL rather than mirrored back
  // — a mount is a reload or a pasted link, which is exactly when there is nothing to overwrite.
  const [searchInput, setSearchInput] = useState(view.search);
  const settled = useDebouncedValue(searchInput);
  // Deliberately un-memoized and guarded instead: it runs after every render and writes only once the
  // typing has settled somewhere new. A new search also returns to the first page, since page 3 of the
  // old results holds different matches than page 3 of the new ones.
  useEffect(() => {
    if (settled.trim() === view.search) return;
    setView({ search: settled, page: 0 });
  });

  const query = useGetApiV1PlayersCodeMatchHistory(
    code,
    {
      limit: PAGE_SIZE,
      offset: view.page * PAGE_SIZE,
      search: view.search || undefined,
      opponentBand: view.band || undefined,
    },
    { query: { enabled: Boolean(code) } },
  );
  const items = query.data?.items ?? [];
  const total = query.data?.total ?? 0;

  return (
    <PublicPageShell columns={false}>
      <Card>
        <CardHeader>
          <CardTitle>Match history</CardTitle>
          <CardDescription>
            Every match, newest first. Search by an opponent or partner's name
            or code. Ratings show only as the <NtrpLabel /> band at the time.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          <IgnoredParamsNotice ignored={ignored} />
          <Input
            type="search"
            placeholder="Search opponent…"
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
          />
          <select
            aria-label="Filter by opponent NTRP band"
            className="h-9 w-full rounded-md border bg-background px-2 text-sm"
            value={view.band}
            onChange={(e) => setView({ band: e.target.value, page: 0 })}
          >
            <option value="">All opponent bands</option>
            {NTRP_LEVELS.map((level) => (
              <option key={level} value={level}>
                NTRP {level}
              </option>
            ))}
          </select>
          {query.isLoading ? (
            <p className="text-sm text-muted-foreground">Loading…</p>
          ) : query.data?.hidden ? (
            // #622: the owner hid their match history and this viewer is not privileged.
            <p className="text-sm text-muted-foreground">
              This player has hidden their match history.
            </p>
          ) : items.length > 0 ? (
            <>
              <ul className="space-y-2">
                {items.map((match) => (
                  <MatchHistoryRow key={match.matchId} match={match} />
                ))}
              </ul>
              <NumberedPager
                page={view.page}
                total={total}
                pageSize={PAGE_SIZE}
                onPage={(next) => setView({ page: next })}
              />
            </>
          ) : (
            <p className="text-sm text-muted-foreground">
              {view.search || view.band
                ? "No matches for that filter."
                : "No matches yet."}
            </p>
          )}
        </CardContent>
      </Card>
    </PublicPageShell>
  );
}
