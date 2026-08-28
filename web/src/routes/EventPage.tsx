import { Link, useLocation, useParams } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { toastError } from "@/observability/toastError";
import { Card, CardContent } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import {
  getGetApiV1EventsCodeCodeQueryKey,
  useGetApiV1EventsCodeCode,
  useGetApiV1EventsCodeCodeManage,
  usePostApiV1EventsCodeCodeSignup,
} from "@/api/generated/events/events";
import { useGetApiV1UsersMe } from "@/api/generated/users/users";
import { canManageMatches } from "@/auth/capabilities";
import { ShareCard } from "@/components/ShareCard";
import { PublicPageShell } from "@/components/PublicPageShell";
import { useAuth } from "@/auth/useAuth";
import { EventHeaderPublic } from "@/features/event/EventHeaderPublic";
import { EventClubSection } from "@/features/event/EventClubSection";
import { EventMatchSections } from "@/features/event/EventMatchSections";
import { EventParticipantList } from "@/features/event/EventParticipantList";
import { EventManagerView } from "@/features/event/EventManagerView";

/**
 * The join card (#201): request to join, or the viewer's current standing. Withheld once the event is
 * finalized or deleted (#741) — neither takes joiners, and the server rejects the request outright, so
 * offering the button would only produce an error.
 */
function JoinCard({
  code,
  viewerStatus,
  closed,
}: {
  code: string;
  viewerStatus?: string | null;
  closed: boolean;
}) {
  const queryClient = useQueryClient();
  const { user } = useAuth();
  const location = useLocation();
  const signup = usePostApiV1EventsCodeCodeSignup({
    mutation: {
      onSuccess: () => {
        queryClient.invalidateQueries({
          queryKey: getGetApiV1EventsCodeCodeQueryKey(code),
        });
      },
      onError: (error) =>
        toastError("Could not sign up for this event. Please try again.", { cause: error,
          duration: 8000,
        }),
    },
  });

  if (viewerStatus === "APPROVED") {
    return (
      <p className="text-sm text-muted-foreground">
        You’re confirmed for this event.
      </p>
    );
  }
  if (viewerStatus === "PENDING") {
    return (
      <p className="text-sm text-muted-foreground">
        Your request to join is pending the host’s approval.
      </p>
    );
  }
  if (viewerStatus === "HOLD") {
    return (
      <p className="text-sm text-muted-foreground">
        Your request is on hold — the host will review it.
      </p>
    );
  }
  if (closed) {
    return (
      <p className="text-sm text-muted-foreground">
        This event is closed to new participants.
      </p>
    );
  }
  // Joining needs an account (#193): prompt an anonymous viewer to log in / sign up first.
  if (!user) {
    return (
      <p className="text-sm text-muted-foreground">
        <Link
          to="/login"
          state={{ from: location }}
          className="font-medium text-primary hover:underline"
        >
          Log in
        </Link>
        {" or "}
        <Link to="/signup" className="font-medium text-primary hover:underline">
          sign up
        </Link>
        {" to request to join."}
      </p>
    );
  }
  return (
    <div className="space-y-2">
      <Button
        type="button"
        size="sm"
        disabled={signup.isPending}
        onClick={() => signup.mutate({ code })}
      >
        {signup.isPending ? "Requesting…" : "Request to join"}
      </Button>
    </div>
  );
}

/**
 * `/events/{code}` — the single event view (#741), reached from a shared link and from the Event
 * Organizer list alike. There is no second, in-dashboard event page: a match manager gets the
 * organizer surface here, gated by capability, and everyone else (including anonymous viewers, #193)
 * gets the read-only one. The two differ by composition, not by route.
 */
export function EventPage() {
  const { code = "" } = useParams();
  const query = useGetApiV1EventsCodeCode(code);
  const event = query.data;

  // The organizer payload is fetched by the same public code (#741) and yields the event id every
  // mutation route is keyed by. Only requested for a viewer who could act on it; a HOST who doesn't
  // own this event gets a 403 and simply falls through to the read-only view.
  const me = useGetApiV1UsersMe({ query: { retry: false } }).data;
  const canManage = canManageMatches(me?.capabilities);
  const manageQuery = useGetApiV1EventsCodeCodeManage(code, {
    query: { enabled: canManage && code !== "", retry: false },
  });
  const managedId = manageQuery.data?.id;

  return (
    <PublicPageShell columns={!managedId}>
      {query.isLoading ? (
        <p className="text-sm text-muted-foreground">Loading event…</p>
      ) : null}

      {query.isError ? (
        <p className="text-sm text-muted-foreground">
          We couldn’t find or load this event. The link may be wrong, or try
          again.
        </p>
      ) : null}

      {event && managedId ? (
        <EventManagerView eventId={managedId} />
      ) : event ? (
        <>
          <Card>
            <EventHeaderPublic event={event} />
            <CardContent className="space-y-4 text-sm">
              {/* A soft-deleted event stays reachable by link for traceability (#325) — flag it. */}
              {event.isActive === false ? (
                <p
                  role="status"
                  className="rounded-md border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm text-destructive"
                >
                  This event has been deleted. It’s kept for reference only.
                </p>
              ) : null}
                <EventClubSection clubName={event.clubName} />
              <div>
                <div className="text-xs font-medium uppercase text-muted-foreground">
                  Participants
                </div>
                <div className="mt-1">
                  <EventParticipantList participants={event.participants} />
                </div>
              </div>
              <EventMatchSections matches={event.matches} />
              <div className="border-t pt-3">
                <JoinCard
                  code={event.publicCode}
                  viewerStatus={event.viewerStatus}
                  closed={
                    event.isFinalized === true || event.isActive === false
                  }
                />
              </div>
            </CardContent>
          </Card>

          <ShareCard
            url={`${window.location.origin}/events/${event.publicCode}`}
            title="Share this event"
            description="Scan this code or copy the link to open this event."
            shareText={`${event.name} on Skopeo`}
          />
        </>
      ) : null}
    </PublicPageShell>
  );
}
