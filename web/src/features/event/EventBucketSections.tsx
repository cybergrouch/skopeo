import type { ReactNode } from "react";
import { splitByBucket, type BucketableEvent } from "./eventBuckets";

/**
 * A labelled subsection with its own empty state (#271), shared by every surface that lists events (#780).
 *
 * The **row** is supplied by the caller rather than baked in, because the two consumers need genuinely
 * different affordances: the Event Organizer's row is a button that opens the event's working page, while a
 * club's public page links to the event's public page. Sharing the shell and the bucket rules — the parts
 * that were drifting — without forcing one row to do both jobs.
 */
export function EventSection<T>({
  title,
  events,
  emptyLabel,
  renderRow,
}: {
  title: string;
  events: T[];
  emptyLabel: string;
  renderRow: (event: T) => ReactNode;
}) {
  return (
    <div>
      <div className="text-xs font-medium uppercase text-muted-foreground">
        {title}
      </div>
      {events.length > 0 ? (
        <ul className="mt-1 space-y-2">{events.map((event) => renderRow(event))}</ul>
      ) : (
        <p className="mt-1 text-sm text-muted-foreground">{emptyLabel}</p>
      )}
    </div>
  );
}

/**
 * The three event subsections in order — Upcoming, Unfinalized, Finalized (#483) — with their empty
 * states. `renderRow` receives each event plus whether it sits in the Upcoming bucket, since upcoming rows
 * show a start date and the rest an end date (#296).
 */
export function EventBuckets<T extends BucketableEvent>({
  events,
  today,
  renderRow,
}: {
  events: T[];
  today: string;
  renderRow: (event: T, opts: { upcoming: boolean }) => ReactNode;
}) {
  const { upcoming, unfinalized, finalized } = splitByBucket(events, today);
  return (
    <>
      <EventSection
        title="Upcoming"
        events={upcoming}
        emptyLabel="No upcoming events."
        renderRow={(event) => renderRow(event, { upcoming: true })}
      />
      <EventSection
        title="Unfinalized"
        events={unfinalized}
        emptyLabel="No unfinalized events."
        renderRow={(event) => renderRow(event, { upcoming: false })}
      />
      <EventSection
        title="Finalized"
        events={finalized}
        emptyLabel="No finalized events."
        renderRow={(event) => renderRow(event, { upcoming: false })}
      />
    </>
  );
}
