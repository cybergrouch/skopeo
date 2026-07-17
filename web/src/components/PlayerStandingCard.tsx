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
 * A player's competitive standing headline (#448): their rank within their (NTRP band, sex) group and
 * the points backing it — e.g. "#4 · 240 pts · 4.0 Men". Public (order + points already are, #64/#114),
 * so it renders on the anonymous public profile too. A 204 (unranked: unrated / no points) yields no
 * data, and the card shows "Unranked".
 */
export function PlayerStandingCard({ code }: PlayerStandingCardProps) {
  const { data, isLoading } = useGetApiV1PlayersCodeStanding(code, {
    query: { enabled: Boolean(code) },
  });

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
              {data.points} pts · {groupLabel(data.band, data.sex)}
            </span>
          </p>
        ) : (
          <p className="text-sm text-muted-foreground">Unranked</p>
        )}
      </CardContent>
    </Card>
  );
}
