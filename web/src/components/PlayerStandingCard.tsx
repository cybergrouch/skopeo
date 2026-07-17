import { useGetApiV1PlayersCodeStanding } from "@/api/generated/users/users";
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";

/** "Male" → "Men", "Female" → "Women"; anything else (Unspecified/null) is dropped from the label. */
function groupLabel(band: string, sex: string | null | undefined): string {
  const sexLabel = sex === "Male" ? "Men" : sex === "Female" ? "Women" : null;
  return sexLabel ? `${band} ${sexLabel}` : band;
}

interface PlayerStandingCardProps {
  /** The player's shareable public code (#448). */
  code: string;
}

/**
 * A player's competitive standing headline (#448), source-aware (#457): their rank within their (NTRP
 * band, sex) group and the metric backing it under the active standings source. Under POINTS the public
 * points total is shown — e.g. "#4 · 240 pts · 4.0 Men". Under RATING the precise rating is shown only
 * when the response includes it (revealed to RATER/ADMINISTRATOR or the owner, #186) — e.g.
 * "#4 · NTRP 4.1 · 4.0 Men"; other viewers see "#4 · 4.0 Men" (rank + band only, no rating leaked).
 * A 204 (unranked: unrated / no points) yields no data, and the card shows "Unranked".
 */
export function PlayerStandingCard({ code }: PlayerStandingCardProps) {
  const { data, isLoading } = useGetApiV1PlayersCodeStanding(code, {
    query: { enabled: Boolean(code) },
  });

  // The source-aware metric segment: points (public) under POINTS, the rating (only when revealed) under
  // RATING. Null when RATING and the viewer can't see the rating — the card then shows rank + band only.
  function metric(): string | null {
    if (!data) return null;
    if (data.source === "POINTS") {
      return data.points ? `${data.points} pts` : null;
    }
    return data.rating ? `NTRP ${data.rating}` : null;
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Ranking</CardTitle>
      </CardHeader>
      <CardContent>
        {isLoading ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : data ? (
          <p className="text-sm">
            <span className="text-lg font-semibold">#{data.rank}</span>
            <span className="text-muted-foreground">
              {" · "}
              {(() => {
                const m = metric();
                return m ? `${m} · ${groupLabel(data.band, data.sex)}` : groupLabel(data.band, data.sex);
              })()}
            </span>
          </p>
        ) : (
          <p className="text-sm text-muted-foreground">Unranked</p>
        )}
      </CardContent>
    </Card>
  );
}
