import { useState, type FormEvent } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import {
  getGetApiV1UsersMeThemeQueryKey,
  useGetApiV1UsersMeTheme,
  usePutApiV1UsersMeTheme,
} from "@/api/generated/settings/settings";
import type { SetLocalThemeRequestTheme } from "@/api/generated/model";

/**
 * The selectable local themes (#514): each ThemeSetting value plus a friendly label. AUTO follows the
 * seasonal calendar; the rest pin a fixed look. The special "" option ("Use default (global)") clears
 * the local theme (null) so the user follows the admin-set global theme.
 */
const THEME_OPTIONS: Array<{ value: string; label: string }> = [
  { value: "", label: "Use default (global)" },
  { value: "AUTO", label: "Auto (by season)" },
  { value: "GRASS", label: "Grass" },
  { value: "CLAY", label: "Clay" },
  { value: "AO", label: "Australian Open" },
  { value: "US_OPEN", label: "US Open" },
  { value: "OFF_SEASON", label: "Off-season" },
  { value: "CHRISTMAS", label: "Christmas" },
  { value: "VALENTINES", label: "Valentine's" },
  { value: "SPRING", label: "Spring" },
  { value: "RAINY", label: "Rainy" },
  { value: "HALLOWEEN", label: "Halloween" },
  { value: "AUTUMN", label: "Autumn" },
  { value: "SKOPEO_OG", label: "Skopeo OG" },
];

/**
 * The selector itself, prefilled from [initialTheme] ("" = follow global). On save it PUTs the choice
 * (null clears it) and invalidates the local-theme query so `LocalThemeApplier` recomputes and the
 * swap is immediate.
 */
function Controls({ initialTheme }: { initialTheme: string }) {
  const queryClient = useQueryClient();
  const save = usePutApiV1UsersMeTheme();
  const [selected, setSelected] = useState<string>(initialTheme);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setSaved(false);
    setError(null);
    try {
      // "" clears the local theme (null → follow the global theme).
      const theme =
        selected === "" ? null : (selected as SetLocalThemeRequestTheme);
      await save.mutateAsync({ data: { theme } });
      await queryClient.invalidateQueries({
        queryKey: getGetApiV1UsersMeThemeQueryKey(),
      });
      setSaved(true);
    } catch {
      setError("Could not save your theme. Please try again.");
    }
  }

  return (
    <form onSubmit={onSubmit} className="space-y-3">
      <div className="space-y-1">
        <Label htmlFor="local-theme">Theme</Label>
        <select
          id="local-theme"
          value={selected}
          onChange={(e) => {
            setSelected(e.target.value);
            setSaved(false);
          }}
          className="h-9 w-full rounded-md border border-input bg-transparent px-3 text-sm"
        >
          {THEME_OPTIONS.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
        <p className="text-xs text-muted-foreground">
          Your own look. A fixed theme overrides the site default; when the site
          default follows the season, a new season re-applies the seasonal look
          until you pick again. "Use default (global)" follows the site default.
        </p>
      </div>
      <div className="flex items-center gap-2">
        <Button type="submit" size="sm" disabled={save.isPending}>
          {save.isPending ? "Saving…" : "Save theme"}
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

/**
 * Per-user "local theme" selector (#514). Reads the caller's OWN theme via `/api/v1/users/me/theme`,
 * then renders the selector prefilled from it (keyed so the initial value applies once loaded, per the
 * PhotoSettingsForm pattern — avoids a setState-in-effect).
 */
export function LocalThemeForm() {
  const themeQuery = useGetApiV1UsersMeTheme();
  if (themeQuery.isLoading) {
    return <p className="text-sm text-muted-foreground">Loading…</p>;
  }
  const initialTheme = themeQuery.data?.theme ?? "";
  return <Controls key={initialTheme} initialTheme={initialTheme} />;
}
