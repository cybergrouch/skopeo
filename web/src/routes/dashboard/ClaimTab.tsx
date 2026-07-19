import { useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
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
  getGetApiV1UsersMeQueryKey,
  usePostApiV1UsersClaim,
} from "@/api/generated/users/users";
import type { UserResponse } from "@/api/generated/model";

/** Prefer the server's message (e.g. "code expired", "account not empty"), else a generic fallback. */
function claimErrorMessage(err: unknown, fallback: string): string {
  const message = (err as { response?: { data?: { message?: string } } })
    ?.response?.data?.message;
  return message && message.trim() !== "" ? message : fallback;
}

/**
 * "Claim a placeholder account" (#496): any signed-in user pastes the secret code an administrator
 * handed them; on success the placeholder's history is merged into their account. Rejections
 * (bad/expired/consumed code, a non-empty account, etc.) are surfaced inline from the server message.
 */
export function ClaimTab() {
  const queryClient = useQueryClient();
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [claimed, setClaimed] = useState<UserResponse | null>(null);
  const claim = usePostApiV1UsersClaim();

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    const trimmed = code.trim();
    if (trimmed === "") {
      setError("Enter the claim code you were given.");
      return;
    }
    try {
      const user = await claim.mutateAsync({ data: { code: trimmed } });
      setClaimed(user);
      setCode("");
      // The caller's profile (and its now-merged history) has changed — refresh it everywhere.
      await queryClient.invalidateQueries({
        queryKey: getGetApiV1UsersMeQueryKey(),
      });
    } catch (err) {
      setError(
        claimErrorMessage(
          err,
          "That code could not be used. It may be wrong, expired, or already claimed — or your account already has activity.",
        ),
      );
    }
  }

  if (claimed) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>Account claimed</CardTitle>
          <CardDescription>
            The placeholder&rsquo;s match and rating history is now part of your
            account.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-2">
          <p className="text-sm" role="status">
            You&rsquo;re all set.
          </p>
          <Link
            to={`/players/${claimed.publicCode}`}
            className="text-sm text-primary hover:underline"
          >
            View your profile →
          </Link>
        </CardContent>
      </Card>
    );
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Claim a placeholder account</CardTitle>
        <CardDescription>
          Were you added to matches before you had an account? Ask an
          administrator for your one-time claim code and paste it here to adopt
          that player and its history.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <form onSubmit={onSubmit} className="grid gap-3">
          <div className="space-y-1">
            <Label htmlFor="claim-code" className="text-xs">
              Claim code
            </Label>
            <Input
              id="claim-code"
              value={code}
              onChange={(e) => setCode(e.target.value)}
              placeholder="Paste your code"
              autoComplete="off"
            />
          </div>
          {error ? (
            <p className="text-sm text-destructive" role="alert">
              {error}
            </p>
          ) : null}
          <Button type="submit" size="sm" disabled={claim.isPending}>
            {claim.isPending ? "Claiming…" : "Claim account"}
          </Button>
        </form>
      </CardContent>
    </Card>
  );
}
