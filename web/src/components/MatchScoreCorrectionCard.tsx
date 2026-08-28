import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { toastError } from "@/observability/toastError";
import {
  getGetApiV1MatchesCodeCodeQueryKey,
  usePostApiV1MatchesIdScoreCorrection,
} from "@/api/generated/matches/matches";
import type {
  MatchPublicResponse,
  MatchScoreCorrectionResponse,
  SetScoreRequest,
} from "@/api/generated/model";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Input } from "@/components/ui/input";

/** A set being edited; kept as strings so a half-typed field doesn't fight the input. */
type EditableSet = { team1Games: string; team2Games: string };

/** A signed delta for display, e.g. "+0.123456" — already-negative strings keep their sign. */
function signed(value: string): string {
  return value.startsWith("-") ? value : `+${value}`;
}

function toEditableSets(match: MatchPublicResponse): EditableSet[] {
  return match.sets.map((s) => ({
    team1Games: String(s.team1Games),
    team2Games: String(s.team2Games),
  }));
}

/**
 * A field holding a whole, non-negative game count. Blank is rejected explicitly: `Number("")` is 0, so
 * without this an empty box would silently submit as "0 games".
 */
function gameCount(value: string): number | null {
  const trimmed = value.trim();
  if (trimmed === "") return null;
  const parsed = Number(trimmed);
  return Number.isInteger(parsed) && parsed >= 0 ? parsed : null;
}

/** Parse the edited sets into the request shape; null when any field isn't a whole, non-negative count. */
function toRequestSets(sets: EditableSet[]): SetScoreRequest[] | null {
  if (sets.length === 0) return null;
  const parsed = sets.map((s) => ({
    team1Games: gameCount(s.team1Games),
    team2Games: gameCount(s.team2Games),
  }));
  return parsed.every((s) => s.team1Games !== null && s.team2Games !== null)
    ? (parsed as SetScoreRequest[])
    : null;
}

/** The per-player before/after table the admin approves before anything is written. */
function PreviewTable({ preview }: { preview: MatchScoreCorrectionResponse }) {
  return (
    <div className="space-y-2 rounded-md border bg-muted/40 p-3 text-sm">
      <div className="font-medium">
        Preview: {preview.previousScore} → {preview.newScore}
      </div>
      {preview.winnerChanged ? (
        <p role="status" className="text-sm font-medium text-destructive">
          This changes who won the match.
        </p>
      ) : null}
      <ul className="space-y-1.5">
        {preview.impacts.map((impact) => (
          <li key={impact.userId} className="flex flex-col gap-0.5">
            <span className="font-medium">
              {impact.displayName ?? impact.userId}
            </span>
            <span className="font-mono text-xs text-muted-foreground">
              {impact.currentRating} − {impact.reversedChange} +{" "}
              {impact.newChange} = {impact.resultingRating} (
              {signed(impact.netAdjustment)})
            </span>
            {impact.levelChanged ? (
              <span className="text-xs">
                Band: {impact.previousLevel ?? "—"} →{" "}
                {impact.resultingLevel ?? "—"}
              </span>
            ) : null}
          </li>
        ))}
      </ul>
      <p className="text-xs text-muted-foreground">
        Later matches are not recalculated. The original rating change is
        reversed and a change recomputed from the corrected score is applied in
        its place.
      </p>
    </div>
  );
}

/**
 * ADMINISTRATOR-only score correction on the public match page (#776). Shown only for a match that has
 * already been rated — an unrated result is still edited through the Event Organizer flow.
 *
 * Deliberately two steps: previewing calls the endpoint with `dryRun: true` (which writes nothing) and
 * shows the per-player rating impact; only the explicit confirm sends `dryRun: false`. The correction
 * reverses and re-applies rating deltas and re-issues ranking points, so it must not fire on one click.
 */
export function MatchScoreCorrectionCard({
  match,
}: {
  match: MatchPublicResponse;
}) {
  // The id is revealed to ADMINISTRATOR viewers only (#776); without it there is nothing to address, so
  // there is nothing to offer either.
  if (!match.id) return null;
  return <Editor match={match} matchId={match.id} />;
}

function Editor({
  match,
  matchId,
}: {
  match: MatchPublicResponse;
  matchId: string;
}) {
  const queryClient = useQueryClient();
  const [sets, setSets] = useState<EditableSet[]>(() => toEditableSets(match));
  const [preview, setPreview] = useState<MatchScoreCorrectionResponse | null>(
    null,
  );
  const correct = usePostApiV1MatchesIdScoreCorrection();

  function updateSet(index: number, side: keyof EditableSet, value: string) {
    setSets((current) =>
      current.map((s, i) => (i === index ? { ...s, [side]: value } : s)),
    );
    // Any edit invalidates a preview taken against the previous numbers.
    setPreview(null);
  }

  async function submit(dryRun: boolean) {
    const requestSets = toRequestSets(sets);
    if (!requestSets) {
      toastError("Each set needs two whole, non-negative game counts.");
      return;
    }
    try {
      const result = await correct.mutateAsync({
        id: matchId,
        data: { sets: requestSets, dryRun },
      });
      if (dryRun) {
        setPreview(result);
        return;
      }
      setPreview(null);
      await queryClient.invalidateQueries({
        queryKey: getGetApiV1MatchesCodeCodeQueryKey(match.publicCode),
      });
      toast.success("Score corrected and ratings re-applied.");
    } catch (error) {
      toastError(
        dryRun
          ? "Could not preview the correction. Check the score and try again."
          : "Could not apply the correction. Please try again.",
        { cause: error, duration: 8000 },
      );
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Correct this score</CardTitle>
        <CardDescription>
          This match has already been rated. Correcting the score reverses the
          rating change it applied and applies one recomputed from the new
          score. Matches rated afterwards are left as they are.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3 text-sm">
        <div className="space-y-2">
          {sets.map((set, i) => (
            <div key={i} className="flex items-center gap-2">
              <span className="w-14 text-muted-foreground">Set {i + 1}</span>
              <Input
                aria-label={`Set ${i + 1} side 1 games`}
                inputMode="numeric"
                className="w-16"
                value={set.team1Games}
                disabled={correct.isPending}
                onChange={(e) => updateSet(i, "team1Games", e.target.value)}
              />
              <span aria-hidden="true">–</span>
              <Input
                aria-label={`Set ${i + 1} side 2 games`}
                inputMode="numeric"
                className="w-16"
                value={set.team2Games}
                disabled={correct.isPending}
                onChange={(e) => updateSet(i, "team2Games", e.target.value)}
              />
            </div>
          ))}
        </div>

        {preview ? <PreviewTable preview={preview} /> : null}

        <div className="flex flex-wrap items-center gap-2">
          <Button
            type="button"
            variant="outline"
            disabled={correct.isPending}
            onClick={() => submit(true)}
          >
            Preview correction
          </Button>
          {preview ? (
            <Button
              type="button"
              disabled={correct.isPending}
              onClick={() => submit(false)}
            >
              Apply correction
            </Button>
          ) : null}
          {correct.isPending ? (
            <span className="text-xs text-muted-foreground">Working…</span>
          ) : null}
        </div>
      </CardContent>
    </Card>
  );
}
