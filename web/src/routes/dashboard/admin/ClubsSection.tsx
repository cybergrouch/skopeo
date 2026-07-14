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
  useDeleteApiV1ClubsId,
  useDeleteApiV1ClubsIdOwnersUserId,
  useGetApiV1Clubs,
  usePatchApiV1ClubsId,
  usePostApiV1Clubs,
  usePostApiV1ClubsIdOwners,
} from "@/api/generated/clubs/clubs";
import type { ClubResponse, UserSummaryResponse } from "@/api/generated/model";
import { GetApiV1UsersCapability } from "@/api/generated/model";

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
  const rename = usePatchApiV1ClubsId();
  const del = useDeleteApiV1ClubsId();
  const busy = assign.isPending || remove.isPending;

  const [editing, setEditing] = useState(false);
  const [nameDraft, setNameDraft] = useState(club.name);
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function addOwner(user: UserSummaryResponse) {
    await assign.mutateAsync({ id: club.id, data: { userId: user.id } });
    onChange();
  }

  async function removeOwner(userId: string) {
    await remove.mutateAsync({ id: club.id, userId });
    onChange();
  }

  async function saveName() {
    const trimmed = nameDraft.trim();
    if (!trimmed) {
      setError("Club name is required.");
      return;
    }
    setError(null);
    try {
      await rename.mutateAsync({ id: club.id, data: { name: trimmed } });
      setEditing(false);
      onChange();
    } catch {
      setError("Could not rename the club.");
    }
  }

  function cancelEdit() {
    setNameDraft(club.name);
    setError(null);
    setEditing(false);
  }

  async function deleteClub() {
    setError(null);
    try {
      await del.mutateAsync({ id: club.id });
      onChange();
    } catch {
      setError("Could not delete the club.");
      setConfirmingDelete(false);
    }
  }

  return (
    <li className="space-y-2 rounded-lg border p-3">
      <div className="flex items-center justify-between gap-2">
        {editing ? (
          <div className="flex flex-1 items-center gap-2">
            <Input
              aria-label="Club name"
              value={nameDraft}
              onChange={(e) => setNameDraft(e.target.value)}
            />
            <Button size="sm" disabled={rename.isPending} onClick={saveName}>
              {rename.isPending ? "Saving…" : "Save"}
            </Button>
            <Button
              type="button"
              variant="ghost"
              size="sm"
              disabled={rename.isPending}
              onClick={cancelEdit}
            >
              Cancel
            </Button>
          </div>
        ) : (
          <>
            <p className="font-medium">{club.name}</p>
            <div className="flex items-center gap-1">
              <Button
                type="button"
                variant="ghost"
                size="sm"
                onClick={() => setEditing(true)}
              >
                Edit
              </Button>
              {confirmingDelete ? (
                <>
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    className="text-destructive hover:text-destructive"
                    disabled={del.isPending}
                    onClick={deleteClub}
                  >
                    {del.isPending ? "Deleting…" : "Confirm delete"}
                  </Button>
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    disabled={del.isPending}
                    onClick={() => setConfirmingDelete(false)}
                  >
                    Cancel
                  </Button>
                </>
              ) : (
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  className="text-destructive hover:text-destructive"
                  onClick={() => setConfirmingDelete(true)}
                >
                  Delete
                </Button>
              )}
            </div>
          </>
        )}
      </div>
      {error ? (
        <p className="text-sm text-destructive" role="alert">
          {error}
        </p>
      ) : null}
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
      {/* Only surface users who hold the CLUB_OWNER capability (#317). */}
      <UserSearchSelect
        label="Assign an owner"
        excludeIds={club.owners.map((o) => o.userId)}
        filters={{ capability: GetApiV1UsersCapability.CLUB_OWNER }}
        onSelect={addOwner}
      />
    </li>
  );
}
