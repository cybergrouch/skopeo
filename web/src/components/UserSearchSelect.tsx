import { useState } from "react";
import type { ReactNode } from "react";
import { NtrpDisclaimerInfo } from "@/components/NtrpLabel";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useDebouncedValue } from "@/hooks/useDebouncedValue";
import { formatConfidence } from "@/lib/confidence";
import { useGetApiV1Users } from "@/api/generated/users/users";
import type {
  GetApiV1UsersParams,
  UserSummaryResponse,
} from "@/api/generated/model";

const MIN_QUERY = 2;

/**
 * Secondary suggestion line — sex · age · NTRP band — to disambiguate similar names (#87).
 *
 * The NTRP band is **plain text here, deliberately** (#852). Each suggestion is a `<button>`, and the
 * disclaimer trigger is also a `<button>` — nesting them made the disclaimer unreachable: the click landed
 * on the outer button and picked the player instead. The trigger lives once beside the field's label, which
 * covers every suggestion in the list. See WEB_UI_ARCHITECTURE.md.
 */
function detailLine(user: UserSummaryResponse): ReactNode {
  const parts: string[] = [];
  if (user.sex) parts.push(user.sex);
  if (user.age != null) parts.push(String(user.age));
  if (user.rating) {
    const pct = formatConfidence(user.rating.confidence);
    const band = user.rating.level ?? user.rating.value;
    parts.push(pct ? `NTRP ${band} · ${pct}` : `NTRP ${band}`);
  }
  if (parts.length === 0) return null;
  return parts.join(" · ");
}

interface UserSearchSelectProps {
  label: string;
  placeholder?: string;
  /** Users already chosen elsewhere, hidden from the results. */
  excludeIds?: string[];
  /** Optional sex/age/rating/capability constraints merged into the search query (#111, #317). */
  filters?: Pick<GetApiV1UsersParams, "sex" | "age" | "rating" | "capability">;
  onSelect: (user: UserSummaryResponse) => void;
}

/** Typeahead that searches users by name (GET /users?name=) and emits the picked one. */
export function UserSearchSelect({
  label,
  placeholder,
  excludeIds = [],
  filters = {},
  onSelect,
}: UserSearchSelectProps) {
  const [term, setTerm] = useState("");
  const debounced = useDebouncedValue(term).trim();
  const enabled = debounced.length >= MIN_QUERY;

  // Unified search (#86): the backend matches the term against names (fuzzy) OR a player-code
  // prefix, so partial codes and names both surface incrementally without client-side guessing.
  // Optional filters (#111) narrow the candidate pool to the desired sex/age/rating band.
  const query = useGetApiV1Users(
    { q: debounced, ...filters },
    { query: { enabled } },
  );
  const results = (query.data ?? []).filter((u) => !excludeIds.includes(u.id));

  function pick(user: UserSummaryResponse) {
    onSelect(user);
    setTerm("");
  }

  return (
    <div className="space-y-1">
      <Label htmlFor={`search-${label}`}>{label}</Label>{" "}
      {/* The one disclaimer trigger for this field (#852/#842). It sits OUTSIDE the suggestion buttons —
          and outside the <Label>, which would otherwise capture the control's accessible name. */}
      <NtrpDisclaimerInfo />
      <Input
        id={`search-${label}`}
        value={term}
        placeholder={placeholder ?? "Search by name or player ID…"}
        onChange={(e) => setTerm(e.target.value)}
      />
      {enabled && results.length > 0 ? (
        <ul className="rounded-md border" role="listbox">
          {results.map((user) => {
            const detail = detailLine(user);
            return (
              <li key={user.id}>
                <button
                  type="button"
                  className="block w-full px-3 py-2 text-left text-sm hover:bg-accent"
                  onClick={() => pick(user)}
                >
                  <span className="font-medium">
                    {user.displayName ?? user.id}
                  </span>{" "}
                  <span className="text-muted-foreground">
                    · {user.publicCode}
                  </span>
                  {detail ? (
                    <span className="block text-xs text-muted-foreground">
                      {detail}
                    </span>
                  ) : null}
                </button>
              </li>
            );
          })}
        </ul>
      ) : null}
      {enabled && !query.isLoading && results.length === 0 ? (
        <p className="text-sm text-muted-foreground">No matches.</p>
      ) : null}
    </div>
  );
}
