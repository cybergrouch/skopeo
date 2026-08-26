import { useParams } from "react-router-dom";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import {
  useGetApiV1Clubs,
  useGetApiV1ClubsCodeCode,
} from "@/api/generated/clubs/clubs";
import { useGetApiV1UsersMe } from "@/api/generated/users/users";
import { canManageMatches } from "@/auth/capabilities";
import { canOrganizeClub } from "@/auth/clubAccess";
import { ShareCard } from "@/components/ShareCard";
import { PublicPageShell } from "@/components/PublicPageShell";
import { NewEventForm } from "@/features/event/NewEventForm";
import { ClubEventsCard } from "@/features/club/ClubEventsCard";

/** The three groupings, in the order they appear down the page (#483/#786). */
const BUCKETS = [
  {
    bucket: "UPCOMING",
    title: "Upcoming events",
    emptyLabel: "No upcoming events.",
  },
  {
    bucket: "UNFINALIZED",
    title: "Unfinalized events",
    emptyLabel: "No unfinalized events.",
  },
  {
    bucket: "FINALIZED",
    title: "Finalized events",
    emptyLabel: "No finalized events.",
  },
] as const;

/**
 * Public club page reached via `/clubs/:code` (#327), and that club's own event organizer (#780).
 *
 * Laid out as discrete cards (#786): the club's identity, the New Event form beside it, then one card per
 * event grouping — Upcoming / Unfinalized / Finalized — each fetching and paging its own ten at a time.
 * Splitting the groupings into separately-paginated queries is what makes the page cheap to open: it no
 * longer serializes every event the club has ever run just to render a header. The bucket rules themselves
 * moved into SQL for the same reason (see `EventBucket`).
 *
 * An **owner of this club** — plus any ADMINISTRATOR — additionally gets a New Event form filed under it,
 * with no club selector, since the page already answers that question. Ownership, not the bare
 * match-management capability, is the gate (#789): filing an event under a club you don't own is refused
 * server-side, so offering the form to every host would only invite a 403. Ownership is read from the
 * staff-only clubs list, which already carries each club's owners; the page stays fully renderable for
 * anonymous visitors (both lookups are best-effort, and no data simply means no form).
 */
export function ClubPage() {
  const { code = "" } = useParams();
  const query = useGetApiV1ClubsCodeCode(code);
  const club = query.data;
  const me = useGetApiV1UsersMe().data;
  // Only staff can read the clubs list, and only staff could ever own a club, so the fetch that answers
  // "do I own THIS club?" is gated on the capability rather than being the answer itself (#789).
  const clubs =
    useGetApiV1Clubs({
      query: { enabled: canManageMatches(me?.capabilities) },
    }).data ?? [];
  // A deleted club is kept for reference only (#325), so it gains no organizer affordances.
  const canOrganize =
    club?.isActive !== false &&
    canOrganizeClub({
      capabilities: me?.capabilities,
      clubs,
      meId: me?.id,
      publicCode: club?.publicCode,
    });

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
          {/* A soft-deleted club stays reachable by link for traceability (#325) — flag it. */}
          {club.isActive === false ? (
            <CardContent className="text-sm">
              <p
                role="status"
                className="rounded-md border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm text-destructive"
              >
                This club has been deleted. It’s kept for reference only.
              </p>
            </CardContent>
          ) : null}
        </Card>
      ) : null}

      {club && canOrganize ? (
        <NewEventForm
          clubPublicCode={club.publicCode}
          publicCodeToRefresh={club.publicCode}
        />
      ) : null}

      {/* One separately-paginated card per grouping (#786), ten at a time. */}
      {club
        ? BUCKETS.map((b) => (
            <ClubEventsCard
              key={b.bucket}
              code={club.publicCode}
              bucket={b.bucket}
              title={b.title}
              emptyLabel={b.emptyLabel}
            />
          ))
        : null}

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
