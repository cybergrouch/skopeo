import type { ClubResponse } from "@/api/generated/model";
import { Label } from "@/components/ui/label";

/**
 * The event's organizing club (#313/#319), rendered once for every audience (#741).
 *
 * A viewer who can't change it reads the club's name; a match manager gets the same section as a
 * select that sets, changes, or clears it. This was the last section with two implementations — a
 * read-only line on the public page and an editable one in the organizer surface — which is exactly
 * the drift this issue set out to remove.
 *
 * Since #794 every event belongs to a club, so the editable variant re-files rather than offering "none";
 * the read-only variant still renders nothing when it has no name to show.
 */
export function EventClubSection({
  clubName,
  clubId,
  clubs,
  onChange,
  disabled = false,
}: {
  clubName?: string | null;
  clubId?: string | null;
  clubs?: ClubResponse[];
  onChange?: (clubId: string) => void;
  disabled?: boolean;
}) {
  if (!onChange) {
    if (!clubName) return null;
    return (
      <div>
        <div className="text-xs font-medium uppercase text-muted-foreground">
          Club
        </div>
        <p className="mt-1">{clubName}</p>
      </div>
    );
  }

  return (
    <div className="space-y-1">
      <Label
        htmlFor="event-club-edit"
        className="text-xs font-medium uppercase text-muted-foreground"
      >
        Club
      </Label>
      <select
        id="event-club-edit"
        value={clubId ?? ""}
        disabled={disabled}
        onChange={(e) => onChange(e.target.value)}
        className="h-9 w-full rounded-md border border-input bg-transparent px-3 text-sm"
      >
        {(clubs ?? []).map((club) => (
          <option key={club.id} value={club.id}>
            {club.name}
          </option>
        ))}
      </select>
    </div>
  );
}
