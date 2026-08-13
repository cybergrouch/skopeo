import type { EventParticipantResponse } from "@/api/generated/model";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { PlaceholderTag } from "@/components/PlaceholderTag";
import { playerLabel } from "@/lib/playerLabel";
import { participantMeta } from "./eventFacets";

/**
 * Players who signed up from the shared link and are waiting on a host's decision (#201).
 *
 * Renders nothing when there are no requests — an empty "Join requests" card would be noise on a
 * roster the host filled themselves. Hold is offered only for a PENDING request: a held one has
 * already been set aside, so the remaining move is to approve it.
 */
export function EventJoinRequestsSection({
  requests,
  disabled,
  onDecide,
}: {
  requests: EventParticipantResponse[];
  disabled: boolean;
  onDecide: (userId: string, status: "APPROVED" | "HOLD") => void;
}) {
  if (requests.length === 0) return null;

  return (
    <Card>
      <CardHeader>
        <CardTitle>Join requests</CardTitle>
        <CardDescription>
          Players who signed up from the shared link. Approve to add them to the
          roster, or hold to set aside (you can approve a held request later).
        </CardDescription>
      </CardHeader>
      <CardContent>
        <ul className="space-y-1 text-sm">
          {requests.map((p) => {
            const meta = participantMeta(p);
            return (
              <li
                key={p.userId}
                className="flex items-center justify-between gap-2"
              >
                <span className="min-w-0">
                  <span className="block">
                    {playerLabel(p.displayName, p.publicCode, p.userId)}
                    <PlaceholderTag
                      show={p.isPlaceholder}
                      deleted={p.isDeleted}
                    />
                    {p.status === "HOLD" ? (
                      <span className="text-muted-foreground"> · on hold</span>
                    ) : null}
                  </span>
                  {meta ? (
                    <span className="block text-xs text-muted-foreground">
                      {meta}
                    </span>
                  ) : null}
                </span>
                <span className="flex shrink-0 items-center gap-2">
                  <Button
                    type="button"
                    size="sm"
                    disabled={disabled}
                    onClick={() => onDecide(p.userId, "APPROVED")}
                  >
                    Approve
                  </Button>
                  {p.status === "HOLD" ? null : (
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      disabled={disabled}
                      onClick={() => onDecide(p.userId, "HOLD")}
                    >
                      Hold
                    </Button>
                  )}
                </span>
              </li>
            );
          })}
        </ul>
      </CardContent>
    </Card>
  );
}
