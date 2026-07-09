import { useState, type FormEvent } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  getGetApiV1UsersIdQueryKey,
  getGetApiV1UsersMeQueryKey,
  useGetApiV1UsersId,
  usePutApiV1UsersIdPhoto,
} from "@/api/generated/users/users";

/**
 * Profile-photo controls (#303): hide the photo, or supply a custom public image URL shown instead
 * of the Google/Facebook one. Neither choice is overwritten by the login-time OAuth photo sync.
 * Authorized self-or-ADMINISTRATOR, so this serves both the owner's Profile tab and admin player
 * management. Persists via PUT /users/{id}/photo, then invalidates the profile queries so the
 * displayed avatar updates.
 */
function Controls({
  userId,
  initialCustomUrl,
  initialHidden,
}: {
  userId: string;
  initialCustomUrl: string;
  initialHidden: boolean;
}) {
  const queryClient = useQueryClient();
  const [customUrl, setCustomUrl] = useState(initialCustomUrl);
  const [hidden, setHidden] = useState(initialHidden);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const save = usePutApiV1UsersIdPhoto();

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setSaved(false);
    setError(null);
    const trimmed = customUrl.trim();
    if (trimmed && !/^https?:\/\//i.test(trimmed)) {
      setError("Enter an http(s) image URL, or leave it blank.");
      return;
    }
    try {
      await save.mutateAsync({
        id: userId,
        data: { customPhotoUrl: trimmed || null, hidden },
      });
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: getGetApiV1UsersIdQueryKey(userId),
        }),
        queryClient.invalidateQueries({
          queryKey: getGetApiV1UsersMeQueryKey(),
        }),
      ]);
      setSaved(true);
    } catch {
      setError(
        "Could not save your photo settings. Check the URL and try again.",
      );
    }
  }

  return (
    <form onSubmit={onSubmit} className="space-y-3">
      <label className="flex items-center gap-2 text-sm">
        <input
          type="checkbox"
          checked={hidden}
          onChange={(e) => {
            setHidden(e.target.checked);
            setSaved(false);
          }}
          className="h-4 w-4"
        />
        Hide my photo
      </label>
      <div className="space-y-1">
        <Label htmlFor="pf-photo-url">Custom photo URL</Label>
        <Input
          id="pf-photo-url"
          type="url"
          placeholder="https://example.com/me.jpg"
          value={customUrl}
          onChange={(e) => {
            setCustomUrl(e.target.value);
            setSaved(false);
          }}
        />
        <p className="text-xs text-muted-foreground">
          Shown instead of your Google/Facebook photo. Leave blank to use the
          provider photo.
        </p>
      </div>
      <div className="flex items-center gap-2">
        <Button type="submit" size="sm" disabled={save.isPending}>
          {save.isPending ? "Saving…" : "Save photo"}
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
    </form>
  );
}

/** Loads the user, then renders the photo controls prefilled from the current settings. */
export function PhotoSettingsForm({ userId }: { userId: string }) {
  const userQuery = useGetApiV1UsersId(userId, {
    query: { enabled: Boolean(userId) },
  });
  if (userQuery.isLoading || !userQuery.data) {
    return <p className="text-sm text-muted-foreground">Loading…</p>;
  }
  const data = userQuery.data;
  return (
    <Controls
      key={userId}
      userId={userId}
      initialCustomUrl={data.customPhotoUrl ?? ""}
      initialHidden={data.photoHidden ?? false}
    />
  );
}
