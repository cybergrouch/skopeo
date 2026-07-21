import { useState, type FormEvent } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { UserSearchSelect } from "@/components/UserSearchSelect";
import { usePostApiV1UsersPlaceholders } from "@/api/generated/users/users";
import type {
  CreatePlaceholderRequestSex,
  GetApiV1UsersParams,
  UserResponse,
  UserSummaryResponse,
} from "@/api/generated/model";

const SEXES = ["Male", "Female"] as const;

/** NTRP bounds for the optional initial rating (#503) — validated only when a value is entered. */
const NTRP_MIN = 1.0;
const NTRP_MAX = 7.0;

/**
 * Adapt the full {@link UserResponse} returned by the placeholder-create endpoint into the slim
 * {@link UserSummaryResponse} shape the pickers emit, so a freshly created placeholder is handed back
 * to callers exactly like a searched-and-picked player.
 */
function toSummary(user: UserResponse): UserSummaryResponse {
  return {
    id: user.id,
    publicCode: user.publicCode,
    displayName: user.names[0]?.value ?? null,
    photoUrl: user.photoUrl ?? null,
    sex: (user.sex as UserSummaryResponse["sex"]) ?? undefined,
    capabilities: [],
  };
}

interface PlayerPickerProps {
  label: string;
  placeholder?: string;
  /** Players already chosen elsewhere, hidden from the search results. */
  excludeIds?: string[];
  /** Optional sex/age/rating/capability constraints merged into the search query. */
  filters?: Pick<GetApiV1UsersParams, "sex" | "age" | "rating" | "capability">;
  /**
   * When true, the placeholder-create form offers an optional "Initial rating" field (#503),
   * set in the same flow. Surface this only to RATER/ADMINISTRATOR callers (the rating set is
   * RATER-gated on the backend); everyone can still create a rating-less placeholder.
   */
  canSetRating?: boolean;
  onSelect: (user: UserSummaryResponse) => void;
}

/**
 * Player picker for the Event Organizer flows (#496): first searches existing players by name (the
 * same {@link UserSearchSelect} used everywhere), then — behind an "Add placeholder player" toggle —
 * offers a small form to create a login-less placeholder. A created placeholder is emitted through the
 * same `onSelect` as a picked player, so callers treat it identically.
 */
export function PlayerPicker({
  label,
  placeholder,
  excludeIds = [],
  filters = {},
  canSetRating = false,
  onSelect,
}: PlayerPickerProps) {
  const [creating, setCreating] = useState(false);
  const [displayName, setDisplayName] = useState("");
  const [sex, setSex] = useState("");
  const [dateOfBirth, setDateOfBirth] = useState("");
  const [initialRating, setInitialRating] = useState("");
  const [error, setError] = useState<string | null>(null);

  const create = usePostApiV1UsersPlaceholders();

  function reset() {
    setDisplayName("");
    setSex("");
    setDateOfBirth("");
    setInitialRating("");
    setError(null);
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
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
    // The initial rating is optional; validate the NTRP range only when a value is entered.
    const rating = initialRating.trim();
    if (canSetRating && rating !== "") {
      const parsed = Number(rating);
      if (Number.isNaN(parsed) || parsed < NTRP_MIN || parsed > NTRP_MAX) {
        setError("Initial rating must be a number between 1.0 and 7.0.");
        return;
      }
    }
    try {
      const user = await create.mutateAsync({
        data: {
          displayName: name,
          sex: sex as CreatePlaceholderRequestSex,
          // Send the DOB only when provided; the field is optional.
          ...(dateOfBirth !== "" ? { dateOfBirth } : {}),
          // Send the initial rating only when the caller may set it and entered a value.
          ...(canSetRating && rating !== "" ? { initialRating: rating } : {}),
        },
      });
      onSelect(toSummary(user));
      reset();
      setCreating(false);
    } catch {
      setError("Could not create the placeholder player. Try again.");
    }
  }

  return (
    <div className="space-y-2">
      <UserSearchSelect
        label={label}
        placeholder={placeholder}
        excludeIds={excludeIds}
        filters={filters}
        onSelect={onSelect}
      />
      {creating ? (
        <form
          onSubmit={submit}
          className="grid gap-3 rounded-md border border-input p-3"
        >
          <p className="text-xs font-medium uppercase text-muted-foreground">
            New placeholder player
          </p>
          <p className="text-xs text-muted-foreground">
            Creates a login-less player so someone without an account can be
            added now and claim it later.
          </p>
          <div className="space-y-1">
            <Label htmlFor="placeholder-name" className="text-xs">
              Display name
            </Label>
            <Input
              id="placeholder-name"
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              placeholder="Alex P."
            />
          </div>
          <div className="grid grid-cols-2 gap-2">
            <div className="space-y-1">
              <Label htmlFor="placeholder-sex" className="text-xs">
                Sex
              </Label>
              <select
                id="placeholder-sex"
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
              <Label htmlFor="placeholder-dob" className="text-xs">
                Date of birth (optional)
              </Label>
              <Input
                id="placeholder-dob"
                type="date"
                value={dateOfBirth}
                onChange={(e) => setDateOfBirth(e.target.value)}
              />
            </div>
          </div>
          {canSetRating ? (
            <div className="space-y-1">
              <Label htmlFor="placeholder-rating" className="text-xs">
                Initial rating (optional)
              </Label>
              <Input
                id="placeholder-rating"
                type="text"
                inputMode="decimal"
                value={initialRating}
                onChange={(e) => setInitialRating(e.target.value)}
                placeholder="NTRP 1.0–7.0"
              />
            </div>
          ) : null}
          {error ? (
            <p className="text-sm text-destructive" role="alert">
              {error}
            </p>
          ) : null}
          <div className="flex items-center gap-2">
            <Button type="submit" size="sm" disabled={create.isPending}>
              {create.isPending ? "Creating…" : "Create placeholder"}
            </Button>
            <Button
              type="button"
              variant="ghost"
              size="sm"
              disabled={create.isPending}
              onClick={() => {
                reset();
                setCreating(false);
              }}
            >
              Cancel
            </Button>
          </div>
        </form>
      ) : (
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={() => setCreating(true)}
        >
          Add placeholder player
        </Button>
      )}
    </div>
  );
}
