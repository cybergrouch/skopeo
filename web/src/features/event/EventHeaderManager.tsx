import { useState } from "react";
import type { EventResponse } from "@/api/generated/model";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { eventTypeLabel } from "./eventFacets";

/**
 * The event header for a match manager (#741) — the sibling of `EventHeaderPublic`.
 *
 * Two components rather than one threaded with `canManage &&` conditionals: this is where the two
 * audiences diverge most, and keeping them apart means the public variant can be shown to carry no
 * manager affordance by reading it, not by tracing conditionals.
 *
 * Rename is edited here and saved by the caller: the draft, the validation message, and the
 * open/closed state are this component's business, while the mutation stays with the surface that
 * owns the event's queries. [onRename] reports whether the save landed — a rejected rename leaves the
 * editor open with the caller's toast beside it, rather than closing as if it had worked.
 */
export function EventHeaderManager({
  event,
  finalized,
  onRename,
  renaming: saving = false,
}: {
  event: EventResponse;
  finalized: boolean;
  onRename: (name: string) => Promise<boolean>;
  renaming?: boolean;
}) {
  const [editing, setEditing] = useState(false);
  const [nameDraft, setNameDraft] = useState("");
  const [error, setError] = useState<string | null>(null);

  async function save() {
    const name = nameDraft.trim();
    if (name === "") {
      setError("Event name is required.");
      return;
    }
    setError(null);
    if (await onRename(name)) setEditing(false);
  }

  return (
    <CardHeader>
      {editing ? (
        <div className="space-y-2">
          <Label htmlFor="event-name" className="text-xs">
            Event name
          </Label>
          <div className="flex flex-wrap items-center gap-2">
            <Input
              id="event-name"
              value={nameDraft}
              onChange={(e) => setNameDraft(e.target.value)}
              className="max-w-xs"
            />
            <Button type="button" size="sm" disabled={saving} onClick={save}>
              {saving ? "Saving…" : "Save"}
            </Button>
            <Button
              type="button"
              variant="ghost"
              size="sm"
              disabled={saving}
              onClick={() => setEditing(false)}
            >
              Cancel
            </Button>
          </div>
          {error ? (
            <p className="text-sm text-destructive" role="alert">
              {error}
            </p>
          ) : null}
        </div>
      ) : (
        <div className="flex items-center justify-between gap-2">
          <span className="flex items-center gap-2">
            <CardTitle>{event.name}</CardTitle>
            {finalized ? (
              <span
                className="rounded-full border border-emerald-500/50 bg-emerald-500/10 px-2 py-0.5 text-xs font-medium text-emerald-700 dark:text-emerald-400"
                data-testid="finalized-badge"
              >
                Finalized
              </span>
            ) : null}
          </span>
          {finalized ? null : (
            <Button
              type="button"
              variant="ghost"
              size="sm"
              onClick={() => {
                setError(null);
                setNameDraft(event.name);
                setEditing(true);
              }}
            >
              Rename
            </Button>
          )}
        </div>
      )}
      <CardDescription>
        {eventTypeLabel(event.type)}
        {" · "}
        {event.startDate} – {event.endDate} · Event ID:{" "}
        <code className="font-mono font-medium text-foreground">
          {event.publicCode}
        </code>
      </CardDescription>
    </CardHeader>
  );
}
