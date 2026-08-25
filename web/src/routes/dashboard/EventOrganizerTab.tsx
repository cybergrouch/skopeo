import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { useGetApiV1Clubs } from "@/api/generated/clubs/clubs";
import { ContentLink } from "@/components/ContentLink";
import { NewEventForm } from "@/features/event/NewEventForm";

/**
 * The Event Organizer tab (#138), ADMINISTRATOR-only since #794.
 *
 * It used to list every event, grouped by club — which meant one unbounded query over every event a club
 * had ever run, and it was the slowest screen in the dashboard. Each club now has its own paginated
 * organizer on its public page (#780/#786), so the list is gone entirely: this tab fetches **no events at
 * all**. What remains is what only an administrator needs — a way into any club, and a create form whose
 * club is a free choice rather than one implied by the page you happen to be on.
 *
 * Hosts and club owners deliberately do not see this tab. They organize from their own club's page, which
 * is the point of #780; showing them a cross-club index would duplicate Club Management (#786).
 */
export function EventOrganizerTab() {
  // The only query this tab makes. NewEventForm's selector reads the same list, so React Query serves
  // both from one request.
  const clubsQuery = useGetApiV1Clubs();
  const clubs = clubsQuery.data ?? [];

  return (
    <div className="grid gap-4 lg:grid-cols-2 lg:items-start">
      <NewEventForm />

      <Card>
        <CardHeader>
          <CardTitle>Clubs</CardTitle>
          <CardDescription>
            Open a club to run its events — fixtures, results, and finalizing
            all live on the club’s own page.
          </CardDescription>
        </CardHeader>
        <CardContent className="text-sm">
          {clubsQuery.isLoading ? (
            <p className="text-muted-foreground">Loading clubs…</p>
          ) : clubsQuery.isError ? (
            <p className="text-muted-foreground">
              We couldn’t load the clubs. Please try again.
            </p>
          ) : clubs.length > 0 ? (
            <ul className="space-y-2">
              {clubs.map((club) => (
                <li
                  key={club.id}
                  className="flex flex-wrap items-center justify-between gap-2 rounded-lg border p-3"
                >
                  <ContentLink to={`/clubs/${club.publicCode}`}>
                    {club.name}
                  </ContentLink>
                  <span className="font-mono text-xs text-muted-foreground">
                    {club.publicCode}
                  </span>
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-muted-foreground">
              No clubs yet. Create one in Club Management first.
            </p>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
