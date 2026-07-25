import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  getGetApiV1SettingsPointsOpenPlayQueryKey,
  getGetApiV1SettingsPointsTournamentQueryKey,
  useGetApiV1SettingsPointsOpenPlay,
  useGetApiV1SettingsPointsTournament,
  usePutApiV1SettingsPointsOpenPlay,
  usePutApiV1SettingsPointsTournament,
} from "@/api/generated/settings/settings";
import type {
  OpenPlayPointsConfig,
  TournamentPointsConfig,
} from "@/api/generated/model";
import { OpenPlayMarginPointsRelation } from "@/api/generated/model";

/** The three band relations, in display order, with a short column label. */
const RELATIONS: { key: OpenPlayMarginPointsRelation; label: string }[] = [
  { key: OpenPlayMarginPointsRelation.EQUAL, label: "Equal" },
  { key: OpenPlayMarginPointsRelation.FAVORITE, label: "Favorite" },
  { key: OpenPlayMarginPointsRelation.UPSET, label: "Upset" },
];

const PLACES = ["1st", "2nd", "3rd", "4th"];

/** Parse an input value to an integer, tolerating a lone "-" / empty while typing (→ 0). */
function toInt(value: string): number {
  const n = Number(value);
  return Number.isFinite(n) ? Math.trunc(n) : 0;
}

/**
 * Admin-configurable points schedules (#552/#553): two editable grids — the open-play margin-bracket
 * table (band relation × game margin → winner/loser points) and the tournament placement table
 * (sanctioned/unsanctioned 1st–4th), each with a validity window. Both read the seeded default until an
 * admin saves an override; the PUTs are ADMINISTRATOR-only (the API enforces it, surfaced inline).
 * Editing these lets the standings adopt the study recommendations (validity, diverse increments)
 * without a code change. Each card fetches, then hands a fresh copy to a keyed editor so the editable
 * draft initialises from the fetched config without a set-state-in-effect.
 */
export function PointsSchedulesSection() {
  return (
    <>
      <OpenPlayPointsCard />
      <TournamentPointsCard />
    </>
  );
}

function OpenPlayPointsCard() {
  const query = useGetApiV1SettingsPointsOpenPlay({ query: { retry: false } });
  return (
    <Card>
      <CardHeader>
        <CardTitle>Open-play points</CardTitle>
        <CardDescription>
          Per-set points by band relation and game margin (winner games − loser games; the top margin
          means "or more"). Winner and loser points per cell; loser totals may be negative.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {query.isLoading || !query.data ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : (
          <OpenPlayEditor
            key={query.data.updatedAt ?? "default"}
            initial={query.data.config}
          />
        )}
      </CardContent>
    </Card>
  );
}

function OpenPlayEditor({ initial }: { initial: OpenPlayPointsConfig }) {
  const queryClient = useQueryClient();
  const [draft, setDraft] = useState<OpenPlayPointsConfig>(initial);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const save = usePutApiV1SettingsPointsOpenPlay({
    mutation: {
      onSuccess: () => {
        setSaved(true);
        void queryClient.invalidateQueries({
          queryKey: getGetApiV1SettingsPointsOpenPlayQueryKey(),
        });
      },
      onError: () => setError("Save failed. You need administrator access."),
    },
  });

  function setCell(
    relation: OpenPlayMarginPointsRelation,
    margin: number,
    field: "winnerPoints" | "loserPoints",
    value: number,
  ) {
    setSaved(false);
    setDraft((d) => ({
      ...d,
      rows: d.rows.map((r) =>
        r.relation === relation && r.margin === margin ? { ...r, [field]: value } : r,
      ),
    }));
  }

  const cell = (relation: OpenPlayMarginPointsRelation, margin: number) =>
    draft.rows.find((r) => r.relation === relation && r.margin === margin);

  return (
    <>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-xs text-muted-foreground">
              <th className="py-1 pr-2">Margin</th>
              {RELATIONS.map((rel) => (
                <th key={rel.key} className="py-1 pr-2" colSpan={2}>
                  {rel.label} (win / lose)
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {Array.from({ length: draft.maxMargin }, (_, i) => i + 1).map((margin) => (
              <tr key={margin} className="border-t">
                <td className="py-1 pr-2 tabular-nums">
                  {margin === draft.maxMargin ? `${margin}+` : margin}
                </td>
                {RELATIONS.map((rel) => (
                  <PointsCellInputs
                    key={rel.key}
                    label={`${rel.label} margin ${margin}`}
                    winner={cell(rel.key, margin)?.winnerPoints ?? 0}
                    loser={cell(rel.key, margin)?.loserPoints ?? 0}
                    onWinner={(v) => setCell(rel.key, margin, "winnerPoints", v)}
                    onLoser={(v) => setCell(rel.key, margin, "loserPoints", v)}
                  />
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <ValidityAndSave
        scope="open-play"
        validityDays={draft.validityDays}
        onValidity={(v) => {
          setSaved(false);
          setDraft((d) => ({ ...d, validityDays: v }));
        }}
        onSave={() => {
          setError(null);
          setSaved(false);
          save.mutate({ data: draft });
        }}
        pending={save.isPending}
        saved={saved}
        error={error}
      />
    </>
  );
}

/** The winner/loser number inputs for one (relation × margin) cell. */
function PointsCellInputs({
  label,
  winner,
  loser,
  onWinner,
  onLoser,
}: {
  label: string;
  winner: number;
  loser: number;
  onWinner: (v: number) => void;
  onLoser: (v: number) => void;
}) {
  return (
    <>
      <td className="py-1 pr-1">
        <Input
          type="number"
          aria-label={`${label} winner points`}
          className="w-16"
          value={winner}
          onChange={(e) => onWinner(toInt(e.target.value))}
        />
      </td>
      <td className="py-1 pr-2">
        <Input
          type="number"
          aria-label={`${label} loser points`}
          className="w-16"
          value={loser}
          onChange={(e) => onLoser(toInt(e.target.value))}
        />
      </td>
    </>
  );
}

function TournamentPointsCard() {
  const query = useGetApiV1SettingsPointsTournament({ query: { retry: false } });
  return (
    <Card>
      <CardHeader>
        <CardTitle>Tournament placement points</CardTitle>
        <CardDescription>
          Points for 1st–4th place, separately for sanctioned and unsanctioned tournaments.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {query.isLoading || !query.data ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : (
          <TournamentEditor
            key={query.data.updatedAt ?? "default"}
            initial={query.data.config}
          />
        )}
      </CardContent>
    </Card>
  );
}

function TournamentEditor({ initial }: { initial: TournamentPointsConfig }) {
  const queryClient = useQueryClient();
  const [draft, setDraft] = useState<TournamentPointsConfig>(initial);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const save = usePutApiV1SettingsPointsTournament({
    mutation: {
      onSuccess: () => {
        setSaved(true);
        void queryClient.invalidateQueries({
          queryKey: getGetApiV1SettingsPointsTournamentQueryKey(),
        });
      },
      onError: () => setError("Save failed. You need administrator access."),
    },
  });

  function setPlace(schedule: "sanctioned" | "unsanctioned", index: number, value: number) {
    setSaved(false);
    setDraft((d) => ({
      ...d,
      [schedule]: d[schedule].map((p, i) => (i === index ? value : p)),
    }));
  }

  return (
    <>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-xs text-muted-foreground">
              <th className="py-1 pr-2">Schedule</th>
              {PLACES.map((p) => (
                <th key={p} className="py-1 pr-2">
                  {p}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {(["sanctioned", "unsanctioned"] as const).map((schedule) => (
              <tr key={schedule} className="border-t">
                <td className="py-1 pr-2 capitalize">{schedule}</td>
                {draft[schedule].map((points, index) => (
                  <td key={index} className="py-1 pr-2">
                    <Input
                      type="number"
                      aria-label={`${schedule} ${PLACES[index]} points`}
                      className="w-20"
                      value={points}
                      onChange={(e) => setPlace(schedule, index, toInt(e.target.value))}
                    />
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <ValidityAndSave
        scope="tournament"
        validityDays={draft.validityDays}
        onValidity={(v) => {
          setSaved(false);
          setDraft((d) => ({ ...d, validityDays: v }));
        }}
        onSave={() => {
          setError(null);
          setSaved(false);
          save.mutate({ data: draft });
        }}
        pending={save.isPending}
        saved={saved}
        error={error}
      />
    </>
  );
}

/** Shared validity-days input + Save button with inline saved/error feedback. */
function ValidityAndSave({
  scope,
  validityDays,
  onValidity,
  onSave,
  pending,
  saved,
  error,
}: {
  scope: string;
  validityDays: number;
  onValidity: (v: number) => void;
  onSave: () => void;
  pending: boolean;
  saved: boolean;
  error: string | null;
}) {
  const id = `${scope}-validity-days`;
  return (
    <div className="flex flex-wrap items-center gap-2">
      <label className="text-sm text-muted-foreground" htmlFor={id}>
        Validity (days)
      </label>
      <Input
        id={id}
        type="number"
        aria-label={`${scope} validity days`}
        className="w-24"
        value={validityDays}
        onChange={(e) => onValidity(toInt(e.target.value))}
      />
      <Button size="sm" onClick={onSave} disabled={pending}>
        {pending ? "Saving…" : "Save"}
      </Button>
      {saved ? (
        <span className="text-xs text-muted-foreground" role="status">
          Saved
        </span>
      ) : null}
      {error ? (
        <span className="text-xs text-destructive" role="alert">
          {error}
        </span>
      ) : null}
    </div>
  );
}
