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
import {
  getGetApiV1UsersPlaceholdersQueryKey,
  useGetApiV1UsersPlaceholders,
  usePostApiV1UsersIdClaimCode,
} from "@/api/generated/users/users";
import type {
  ClaimCodeResponse,
  UserSummaryResponse,
} from "@/api/generated/model";
import { isAdministrator, type Capability } from "@/auth/capabilities";
import { playerLabel } from "@/lib/playerLabel";
import { PlaceholderTag } from "@/components/PlaceholderTag";

/** "Female · 34" — a placeholder's sex and age, omitting whatever is missing. */
function placeholderMeta(user: UserSummaryResponse): string {
  const parts: string[] = [];
  if (user.sex) parts.push(user.sex);
  if (user.age != null) parts.push(String(user.age));
  return parts.join(" · ");
}

/**
 * The one-time claim-code panel for a placeholder (#496): shows the plaintext code exactly once with a
 * copy affordance and a plain "won't be shown again" warning. Rendered inline under its row after the
 * admin generates it; dismissed to clear it from the screen.
 */
function ClaimCodePanel({
  result,
  onDismiss,
}: {
  result: ClaimCodeResponse;
  onDismiss: () => void;
}) {
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(result.code);
      setCopied(true);
    } catch {
      // Clipboard may be unavailable (permissions/insecure context); the code is still shown to copy
      // by hand, so a failed copy is non-fatal — just don't claim success.
      setCopied(false);
    }
  }

  return (
    <div
      className="mt-2 space-y-2 rounded-md border border-amber-500/50 bg-amber-500/10 p-3"
      role="status"
    >
      <p className="text-xs font-medium uppercase text-muted-foreground">
        Claim code for {result.placeholderPublicCode}
      </p>
      <p className="text-sm font-semibold">
        Copy this now — it won&rsquo;t be shown again.
      </p>
      <code
        data-testid="claim-code"
        className="block select-all break-all rounded bg-background px-2 py-1 font-mono text-sm"
      >
        {result.code}
      </code>
      <p className="text-xs text-muted-foreground">
        Expires {result.expiresAt.slice(0, 10)}. Hand it to the verified person;
        they claim the account from their signed-in dashboard.
      </p>
      <div className="flex items-center gap-2">
        <Button type="button" size="sm" onClick={copy}>
          {copied ? "Copied" : "Copy code"}
        </Button>
        <Button type="button" variant="ghost" size="sm" onClick={onDismiss}>
          Done
        </Button>
      </div>
    </div>
  );
}

function PlaceholderRow({
  user,
  canGenerate,
}: {
  user: UserSummaryResponse;
  canGenerate: boolean;
}) {
  const [result, setResult] = useState<ClaimCodeResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const generate = usePostApiV1UsersIdClaimCode();
  const meta = placeholderMeta(user);

  async function onGenerate() {
    setError(null);
    try {
      const code = await generate.mutateAsync({ id: user.id });
      setResult(code);
    } catch {
      setError("Could not generate a claim code. Try again.");
    }
  }

  return (
    <li className="rounded-lg border p-3 text-sm">
      <div className="flex items-center justify-between gap-2">
        <div className="min-w-0">
          <div className="truncate font-medium">
            {playerLabel(user.displayName, user.publicCode, user.id)}
            <PlaceholderTag show={user.isPlaceholder} deleted={user.isDeleted} />
          </div>
          <div className="text-xs text-muted-foreground">
            {user.publicCode}
            {meta ? ` · ${meta}` : ""}
          </div>
        </div>
        {canGenerate ? (
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={generate.isPending}
            onClick={onGenerate}
          >
            {generate.isPending ? "Generating…" : "Generate claim code"}
          </Button>
        ) : null}
      </div>
      {error ? (
        <p className="mt-2 text-sm text-destructive" role="alert">
          {error}
        </p>
      ) : null}
      {result ? (
        <ClaimCodePanel result={result} onDismiss={() => setResult(null)} />
      ) : null}
    </li>
  );
}

/**
 * Placeholder-players management (#496): lists the active, unclaimed placeholders (HOST/CLUB_OWNER/
 * ADMIN) and — for an ADMINISTRATOR — offers a one-time claim-code generator per row. The plaintext
 * code is shown once with a copy-now warning. Deactivation is deferred: there is no dedicated
 * deactivate-placeholder endpoint, so it is intentionally omitted rather than reusing the general
 * user-delete.
 */
export function PlaceholderPlayersSection({
  capabilities,
}: {
  capabilities: readonly Capability[] | undefined;
}) {
  // Keep the list fresh so a placeholder just created from a fixture flow appears here.
  const queryClient = useQueryClient();
  const query = useGetApiV1UsersPlaceholders();
  const placeholders = query.data ?? [];
  const canGenerate = isAdministrator(capabilities);

  return (
    <Card>
      <CardHeader>
        <CardTitle>Placeholder players</CardTitle>
        <CardDescription>
          Login-less players created for people without accounts. An
          administrator can generate a one-time claim code so the real person
          can adopt the account and its history.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        <Button
          type="button"
          variant="ghost"
          size="sm"
          disabled={query.isFetching}
          onClick={() =>
            queryClient.invalidateQueries({
              queryKey: getGetApiV1UsersPlaceholdersQueryKey(),
            })
          }
        >
          {query.isFetching ? "Refreshing…" : "Refresh"}
        </Button>
        {query.isLoading ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : placeholders.length > 0 ? (
          <ul className="space-y-2">
            {placeholders.map((user) => (
              <PlaceholderRow
                key={user.id}
                user={user}
                canGenerate={canGenerate}
              />
            ))}
          </ul>
        ) : (
          <p className="text-sm text-muted-foreground">
            No unclaimed placeholder players.
          </p>
        )}
      </CardContent>
    </Card>
  );
}
