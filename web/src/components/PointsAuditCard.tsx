import { useGetApiV1PlayersCodePoints } from "@/api/generated/users/users";
import { ContentLink } from "@/components/ContentLink";
import { CollapsibleCard } from "@/components/CollapsibleCard";
import { Badge } from "@/components/ui/badge";
import { formatPoints } from "@/lib/points";
import { useGetApiV1SettingsHideRankingPoints } from "@/api/generated/settings/settings";

interface PointsAuditCardProps {
  /** The player's shareable public code (#448). */
  code: string;
  /**
   * Whether the viewer may see the audit — the profile owner (viewing self) or an ADMINISTRATOR.
   * Gates the fetch too, so a non-owner never even requests the (403) endpoint.
   */
  enabled: boolean;
  /** Start minimized behind a "Show all" toggle (#589) — used on the owner's Profile tab. */
  collapsible?: boolean;
}

/**
 * The active-points audit (#448) — the player's ACTIVE ranking-point awards, each with its points,
 * band, expiry (validUntil), and a link to the granting match (or the event, for awards with no match
 * link). Owner-or-admin only: rendered and fetched only when {@link PointsAuditCardProps.enabled}.
 */
export function PointsAuditCard({ code, enabled, collapsible = false }: PointsAuditCardProps) {
  const { data, isLoading } = useGetApiV1PlayersCodePoints(code, {
    query: { enabled: enabled && Boolean(code) },
  });
  // The hide-ranking-points flag (#865). The server returns an empty list to a suppressed viewer, which is
  // indistinguishable from genuinely having none — so without this the card would tell a player "No active
  // ranking points" when they have some and simply may not see them. That is a false statement, not a
  // cosmetic gap, and a player could act on it by asking why they were not awarded.
  //
  // Reading the flag rather than re-deriving the whole rule client-side: the capability half stays
  // server-side where it is enforced, and duplicating it here would be a second copy free to drift.
  const hideFlag = useGetApiV1SettingsHideRankingPoints({ query: { retry: false } });
  const pointsHidden = hideFlag.data?.hidden === true;

  if (!enabled) return null;
  // Absent rather than empty while the flag is on — the #857 pattern: say nothing rather than claim zero.
  if (pointsHidden && (data?.length ?? 0) === 0) return null;

  const awards = data ?? [];

  return (
    <CollapsibleCard
      title="Active points"
      description="Your currently-counting ranking points and where they came from."
      collapsible={collapsible}
    >
      {isLoading ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : awards.length > 0 ? (
          <ul className="space-y-2">
            {awards.map((award) => (
              <li key={award.id} className="rounded-lg border p-3 text-sm">
                <div className="flex items-center justify-between gap-2">
                  <span className="font-medium">{formatPoints(award.points)} pts</span>
                  <Badge variant="secondary">{award.band}</Badge>
                </div>
                <div className="mt-1 text-muted-foreground">
                  Expires {award.validUntil.slice(0, 10)}
                </div>
                <div className="mt-1">
                  {award.matchCode ? (
                    <ContentLink to={`/matches/${award.matchCode}`}>
                      View granting match →
                    </ContentLink>
                  ) : award.eventCode ? (
                    <ContentLink to={`/events/${award.eventCode}`}>
                      View granting event →
                    </ContentLink>
                  ) : (
                    <span className="text-muted-foreground">
                      Manual grant
                    </span>
                  )}
                </div>
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-sm text-muted-foreground">
            No active ranking points.
          </p>
        )}
    </CollapsibleCard>
  );
}
