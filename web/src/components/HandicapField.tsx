import { useId } from "react";
import { Info } from "lucide-react";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";

/**
 * Per-side rating handicap (#486) input for the fixture create/edit form. Discouraged by design: the
 * handicap is hidden behind an explicit "Apply handicap" checkbox, and un-ticking it clears both sides'
 * values. The checkbox carries a prudence tooltip (fair to *both* sides). Values are team-mean NTRP
 * points in 0 < h <= 1.0. See RATING_HANDICAP.md.
 */
export const HANDICAP_TOOLTIP =
  "A handicap is a fairness adjustment for lopsided-but-competitive matchups. " +
  "Use it prudently: the goal is a result that's fair to both sides, not an advantage for one.";

interface HandicapFieldProps {
  /** Whether the "Apply handicap" checkbox is ticked (the inputs are revealed). */
  enabled: boolean;
  onToggle: (enabled: boolean) => void;
  /** Draft values (kept as strings so the inputs can be cleared while editing). */
  team1Handicap: string;
  team2Handicap: string;
  onTeam1Change: (value: string) => void;
  onTeam2Change: (value: string) => void;
  /** Labels for each side (e.g. player/team names), for the two inputs. */
  team1Label?: string;
  team2Label?: string;
  disabled?: boolean;
}

export function HandicapField({
  enabled,
  onToggle,
  team1Handicap,
  team2Handicap,
  onTeam1Change,
  onTeam2Change,
  team1Label = "Side 1",
  team2Label = "Side 2",
  disabled = false,
}: HandicapFieldProps) {
  const tipId = useId();
  return (
    <div className="space-y-2">
      <div className="flex items-center gap-2">
        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={enabled}
            disabled={disabled}
            onChange={(e) => onToggle(e.target.checked)}
            aria-label="Apply handicap"
          />
          Apply handicap
        </label>
        <Popover>
          <PopoverTrigger asChild>
            <button
              type="button"
              aria-label="What is a handicap?"
              aria-describedby={tipId}
              className="inline-flex items-center text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring rounded-sm"
            >
              <Info aria-hidden="true" className="size-4" />
            </button>
          </PopoverTrigger>
          <PopoverContent id={tipId} role="tooltip">
            {HANDICAP_TOOLTIP}
          </PopoverContent>
        </Popover>
      </div>
      {enabled ? (
        <div className="grid grid-cols-2 gap-2">
          <div className="space-y-1">
            <Label htmlFor="handicap-team1" className="text-xs">
              {team1Label} handicap
            </Label>
            <Input
              id="handicap-team1"
              type="number"
              step="0.1"
              min={0}
              max={1}
              value={team1Handicap}
              placeholder="0.0"
              disabled={disabled}
              onChange={(e) => onTeam1Change(e.target.value)}
            />
          </div>
          <div className="space-y-1">
            <Label htmlFor="handicap-team2" className="text-xs">
              {team2Label} handicap
            </Label>
            <Input
              id="handicap-team2"
              type="number"
              step="0.1"
              min={0}
              max={1}
              value={team2Handicap}
              placeholder="0.0"
              disabled={disabled}
              onChange={(e) => onTeam2Change(e.target.value)}
            />
          </div>
        </div>
      ) : null}
    </div>
  );
}
