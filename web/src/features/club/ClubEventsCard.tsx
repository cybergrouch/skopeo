import { useState } from "react";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { useGetApiV1ClubsCodeCodeEvents } from "@/api/generated/clubs/clubs";
import type {
  ClubPublicEventResponse,
  GetApiV1ClubsCodeCodeEventsBucket,
} from "@/api/generated/model";
import { ContentLink } from "@/components/ContentLink";
import { NumberedPager } from "@/components/NumberedPager";

/** Ten at a time (#786) — matches the endpoint's own default. */
const PAGE_SIZE = 10;

/** One event, linking to its own public page, with the date that matters for its grouping (#296). */
function EventRow({
  event,
  upcoming,
}: {
  event: ClubPublicEventResponse;
  upcoming: boolean;
}) {
  const date = upcoming
    ? `Starts ${event.startDate}`
    : `Ended ${event.endDate}`;
  return (
    <li className="rounded-lg border p-3 text-sm">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <ContentLink to={`/events/${event.publicCode}`}>
          {event.name}
        </ContentLink>
        <span className="rounded-full bg-muted px-2 py-0.5 text-[10px] font-medium uppercase text-muted-foreground">
          {event.eventType}
        </span>
      </div>
      <div className="mt-1 text-xs text-muted-foreground">{date}</div>
    </li>
  );
}

/**
 * One of a club's three event groupings as its own paginated card (#786).
 *
 * Each card owns its own page state and asks the server for just that page —
 * `?bucket=…&limit=10&offset=…` — so opening a club page costs three small queries instead of a scan of
 * every event it has ever run. The bucket rules themselves live in SQL (see `EventBucket`), which is what
 * makes per-bucket paging possible at all.
 *
 * `total` is the size of the whole bucket rather than the page, so the pager can say "Showing 1–10 of 37".
 */
export function ClubEventsCard({
  code,
  bucket,
  title,
  emptyLabel,
}: {
  code: string;
  bucket: GetApiV1ClubsCodeCodeEventsBucket;
  title: string;
  emptyLabel: string;
}) {
  const [page, setPage] = useState(0);
  const query = useGetApiV1ClubsCodeCodeEvents(code, {
    bucket,
    limit: PAGE_SIZE,
    offset: page * PAGE_SIZE,
  });
  const items = query.data?.items ?? [];
  const total = query.data?.total ?? 0;
  const upcoming = bucket === "UPCOMING";

  return (
    <Card>
      <CardHeader>
        <CardTitle>{title}</CardTitle>
        {total > 0 ? (
          <CardDescription>
            {total} event{total === 1 ? "" : "s"}
          </CardDescription>
        ) : null}
      </CardHeader>
      <CardContent className="text-sm">
        {query.isLoading ? (
          <p className="text-muted-foreground">Loading…</p>
        ) : query.isError ? (
          <p className="text-muted-foreground">
            We couldn’t load these events. Please try again.
          </p>
        ) : items.length > 0 ? (
          <>
            <ul className="space-y-2">
              {items.map((event) => (
                <EventRow
                  key={event.publicCode}
                  event={event}
                  upcoming={upcoming}
                />
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
          <p className="text-muted-foreground">{emptyLabel}</p>
        )}
      </CardContent>
    </Card>
  );
}
