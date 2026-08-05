import { useState } from "react";
import { toast } from "sonner";
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
  getGetApiV1UsersPlaceholdersQueryKey,
  usePostApiV1UsersPlaceholders,
} from "@/api/generated/users/users";
import type { CreatePlaceholderRequestSex } from "@/api/generated/model";
import { NTRP_LEVELS } from "@/lib/ntrp";
import { canRate, type Capability } from "@/auth/capabilities";

const SEXES = ["Male", "Female"] as const;

/**
 * Create a login-less placeholder player (#578). Available to HOST/CLUB_OWNER/ADMINISTRATOR (the
 * Placeholder Players tab gate). Display name + sex are required; date of birth is optional. The
 * optional initial rating is a band dropdown shown only to a RATER/ADMINISTRATOR (the rating set is
 * RATER-gated server-side, #503) and is stored at the band midpoint (#579). On success the shared
 * placeholders list is invalidated so the new player appears immediately below.
 */
export function CreatePlaceholderSection({
  capabilities,
}: {
  capabilities: readonly Capability[] | undefined;
}) {
  const queryClient = useQueryClient();
  const [displayName, setDisplayName] = useState("");
  const [sex, setSex] = useState("");
  const [dateOfBirth, setDateOfBirth] = useState("");
  const [initialRating, setInitialRating] = useState("");
  // Inline error is for synchronous field validation only; the create action's success/failure is
  // surfaced as a dismissable toast (#661).
  const [error, setError] = useState<string | null>(null);
  const canSetRating = canRate(capabilities);

  const create = usePostApiV1UsersPlaceholders();

  async function submit() {
    setError(null);
    const name = displayName.trim();
    if (name === "") {
      setError("A display name is required.");
      return;
    }
    if (sex === "") {
      setError("Sex is required.");
      return;
    }
    const rating = initialRating.trim();
    try {
      await create.mutateAsync({
        data: {
          displayName: name,
          sex: sex as CreatePlaceholderRequestSex,
          ...(dateOfBirth !== "" ? { dateOfBirth } : {}),
          // Only a RATER/ADMINISTRATOR may set a rating; the band is stored at its midpoint (#579).
          ...(canSetRating && rating !== "" ? { initialRating: rating } : {}),
        },
      });
      void queryClient.invalidateQueries({
        queryKey: getGetApiV1UsersPlaceholdersQueryKey(),
      });
      toast.success(`Created placeholder player “${name}”.`);
      setDisplayName("");
      setSex("");
      setDateOfBirth("");
      setInitialRating("");
    } catch {
      // Errors linger longer (and can be dismissed) so the reason isn't missed.
      toast.error("Could not create the placeholder player. Please try again.", {
        duration: 8000,
      });
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Create a placeholder player</CardTitle>
        <CardDescription>
          A login-less player for someone without an account — they can be added to events now and
          claim the account (and its history) later via a one-time code.
        </CardDescription>
      </CardHeader>
      <CardContent className="grid gap-3">
        <div className="space-y-1">
          <Label htmlFor="new-placeholder-name" className="text-xs">
            Display name
          </Label>
          <Input
            id="new-placeholder-name"
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            placeholder="Alex P."
          />
        </div>
        <div className="grid grid-cols-2 gap-2">
          <div className="space-y-1">
            <Label htmlFor="new-placeholder-sex" className="text-xs">
              Sex
            </Label>
            <select
              id="new-placeholder-sex"
              value={sex}
              onChange={(e) => setSex(e.target.value)}
              className="h-9 w-full rounded-md border border-input bg-transparent px-3 text-sm"
            >
              <option value="" disabled>
                Select…
              </option>
              {SEXES.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </div>
          <div className="space-y-1">
            <Label htmlFor="new-placeholder-dob" className="text-xs">
              Date of birth (optional)
            </Label>
            <Input
              id="new-placeholder-dob"
              type="date"
              value={dateOfBirth}
              onChange={(e) => setDateOfBirth(e.target.value)}
            />
          </div>
        </div>
        {canSetRating ? (
          <div className="space-y-1">
            <Label htmlFor="new-placeholder-rating" className="text-xs">
              Initial rating (optional)
            </Label>
            {/* A band dropdown (#579): the backend stores the chosen band at its midpoint. */}
            <select
              id="new-placeholder-rating"
              value={initialRating}
              onChange={(e) => setInitialRating(e.target.value)}
              className="h-9 w-full rounded-md border border-input bg-transparent px-3 text-sm"
            >
              <option value="">No initial rating</option>
              {NTRP_LEVELS.map((level) => (
                <option key={level} value={level}>
                  NTRP {level}
                </option>
              ))}
            </select>
          </div>
        ) : null}
        <div className="flex items-center gap-2">
          <Button type="button" size="sm" onClick={submit} disabled={create.isPending}>
            {create.isPending ? "Creating…" : "Create placeholder"}
          </Button>
          {/* Synchronous field-validation only; the create result is a toast (#661). */}
          {error ? (
            <span className="text-xs text-destructive" role="alert">
              {error}
            </span>
          ) : null}
        </div>
      </CardContent>
    </Card>
  );
}
