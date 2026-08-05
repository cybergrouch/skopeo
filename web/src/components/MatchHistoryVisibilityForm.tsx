import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import {
  getGetApiV1UsersIdQueryKey,
  getGetApiV1UsersMeQueryKey,
  useGetApiV1UsersId,
  usePutApiV1UsersIdMatchHistoryVisibility,
} from "@/api/generated/users/users";

/**
 * Per-player privacy control (#622): hide your match history from other players on your public
 * profile. Elevated roles and you (the owner) still see it. Saves on toggle via
 * PUT /users/{id}/match-history-visibility, then invalidates the profile queries.
 */
function Controls({
  userId,
  initialHidden,
}: {
  userId: string;
  initialHidden: boolean;
}) {
  const queryClient = useQueryClient();
  const [hidden, setHidden] = useState(initialHidden);
  const save = usePutApiV1UsersIdMatchHistoryVisibility();

  async function onToggle(next: boolean) {
    setHidden(next);
    try {
      await save.mutateAsync({ id: userId, data: { hidden: next } });
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: getGetApiV1UsersIdQueryKey(userId),
        }),
        queryClient.invalidateQueries({
          queryKey: getGetApiV1UsersMeQueryKey(),
        }),
      ]);
      toast.success("Saved");
    } catch {
      setHidden(!next);
      toast.error("Could not save. Please try again.", { duration: 8000 });
    }
  }

  return (
    <div className="space-y-2">
      <label className="flex items-center gap-2 text-sm">
        <input
          type="checkbox"
          checked={hidden}
          disabled={save.isPending}
          onChange={(e) => onToggle(e.target.checked)}
          className="h-4 w-4"
        />
        Hide my match history from other players
      </label>
      <div className="flex items-center gap-2">
        {save.isPending ? (
          <span className="text-xs text-muted-foreground">Saving…</span>
        ) : null}
      </div>
    </div>
  );
}

/** Loads the user, then renders the toggle prefilled from the current flag. */
export function MatchHistoryVisibilityForm({ userId }: { userId: string }) {
  const userQuery = useGetApiV1UsersId(userId, {
    query: { enabled: Boolean(userId) },
  });
  if (userQuery.isLoading || !userQuery.data) {
    return <p className="text-sm text-muted-foreground">Loading…</p>;
  }
  return (
    <Controls
      key={userId}
      userId={userId}
      initialHidden={userQuery.data.matchHistoryHidden ?? false}
    />
  );
}
