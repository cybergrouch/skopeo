import { useState, type FormEvent } from "react";
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
import { Label } from "@/components/ui/label";
import { UserSearchSelect } from "@/components/UserSearchSelect";
import {
  getGetApiV1ClubsQueryKey,
  useDeleteApiV1ClubsIdOwnersUserId,
  useGetApiV1Clubs,
  usePostApiV1Clubs,
  usePostApiV1ClubsIdOwners,
} from "@/api/generated/clubs/clubs";
import type { ClubResponse, UserSummaryResponse } from "@/api/generated/model";

/**
 * Admin club management (#313): create clubs and assign/remove CLUB_OWNER(s). ADMINISTRATOR-only
 * (this section only renders in the Admin tab, and the API enforces it). Owners are shown by name;
 * assigning an owner records the association — granting the CLUB_OWNER capability is separate.
 */
export function ClubsSection() {
  const queryClient = useQueryClient();
  const clubsQuery = useGetApiV1Clubs();
  const clubs = clubsQuery.data ?? [];
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const create = usePostApiV1Clubs();

  function invalidate() {
    void queryClient.invalidateQueries({
      queryKey: getGetApiV1ClubsQueryKey(),
    });
  }

  async function onCreate(event: FormEvent) {
    event.preventDefault();
    setError(null);
    const trimmed = name.trim();
    if (!trimmed) {
      setError("Club name is required.");
      return;
    }
    try {
      await create.mutateAsync({ data: { name: trimmed } });
      setName("");
      invalidate();
    } catch {
      setError("Could not create the club.");
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Clubs</CardTitle>
        <CardDescription>
          Create clubs and assign their owners. Events can later be assigned to
          a club.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <form onSubmit={onCreate} className="flex items-end gap-2">
          <div className="flex-1 space-y-1">
            <Label htmlFor="club-name">New club</Label>
            <Input
              id="club-name"
              value={name}
              placeholder="Club name"
              onChange={(e) => setName(e.target.value)}
            />
          </div>
          <Button type="submit" size="sm" disabled={create.isPending}>
            {create.isPending ? "Creating…" : "Create"}
          </Button>
        </form>
        {error ? (
          <p className="text-sm text-destructive" role="alert">
            {error}
          </p>
        ) : null}

        {clubsQuery.isLoading ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : clubs.length === 0 ? (
          <p className="text-sm text-muted-foreground">No clubs yet.</p>
        ) : (
          <ul className="space-y-3">
            {clubs.map((club) => (
              <ClubRow key={club.id} club={club} onChange={invalidate} />
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  );
}

function ClubRow({
  club,
  onChange,
}: {
  club: ClubResponse;
  onChange: () => void;
}) {
  const assign = usePostApiV1ClubsIdOwners();
  const remove = useDeleteApiV1ClubsIdOwnersUserId();
  const busy = assign.isPending || remove.isPending;

  async function addOwner(user: UserSummaryResponse) {
    await assign.mutateAsync({ id: club.id, data: { userId: user.id } });
    onChange();
  }

  async function removeOwner(userId: string) {
    await remove.mutateAsync({ id: club.id, userId });
    onChange();
  }

  return (
    <li className="space-y-2 rounded-lg border p-3">
      <p className="font-medium">{club.name}</p>
      {club.owners.length > 0 ? (
        <ul className="space-y-1">
          {club.owners.map((owner) => (
            <li
              key={owner.userId}
              className="flex items-center justify-between text-sm"
            >
              <span>{owner.displayName ?? owner.publicCode}</span>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                disabled={busy}
                onClick={() => removeOwner(owner.userId)}
              >
                Remove
              </Button>
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-xs text-muted-foreground">No owners yet.</p>
      )}
      <UserSearchSelect
        label="Assign an owner"
        excludeIds={club.owners.map((o) => o.userId)}
        onSelect={addOwner}
      />
    </li>
  );
}
