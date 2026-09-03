import type { ReactNode } from "react";
import { useGetApiV1PlayersCodeStanding } from "@/api/generated/users/users";
import { NtrpLabel } from "@/components/NtrpLabel";
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { formatPoints } from "@/lib/points";

/** "Male" → "Men", "Female" → "Women"; anything else (Unspecified/null) is dropped from the label. */
function groupLabel(band: string, sex: string | null | undefined): string {
  const sexLabel = sex === "Male" ? "Men" : sex === "Female" ? "Women" : null;
  return sexLabel ? `${band} ${sexLabel}` : band;
}

interface PlayerStandingCardProps {
  /** The player's shareable public code (#448). */
  code: string;
  /**
   * Render as a sub-section of an enclosing card (#589) — a bordered "Ranking" block matching the
   * Rating sub-section on the owner's Profile identity card — instead of a standalone Card. Default
   * false keeps the standalone card used on public profiles.
   */
  asSection?: boolean;
}

/**
 * A player's competitive standing headline (#448), source-aware (#457): their rank within their (NTRP
 * band, sex) group and the metric backing it under the active standings source. Under POINTS the public
 * points total is shown — e.g. "#4 · 240 pts · 4.0 Men". Under RATING the precise rating is shown only
 * when the response includes it (revealed to RATER/ADMINISTRATOR or the owner, #186) — e.g.
 * "#4 · NTRP 4.1 · 4.0 Men"; other viewers see "#4 · 4.0 Men" (rank + band only, no rating leaked).
 * A 204 (unranked: unrated / no points) yields no data, and the card shows "Unranked".
 */
export function PlayerStandingCard({ code, asSection = false }: PlayerStandingCardProps) {
  const { data, isLoading } = useGetApiV1PlayersCodeStanding(code, {
    query: { enabled: Boolean(code) },
  });

  // The source-aware metric segment: points (public) under POINTS, the rating (only when revealed) under
  // RATING. Null when RATING and the viewer can't see the rating — the card then shows rank + band only.
  function metric(): ReactNode {
    if (!data) return null;
    if (data.source === "POINTS") {
      const points = formatPoints(data.points);
      return points ? `${points} pts` : null;
    }
    return data.rating ? <NtrpLabel value={data.rating} /> : null;
  }

  const body = isLoading ? (
    <p className="text-sm text-muted-foreground">Loading…</p>
  ) : data ? (
    <p className="text-sm">
      <span className="text-lg font-semibold">#{data.rank}</span>
      <span className="text-muted-foreground">
        {" · "}
        {(() => {
          const m = metric();
          const group = groupLabel(data.band, data.sex);
          // metric() returns a node now (#842 makes the NTRP term a disclaimer trigger), so compose in
          // JSX rather than interpolating into a template string.
          return m ? (
            <>
              {m} · {group}
            </>
          ) : (
            group
          );
        })()}
      </span>
    </p>
  ) : (
    <p className="text-sm text-muted-foreground">Unranked</p>
  );

  // As a sub-section (#589): a bordered "Ranking" block mirroring the Rating sub-section it sits below.
  if (asSection) {
    return (
      <div className="space-y-2 border-t pt-3">
        <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
          Ranking
        </p>
        {body}
      </div>
    );
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Ranking</CardTitle>
      </CardHeader>
      <CardContent>{body}</CardContent>
    </Card>
  );
}
