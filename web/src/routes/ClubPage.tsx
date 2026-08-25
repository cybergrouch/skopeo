import { useParams } from "react-router-dom";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { useGetApiV1ClubsCodeCode } from "@/api/generated/clubs/clubs";
import { useGetApiV1UsersMe } from "@/api/generated/users/users";
import type { ClubPublicEventResponse } from "@/api/generated/model";
import { canManageMatches } from "@/auth/capabilities";
import { ShareCard } from "@/components/ShareCard";
import { PublicPageShell } from "@/components/PublicPageShell";
import { ContentLink } from "@/components/ContentLink";
import { EventBuckets } from "@/features/event/EventBucketSections";
import { todayIso } from "@/features/event/eventBuckets";
import { NewEventForm } from "@/features/event/NewEventForm";

/** One event, linking to its own public page, with the date that matters for its grouping (#296). */
function EventRow({
  event,
  upcoming,
}: {
  event: ClubPublicEventResponse;
  upcoming: boolean;
}) {
  const date = upcoming ? `Starts ${event.startDate}` : `Ended ${event.endDate}`;
  return (
    <li key={event.publicCode} className="rounded-lg border p-3 text-sm">
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
 * Public club page reached via `/clubs/:code` (#327), and that club's own event organizer (#780).
 *
 * Its events are grouped Upcoming / Unfinalized / Finalized by the same `EventBuckets` the Event Organizer
 * tab uses, so the two surfaces cannot drift — the club page used to carry its own Upcoming/Past listing,
 * which is exactly the duplication #780 set out to remove.
 *
 * A match manager (HOST / CLUB_OWNER / ADMINISTRATOR) additionally gets a New Event form filed under this
 * club, with no club selector — the page already answers that question. The page stays fully renderable for
 * anonymous visitors: the viewer lookup is best-effort, no capabilities simply means no form, and the
 * server enforces the rule regardless.
 */
export function ClubPage() {
  const { code = "" } = useParams();
  const query = useGetApiV1ClubsCodeCode(code);
  const club = query.data;
  const me = useGetApiV1UsersMe().data;
  // A deleted club is kept for reference only (#325), so it gains no organizer affordances.
  const canOrganize =
    canManageMatches(me?.capabilities) && club?.isActive !== false;

  return (
    <PublicPageShell>
      {query.isLoading ? (
        <p className="text-sm text-muted-foreground">Loading club…</p>
      ) : null}

      {query.isError ? (
        <p className="text-sm text-muted-foreground">
          We couldn’t find or load this club. The link may be wrong, or try
          again.
        </p>
      ) : null}

      {club ? (
        <Card>
          <CardHeader>
            <CardTitle>{club.name}</CardTitle>
            <CardDescription>
              Club ID:{" "}
              <code className="select-all font-mono font-medium text-foreground">
                {club.publicCode}
              </code>
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4 text-sm">
            {/* A soft-deleted club stays reachable by link for traceability (#325) — flag it. */}
            {club.isActive === false ? (
              <p
                role="status"
                className="rounded-md border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm text-destructive"
              >
                This club has been deleted. It’s kept for reference only.
              </p>
            ) : null}
            <EventBuckets
              events={club.events}
              today={todayIso()}
              renderRow={(event, { upcoming }) => (
                <EventRow
                  key={event.publicCode}
                  event={event}
                  upcoming={upcoming}
                />
              )}
            />
          </CardContent>
        </Card>
      ) : null}

      {club && canOrganize ? (
        <NewEventForm
          fixedClubPublicCode={club.publicCode}
          publicCodeToRefresh={club.publicCode}
        />
      ) : null}

      {club ? (
        <ShareCard
          url={`${window.location.origin}/clubs/${club.publicCode}`}
          title="Share this club"
          description="Scan this code or copy the link to open this club."
          shareText={`${club.name} on Skopeo`}
        />
      ) : null}
    </PublicPageShell>
  );
}
