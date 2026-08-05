import { useState, type FormEvent } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
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
import {
  getGetApiV1ApiClientsQueryKey,
  useDeleteApiV1ApiClientsClientIdKeysKeyId,
  useGetApiV1ApiClients,
  usePostApiV1ApiClients,
  usePostApiV1ApiClientsIdKeys,
  usePutApiV1ApiClientsIdRateLimit,
} from "@/api/generated/client-api/client-api";
import type {
  ApiClientResponse,
  ApiKeyResponse,
  IssuedApiKeyResponse,
} from "@/api/generated/model";
import { Capability } from "@/auth/capabilities";

/** The capability names a key can be scoped to (least privilege, #597). */
const SCOPE_OPTIONS = Object.values(Capability);

/**
 * Admin management of partner API clients and keys (#602, surfacing #596/#597). ADMINISTRATOR-only
 * (this section renders only in the Admin tab, and the API enforces it). A key's plaintext secret is
 * shown exactly once at issue time and never stored — the list only ever shows the non-secret prefix.
 */
export function ApiClientsSection() {
  const queryClient = useQueryClient();
  const clientsQuery = useGetApiV1ApiClients();
  const clients = clientsQuery.data ?? [];
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const create = usePostApiV1ApiClients();

  function invalidate() {
    void queryClient.invalidateQueries({
      queryKey: getGetApiV1ApiClientsQueryKey(),
    });
  }

  async function onCreate(event: FormEvent) {
    event.preventDefault();
    setError(null);
    const trimmed = name.trim();
    if (!trimmed) {
      setError("Client name is required.");
      return;
    }
    try {
      await create.mutateAsync({ data: { name: trimmed } });
      setName("");
      invalidate();
    } catch {
      toast.error("Could not create the client.", { duration: 8000 });
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>API clients</CardTitle>
        <CardDescription>
          Register partner applications and issue them API keys. A key&rsquo;s
          secret is shown once at creation and can never be retrieved again.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <form onSubmit={onCreate} className="flex items-end gap-2">
          <div className="flex-1 space-y-1">
            <Label htmlFor="api-client-name">New client</Label>
            <Input
              id="api-client-name"
              value={name}
              placeholder="Partner name"
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

        {clientsQuery.isLoading ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : clients.length === 0 ? (
          <p className="text-sm text-muted-foreground">No API clients yet.</p>
        ) : (
          <ul className="space-y-3">
            {clients.map((client) => (
              <ApiClientRow
                key={client.id}
                client={client}
                onChange={invalidate}
              />
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  );
}

function ApiClientRow({
  client,
  onChange,
}: {
  client: ApiClientResponse;
  onChange: () => void;
}) {
  const issue = usePostApiV1ApiClientsIdKeys();
  const revoke = useDeleteApiV1ApiClientsClientIdKeysKeyId();
  const setLimit = usePutApiV1ApiClientsIdRateLimit();
  const [scopes, setScopes] = useState<string[]>([]);
  const [environment, setEnvironment] = useState("LIVE");
  const [expiresInDays, setExpiresInDays] = useState("");
  const [limitDraft, setLimitDraft] = useState(client.rateLimitPerMin?.toString() ?? "");
  const [issued, setIssued] = useState<IssuedApiKeyResponse | null>(null);
  const [copied, setCopied] = useState(false);

  function toggleScope(scope: string) {
    setScopes((prev) =>
      prev.includes(scope) ? prev.filter((s) => s !== scope) : [...prev, scope],
    );
  }

  async function onIssue() {
    const days = expiresInDays.trim();
    try {
      const result = await issue.mutateAsync({
        id: client.id,
        data: {
          ...(scopes.length > 0 ? { scopes } : {}),
          environment,
          ...(days ? { expiresInDays: Number(days) } : {}),
        },
      });
      setIssued(result);
      setCopied(false);
      setScopes([]);
      setExpiresInDays("");
      onChange();
    } catch {
      toast.error("Could not issue the key.", { duration: 8000 });
    }
  }

  async function onRevoke(keyId: string) {
    try {
      await revoke.mutateAsync({ clientId: client.id, keyId });
      onChange();
    } catch {
      toast.error("Could not revoke the key.", { duration: 8000 });
    }
  }

  async function saveLimit() {
    const draft = limitDraft.trim();
    // Empty clears the override (fall back to the default tier); otherwise a positive integer.
    const rateLimitPerMin = draft === "" ? null : Number(draft);
    try {
      await setLimit.mutateAsync({ id: client.id, data: { rateLimitPerMin } });
      onChange();
    } catch {
      toast.error("Could not update the rate limit.", { duration: 8000 });
    }
  }

  async function copySecret() {
    if (!issued) return;
    try {
      await navigator.clipboard.writeText(issued.apiKey);
      setCopied(true);
    } catch {
      // Clipboard unavailable (e.g. insecure context) — the secret stays visible to copy manually.
    }
  }

  return (
    <li className="space-y-3 rounded-lg border p-3">
      <div className="flex items-center justify-between gap-2">
        <p className="font-medium">{client.name}</p>
        <span className="text-xs text-muted-foreground">{client.status}</span>
      </div>

      {/* Per-client rate-limit override (#603): blank = the global default tier. */}
      <div className="flex flex-wrap items-end gap-2">
        <div className="space-y-1">
          <Label htmlFor={`limit-${client.id}`} className="text-xs">
            Rate limit / min (blank = default)
          </Label>
          <Input
            id={`limit-${client.id}`}
            type="number"
            min="1"
            value={limitDraft}
            onChange={(e) => setLimitDraft(e.target.value)}
            className="w-48"
          />
        </div>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={saveLimit}
          disabled={setLimit.isPending}
        >
          {setLimit.isPending ? "Saving…" : "Save limit"}
        </Button>
      </div>

      {issued ? (
        <div
          className="space-y-2 rounded-md border border-primary bg-primary/5 p-3"
          role="status"
        >
          <p className="text-sm font-medium text-foreground">
            New key — copy it now. It won&rsquo;t be shown again.
          </p>
          <code
            data-testid="issued-secret"
            className="block break-all rounded bg-background p-2 font-mono text-xs"
          >
            {issued.apiKey}
          </code>
          <div className="flex items-center gap-2">
            <Button type="button" size="sm" onClick={copySecret}>
              {copied ? "Copied" : "Copy key"}
            </Button>
            <Button
              type="button"
              variant="ghost"
              size="sm"
              onClick={() => setIssued(null)}
            >
              Done
            </Button>
          </div>
        </div>
      ) : null}

      {client.keys.length > 0 ? (
        <ul className="space-y-1">
          {client.keys.map((key) => (
            <ApiKeyRow
              key={key.id}
              apiKey={key}
              onRevoke={() => onRevoke(key.id)}
              revoking={revoke.isPending}
            />
          ))}
        </ul>
      ) : (
        <p className="text-xs text-muted-foreground">No keys yet.</p>
      )}

      <div className="space-y-2 border-t pt-3">
        <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
          Issue a key
        </p>
        <div className="flex flex-wrap gap-2">
          {SCOPE_OPTIONS.map((scope) => (
            <label
              key={scope}
              className="flex items-center gap-1 text-xs text-muted-foreground"
            >
              <input
                type="checkbox"
                checked={scopes.includes(scope)}
                onChange={() => toggleScope(scope)}
                aria-label={`Scope ${scope}`}
              />
              {scope}
            </label>
          ))}
        </div>
        <div className="flex flex-wrap items-end gap-2">
          <div className="space-y-1">
            <Label htmlFor={`env-${client.id}`} className="text-xs">
              Environment
            </Label>
            <select
              id={`env-${client.id}`}
              value={environment}
              onChange={(e) => setEnvironment(e.target.value)}
              className="h-9 rounded-md border border-input bg-transparent px-3 text-sm"
            >
              <option value="LIVE">LIVE</option>
              <option value="TEST">TEST</option>
            </select>
          </div>
          <div className="space-y-1">
            <Label htmlFor={`exp-${client.id}`} className="text-xs">
              Expires in days (optional)
            </Label>
            <Input
              id={`exp-${client.id}`}
              type="number"
              min="1"
              value={expiresInDays}
              onChange={(e) => setExpiresInDays(e.target.value)}
              className="w-40"
            />
          </div>
          <Button type="button" size="sm" onClick={onIssue} disabled={issue.isPending}>
            {issue.isPending ? "Issuing…" : "Issue key"}
          </Button>
        </div>
      </div>
    </li>
  );
}

function ApiKeyRow({
  apiKey,
  onRevoke,
  revoking,
}: {
  apiKey: ApiKeyResponse;
  onRevoke: () => void;
  revoking: boolean;
}) {
  const active = apiKey.status === "ACTIVE";
  return (
    <li className="flex items-center justify-between gap-2 text-sm">
      <span className="min-w-0">
        <code className="font-mono text-xs">{apiKey.keyPrefix}…</code>
        <span className="text-muted-foreground">
          {" · "}
          {apiKey.status}
          {apiKey.scopes.length > 0 ? ` · ${apiKey.scopes.join(", ")}` : ""}
          {apiKey.expiresAt ? ` · expires ${apiKey.expiresAt.slice(0, 10)}` : ""}
        </span>
      </span>
      {active ? (
        <Button
          type="button"
          variant="ghost"
          size="sm"
          className="text-destructive hover:text-destructive"
          disabled={revoking}
          onClick={onRevoke}
        >
          Revoke
        </Button>
      ) : null}
    </li>
  );
}
