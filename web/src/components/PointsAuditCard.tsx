import type { ActivePointsAwardResponse, PlayerPointsBandGroup } from "@/api/generated/model";
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
 * The points audit (#448), **grouped by band** (#882).
 *
 * Points count toward a standing only while the band they were earned in matches the player's current
 * level (#403 decision 2). This card used to list every award flat under the description "your
 * currently-counting ranking points" — which was false for anyone who had changed bands, and produced a
 * profile that contradicted itself: awards shown as live while the Ranking section on the same page said
 * "Unranked".
 *
 * So the current band and the latent ones are rendered as **separate sections**, and the current band is
 * shown even holding zero, because that zero is the explanation a reader is looking for.
 *
 * `counting` comes from the server. There is no band comparison in this component — the rule lives in one
 * place on the backend, and re-deriving it here is precisely how the two surfaces drifted apart.
 *
 * Owner-or-admin only: rendered and fetched only when {@link PointsAuditCardProps.enabled}.
 */
export function PointsAuditCard({ code, enabled, collapsible = false }: PointsAuditCardProps) {
  const { data, isLoading } = useGetApiV1PlayersCodePoints(code, {
    query: { enabled: enabled && Boolean(code) },
  });
  // The hide-ranking-points flag (#865). The server returns an empty payload to a suppressed viewer, which
  // is indistinguishable from genuinely having none — so without this the card would tell a player "No
  // ranking points" when they have some and simply may not see them. That is a false statement, not a
  // cosmetic gap, and a player could act on it by asking why they were not awarded.
  //
  // Reading the flag rather than re-deriving the whole rule client-side: the capability half stays
  // server-side where it is enforced, and duplicating it here would be a second copy free to drift.
  const hideFlag = useGetApiV1SettingsHideRankingPoints({ query: { retry: false } });
  const pointsHidden = hideFlag.data?.hidden === true;

  const current = data?.current ?? null;
  const latent = data?.latent ?? [];
  const hasAnyAward = (current?.awards?.length ?? 0) > 0 || latent.length > 0;

  if (!enabled) return null;
  // Absent rather than empty while the flag is on — the #857 pattern: say nothing rather than claim zero.
  if (pointsHidden && !hasAnyAward) return null;

  return (
    <CollapsibleCard
      title="Ranking points"
      description="Where your points came from, and which of them count toward your standing."
      collapsible={collapsible}
    >
      {isLoading ? (
        <p className="text-sm text-muted-foreground">Loading…</p>
      ) : !hasAnyAward ? (
        <p className="text-sm text-muted-foreground">No active ranking points.</p>
      ) : (
        <div className="space-y-4">
          {current ? <CurrentBandSection group={current} /> : null}
          {latent.length > 0 ? <LatentSection groups={latent} /> : null}
        </div>
      )}
    </CollapsibleCard>
  );
}

/**
 * The band the player is actually racing in.
 *
 * Rendered even at zero: a player with nothing counting and points sitting in another band is exactly the
 * case this card exists to explain, and the zero is what connects "I have points" to "I am unranked".
 */
function CurrentBandSection({ group }: { group: PlayerPointsBandGroup }) {
  const awards = group.awards ?? [];
  return (
    <section>
      <header className="mb-2 flex items-baseline justify-between gap-2">
        <h3 className="text-sm font-medium">
          Current band {group.band}
          <Badge variant="default" className="ml-2 align-middle">
            counting
          </Badge>
        </h3>
        <span className="text-sm font-medium tabular-nums">
          {formatPoints(group.totalPoints) ?? group.totalPoints}
        </span>
      </header>
      {awards.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          Nothing counting in this band yet, so you are unranked here — any points below were earned in a
          different band.
        </p>
      ) : (
        <AwardList awards={awards} />
      )}
    </section>
  );
}

/**
 * Bands the player is no longer in.
 *
 * The copy must work for a player who moved **down** as well as up — when this was reported, three of the
 * four affected players had been demoted, so anything implying promotion would have been wrong for most
 * of them.
 */
function LatentSection({ groups }: { groups: PlayerPointsBandGroup[] }) {
  return (
    <section className="border-t pt-3">
      <header className="mb-1">
        <h3 className="text-sm font-medium">Earned in other bands</h3>
        <p className="text-xs text-muted-foreground">
          These do not count toward your current standing, because your band has changed since you earned
          them. They are not lost — they count again if you return to that band, until they expire.
        </p>
      </header>
      <div className="space-y-3">
        {groups.map((group) => (
          <div key={group.band}>
            <div className="mb-1 flex items-baseline justify-between gap-2">
              <span className="text-sm">
                Band {group.band}
                <Badge variant="outline" className="ml-2 align-middle">
                  not counting
                </Badge>
              </span>
              <span className="text-sm tabular-nums text-muted-foreground">
                {formatPoints(group.totalPoints) ?? group.totalPoints}
              </span>
            </div>
            <AwardList awards={group.awards ?? []} />
          </div>
        ))}
      </div>
    </section>
  );
}

/** One band's awards: amount, expiry, and a link to whatever granted it. */
function AwardList({ awards }: { awards: ActivePointsAwardResponse[] }) {
  return (
    <ul className="space-y-2">
      {awards.map((award) => (
        <li key={award.id} className="rounded-lg border p-3 text-sm">
          <div className="flex items-center justify-between gap-2">
            <span className="font-medium">{formatPoints(award.points)} pts</span>
            <Badge variant="secondary">{award.band}</Badge>
          </div>
          <div className="mt-1 text-muted-foreground">Expires {award.validUntil.slice(0, 10)}</div>
          <div className="mt-1">
            {award.matchCode ? (
              <ContentLink to={`/matches/${award.matchCode}`}>View granting match →</ContentLink>
            ) : award.eventCode ? (
              <ContentLink to={`/events/${award.eventCode}`}>View granting event →</ContentLink>
            ) : (
              <span className="text-muted-foreground">Manual grant</span>
            )}
          </div>
        </li>
      ))}
    </ul>
  );
}
