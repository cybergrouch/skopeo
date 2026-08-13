import { useState } from "react";
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
import { MatchHistoryRow } from "@/components/MatchHistoryRow";
import { NumberedPager } from "@/components/NumberedPager";
import { PublicPageShell } from "@/components/PublicPageShell";
import { useDebouncedValue } from "@/hooks/useDebouncedValue";
import { NTRP_LEVELS } from "@/lib/ntrp";

const PAGE_SIZE = 20;

/**
 * The full, paginated + searchable match-history page (#284), reached from the profile's "View all
 * matches" link at `/players/:code/matches`. Auth-gated like the public profile; search matches an
 * opponent/partner name or code server-side, and the pager loads one page at a time.
 */
export function PlayerMatchesPage() {
  const { code = "" } = useParams();
  const [page, setPage] = useState(0);
  const [searchInput, setSearchInput] = useState("");
  const search = useDebouncedValue(searchInput);
  const [opponentBand, setOpponentBand] = useState("");

  const query = useGetApiV1PlayersCodeMatchHistory(
    code,
    {
      limit: PAGE_SIZE,
      offset: page * PAGE_SIZE,
      search: search.trim() || undefined,
      opponentBand: opponentBand || undefined,
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
            or code. Ratings show only as the NTRP band at the time.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          <Input
            type="search"
            placeholder="Search opponent…"
            value={searchInput}
            onChange={(e) => {
              setSearchInput(e.target.value);
              setPage(0);
            }}
          />
          <select
            aria-label="Filter by opponent NTRP band"
            className="h-9 w-full rounded-md border bg-background px-2 text-sm"
            value={opponentBand}
            onChange={(e) => {
              setOpponentBand(e.target.value);
              setPage(0);
            }}
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
                page={page}
                total={total}
                pageSize={PAGE_SIZE}
                onPage={setPage}
              />
            </>
          ) : (
            <p className="text-sm text-muted-foreground">
              {search.trim() || opponentBand
                ? "No matches for that filter."
                : "No matches yet."}
            </p>
          )}
        </CardContent>
      </Card>
    </PublicPageShell>
  );
}
