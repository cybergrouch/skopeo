import { useState } from "react";
import { Link } from "react-router-dom";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { NumberedPager } from "@/components/NumberedPager";
import { NtrpLabel } from "@/components/NtrpLabel";
import { CalibrationOverrideForm } from "@/components/CalibrationOverrideForm";
import { useGetApiV1RatingsCalibrations } from "@/api/generated/ratings/ratings";
import type { PlayerCalibrationResponse } from "@/api/generated/model";
import { calibrationLabel, overrideLabel } from "@/lib/calibration";

const PAGE_SIZE = 20;

/** "Set by Jane D. on 2026-10-09: reason", for a forced override; null for an untouched Automatic one. */
function provenance(player: PlayerCalibrationResponse): string | null {
  if (!player.overrideReason) return null;
  const by = player.overrideByName ?? player.overrideByPublicCode;
  const on = player.overrideAt?.slice(0, 10);
  return [`${overrideLabel(player.override)}`, by ? `by ${by}` : null, on ? `on ${on}` : null]
    .filter(Boolean)
    .join(" ")
    .concat(`: ${player.overrideReason}`);
}

function CalibrationRow({ player }: { player: PlayerCalibrationResponse }) {
  const why = provenance(player);
  return (
    <li className="space-y-2 rounded-lg border p-3">
      <div className="flex items-center justify-between gap-2 text-sm">
        <span className="min-w-0">
          <Link to={`/players/${player.publicCode}`} className="block font-medium hover:underline">
            {player.displayName ?? player.publicCode}{" "}
            <span className="font-normal text-muted-foreground">· {player.publicCode}</span>
          </Link>
          <span className="block text-xs text-muted-foreground">
            {calibrationLabel({
              inCalibration: player.inCalibration,
              matchesRated: player.matchesRated,
              matchesRequired: player.matchesRequired,
              override: player.override,
            })}
          </span>
          {why ? <span className="block text-xs text-muted-foreground">{why}</span> : null}
        </span>
        {player.level ? (
          <span className="shrink-0 text-xs font-medium">
            <NtrpLabel value={player.level} />
          </span>
        ) : null}
      </div>
      <CalibrationOverrideForm userId={player.userId} current={player.override} />
    </li>
  );
}

/**
 * Players in calibration (#1126): everyone whose rating is still being calibrated, newest guesses
 * (fewest rated matches) first, with each player's override and the reason behind it. Optionally also
 * those forced out, so an early exit stays visible and reversible. Who is listed is decided by the server,
 * by the same rule as everywhere else.
 */
export function CalibrationListSection() {
  const [includeForcedOff, setIncludeForcedOff] = useState(false);
  const [page, setPage] = useState(0);
  const query = useGetApiV1RatingsCalibrations({ includeForcedOff, limit: PAGE_SIZE, offset: page * PAGE_SIZE });
  const items = query.data?.items ?? [];
  const total = query.data?.total ?? 0;

  return (
    <Card>
      <CardHeader>
        <CardTitle>Players in calibration</CardTitle>
        <CardDescription>
          A rating set by hand is calibrated for its first matches: the player&apos;s own rating moves,
          their opponents&apos; do not, and they earn no ranking points. End it early, or extend it, per
          player.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={includeForcedOff}
            onChange={(event) => {
              setIncludeForcedOff(event.target.checked);
              setPage(0);
            }}
          />
          Also show players forced out of calibration
        </label>
        {query.isLoading ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : query.isError ? (
          <p className="text-sm text-destructive" role="alert">
            Could not load the players in calibration.
          </p>
        ) : items.length === 0 ? (
          <p className="text-sm text-muted-foreground">Nobody is in calibration.</p>
        ) : (
          <>
            <ul className="space-y-3">
              {items.map((player) => (
                <CalibrationRow key={player.userId} player={player} />
              ))}
            </ul>
            <NumberedPager page={page} total={total} pageSize={PAGE_SIZE} onPage={setPage} />
          </>
        )}
      </CardContent>
    </Card>
  );
}
