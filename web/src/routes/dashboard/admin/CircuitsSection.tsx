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
import {
  getGetApiV1CircuitsQueryKey,
  useDeleteApiV1CircuitsId,
  useGetApiV1Circuits,
  usePatchApiV1CircuitsId,
  usePostApiV1Circuits,
} from "@/api/generated/circuits/circuits";
import type { CircuitResponse } from "@/api/generated/model";

/**
 * Admin circuit management (#525): create, rename, and delete circuits (groupings of tournaments,
 * e.g. NORTH, SOUTH). ADMINISTRATOR-only (this section only renders in the Admin tab, and the API
 * enforces it). Circuits are picked when creating a tournament event.
 */
export function CircuitsSection() {
  const queryClient = useQueryClient();
  const circuitsQuery = useGetApiV1Circuits();
  const circuits = circuitsQuery.data ?? [];
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const create = usePostApiV1Circuits();

  function invalidate() {
    void queryClient.invalidateQueries({
      queryKey: getGetApiV1CircuitsQueryKey(),
    });
  }

  async function onCreate(event: FormEvent) {
    event.preventDefault();
    setError(null);
    const trimmed = name.trim();
    if (!trimmed) {
      setError("Circuit name is required.");
      return;
    }
    try {
      await create.mutateAsync({ data: { name: trimmed } });
      setName("");
      invalidate();
    } catch {
      setError("Could not create the circuit.");
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Circuits</CardTitle>
        <CardDescription>
          Create circuits (groupings of tournaments, e.g. NORTH and SOUTH).
          Tournaments are assigned to a circuit.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <form onSubmit={onCreate} className="flex items-end gap-2">
          <div className="flex-1 space-y-1">
            <Label htmlFor="circuit-name">New circuit</Label>
            <Input
              id="circuit-name"
              value={name}
              placeholder="Circuit name"
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

        {circuitsQuery.isLoading ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : circuits.length === 0 ? (
          <p className="text-sm text-muted-foreground">No circuits yet.</p>
        ) : (
          <ul className="space-y-2">
            {circuits.map((circuit) => (
              <CircuitRow
                key={circuit.id}
                circuit={circuit}
                onChange={invalidate}
              />
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  );
}

function CircuitRow({
  circuit,
  onChange,
}: {
  circuit: CircuitResponse;
  onChange: () => void;
}) {
  const rename = usePatchApiV1CircuitsId();
  const del = useDeleteApiV1CircuitsId();

  const [editing, setEditing] = useState(false);
  const [nameDraft, setNameDraft] = useState(circuit.name);
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function saveName() {
    const trimmed = nameDraft.trim();
    if (!trimmed) {
      setError("Circuit name is required.");
      return;
    }
    setError(null);
    try {
      await rename.mutateAsync({ id: circuit.id, data: { name: trimmed } });
      setEditing(false);
      onChange();
    } catch {
      setError("Could not rename the circuit.");
    }
  }

  async function remove() {
    setError(null);
    try {
      await del.mutateAsync({ id: circuit.id });
      onChange();
    } catch {
      setError("Could not delete the circuit.");
    }
  }

  return (
    <li className="rounded-md border border-border p-3">
      <div className="flex items-center justify-between gap-2">
        {editing ? (
          <div className="flex flex-1 items-end gap-2">
            <Input
              aria-label="Circuit name"
              value={nameDraft}
              onChange={(e) => setNameDraft(e.target.value)}
            />
            <Button size="sm" onClick={saveName} disabled={rename.isPending}>
              Save
            </Button>
            <Button
              size="sm"
              variant="ghost"
              onClick={() => {
                setEditing(false);
                setNameDraft(circuit.name);
                setError(null);
              }}
            >
              Cancel
            </Button>
          </div>
        ) : (
          <>
            <span className="font-medium">{circuit.name}</span>
            <div className="flex gap-2">
              <Button
                size="sm"
                variant="ghost"
                onClick={() => setEditing(true)}
              >
                Rename
              </Button>
              {confirmingDelete ? (
                <>
                  <Button
                    size="sm"
                    variant="default"
                    onClick={remove}
                    disabled={del.isPending}
                  >
                    Confirm
                  </Button>
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={() => setConfirmingDelete(false)}
                  >
                    Cancel
                  </Button>
                </>
              ) : (
                <Button
                  size="sm"
                  variant="ghost"
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
        <p className="mt-2 text-sm text-destructive" role="alert">
          {error}
        </p>
      ) : null}
    </li>
  );
}
