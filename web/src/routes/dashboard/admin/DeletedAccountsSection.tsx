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
import { Label } from "@/components/ui/label";
import { useDebouncedValue } from "@/hooks/useDebouncedValue";
import {
  getGetApiV1UsersSearchQueryKey,
  useGetApiV1UsersSearch,
  usePostApiV1UsersIdReactivate,
} from "@/api/generated/users/users";
import type { UserSummaryResponse } from "@/api/generated/model";
import { playerLabel } from "@/lib/playerLabel";
import { PlaceholderTag } from "@/components/PlaceholderTag";

const MIN_QUERY = 2;

/** The backend's `{ error, message }` body carries a human-readable reason. */
function errorMessage(err: unknown, fallback: string): string {
  const message = (err as { response?: { data?: { message?: string } } })?.response?.data?.message;
  return message ?? fallback;
}

/** One deleted-account row with its "Allow login" (reactivate) action + inline feedback. */
function DeletedAccountRow({
  user,
  onReactivated,
}: {
  user: UserSummaryResponse;
  onReactivated: () => void;
}) {
  const [error, setError] = useState<string | null>(null);
  const reactivate = usePostApiV1UsersIdReactivate({
    mutation: {
      onSuccess: onReactivated,
      onError: (e) => setError(errorMessage(e, "Could not re-allow login.")),
    },
  });

  return (
    <li className="flex items-center justify-between gap-2 rounded-lg border p-3 text-sm">
      <div className="min-w-0">
        <div className="truncate font-medium">
          {playerLabel(user.displayName, user.publicCode, user.id)}
          <PlaceholderTag show={user.isPlaceholder} deleted={user.isDeleted} />
        </div>
        <div className="text-xs text-muted-foreground">{user.publicCode}</div>
        {error ? (
          <p className="mt-1 text-xs text-destructive" role="alert">
            {error}
          </p>
        ) : null}
      </div>
      <Button
        type="button"
        size="sm"
        disabled={reactivate.isPending}
        aria-label={`Allow login for ${user.publicCode}`}
        onClick={() => {
          setError(null);
          reactivate.mutate({ id: user.id });
        }}
      >
        Allow login
      </Button>
    </li>
  );
}

/**
 * Deleted accounts / allow-login (#518): a dedicated admin view for finding soft-deleted accounts and
 * re-enabling their sign-in. The normal player search excludes inactive accounts, so deleted ones
 * aren't discoverable there — this view searches with `includeInactive=true` and filters to genuinely
 * deleted accounts (`isDeleted`), so merged duplicates never appear. ADMINISTRATOR-only (this whole tab
 * is admin-gated); reactivation is audited server-side.
 */
export function DeletedAccountsSection() {
  const queryClient = useQueryClient();
  const [term, setTerm] = useState("");
  const debounced = useDebouncedValue(term).trim();
  const enabled = debounced.length >= MIN_QUERY;

  const params = { q: debounced, includeInactive: true, limit: 25, offset: 0 };
  const query = useGetApiV1UsersSearch(params, { query: { enabled } });
  // Only genuinely deleted accounts (soft-deleted by an admin), not merged duplicates.
  const deleted = (query.data?.items ?? []).filter((u) => u.isDeleted);

  const refetch = () =>
    queryClient.invalidateQueries({ queryKey: getGetApiV1UsersSearchQueryKey(params) });

  return (
    <Card>
      <CardHeader>
        <CardTitle>Deleted accounts</CardTitle>
        <CardDescription>
          Find a soft-deleted account and re-allow its login. Deleted accounts stay out of normal
          search and pickers; search here (by name or player code) to restore access.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="space-y-1">
          <Label htmlFor="deleted-search">Search</Label>
          <Input
            id="deleted-search"
            value={term}
            placeholder="Search deleted accounts by name or player ID…"
            onChange={(e) => setTerm(e.target.value)}
          />
        </div>
        {enabled && deleted.length > 0 ? (
          <ul className="space-y-2">
            {deleted.map((user) => (
              <DeletedAccountRow key={user.id} user={user} onReactivated={refetch} />
            ))}
          </ul>
        ) : null}
        {enabled && !query.isLoading && deleted.length === 0 ? (
          <p className="text-sm text-muted-foreground">No deleted accounts match.</p>
        ) : null}
      </CardContent>
    </Card>
  );
}
