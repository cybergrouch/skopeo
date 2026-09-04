import { useId } from 'react'
import { Info } from 'lucide-react'
import type { ReactNode } from 'react'
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '@/components/ui/popover'
import { cn } from '@/lib/utils'

/**
 * The USTA non-affiliation disclaimer (#842). Exported so every surface shows the identical wording —
 * the string lives here and nowhere else, so it cannot drift between screens.
 */
export const NTRP_DISCLAIMER =
  'NTRP is a reference framework. This platform is not affiliated with or endorsed by the United States Tennis Association (USTA).'

/**
 * Appended to the label when the rating is still being calibrated (#881).
 *
 * A bare asterisk, inside the same text node, deliberately:
 *
 * - **Inside**, because splitting the label across nodes breaks `getByText` (see below) — and because a
 *   marker sitting outside the trigger would not be clickable, so the explanation would be unreachable
 *   from the thing it qualifies.
 * - **Bare**, because NTRP appears many times per screen (a 16-player roster renders it 16 times) and a
 *   word like "calibrating" beside each one would swamp the data. The popover carries the sentence; the
 *   asterisk only signals that there is one.
 */
const CALIBRATION_MARKER = '*'

/** The explanation shown alongside the disclaimer when a rating is calibrating (#881). */
export const NTRP_CALIBRATION_NOTE =
  'This rating is still being calibrated: it was set by hand, and for its first few rated matches it moves ' +
  'without affecting opponents’ or partners’ ratings. Treat it as provisional.'

interface NtrpLabelProps {
  /** A band or rating to render after the term, e.g. `4.0` → "NTRP 4.0". */
  value?: string | number
  /** Replace the whole trigger text. Defaults to "NTRP", or "NTRP {value}" when [value] is given. */
  children?: ReactNode
  /** Extra classes for the inline trigger. */
  className?: string
  /**
   * Mark the rating as still being calibrated (#881) — appends {@link CALIBRATION_MARKER} and adds
   * {@link NTRP_CALIBRATION_NOTE} to the popover.
   *
   * Comes from the server (`rating.inCalibration`), never derived here: the rule depends on a designation
   * timestamp, a rated-match count and a global setting, none of which the client holds — and a client
   * that guessed would eventually disagree with the standings about who is provisional.
   */
  calibrating?: boolean
}

/**
 * The word **NTRP**, rendered as a popup trigger carrying {@link NTRP_DISCLAIMER} (#842). Every visible
 * NTRP label in the app goes through this, so the disclaimer is reachable wherever the term appears.
 *
 * Pass a band through `value` rather than appending it outside — `<NtrpLabel value={level} />`, not
 * `<NtrpLabel /> {level}`. Keeping the whole label inside the trigger gives a larger click target and,
 * importantly, keeps the label as a **single text node**: Testing Library's `getNodeText` concatenates
 * only *direct* child text nodes, so splitting "NTRP 4.0" across a button and a sibling text node makes
 * it unfindable by `getByText` and silently breaks assertions across the suite.
 *
 * Unlike {@link ConfidenceValue} this deliberately carries **no ⓘ icon**, only a dotted underline. NTRP
 * appears many times on a single screen (a 16-player roster renders it 16 times); an icon per instance
 * would be visual noise, while the underline still signals "there is more here". Not an oversight —
 * a considered difference from the confidence affordance.
 *
 * Deliberately NOT used for:
 * - the self-rating **guide links** (`NtrpSelfRatingSelect`, `PendingAssessmentSection`) — already
 *   external anchors, and a trigger nested in an `<a>` is invalid HTML;
 * - `aria-label`s, the sign-up validation toast, and the seeding CSV header — no element to click.
 *
 * See #842 for the full surface list and the reasoning behind each exclusion.
 */
export function NtrpLabel({ value, children, className, calibrating = false }: NtrpLabelProps) {
  const descriptionId = useId()
  // The marker joins the same string rather than being rendered as a sibling: Testing Library's
  // getNodeText concatenates only DIRECT child text nodes, so a sibling would make "NTRP 4.0"
  // unfindable by getByText and silently break assertions across the suite.
  const base = children ?? (value == null ? 'NTRP' : `NTRP ${value}`)
  const text = calibrating && typeof base === 'string' ? `${base}${CALIBRATION_MARKER}` : base
  return (
    <Popover>
      <PopoverTrigger asChild>
        <button
          type="button"
          aria-label={
            calibrating
              ? 'NTRP — about this rating framework, and this rating is still being calibrated'
              : 'NTRP — about this rating framework'
          }
          aria-describedby={descriptionId}
          className={cn(
            'inline underline decoration-dotted underline-offset-2 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring rounded-sm',
            className,
          )}
        >
          {text}
        </button>
      </PopoverTrigger>
      <PopoverContent id={descriptionId} role="tooltip">
        {calibrating ? (
          <>
            <span className="mb-2 block font-medium">{NTRP_CALIBRATION_NOTE}</span>
            {NTRP_DISCLAIMER}
          </>
        ) : (
          NTRP_DISCLAIMER
        )}
      </PopoverContent>
    </Popover>
  )
}

/**
 * An ⓘ-only disclaimer trigger, for places the term itself must not be wrapped (#842):
 *
 * - **Beside a `<label>`, never inside it.** Wrapping label text in a button changes the control's
 *   accessible name — a screen reader would announce the button instead of "NTRP self-rating" — and a
 *   nested button also swallows the click that should focus the field. Both are real regressions, not
 *   just test breakage.
 * - Beside a dense value where a second underlined phrase would be noise.
 *
 * The meaning lives in the `aria-label`, so it is announced even though the glyph carries no text.
 */
export function NtrpDisclaimerInfo({ className }: { className?: string }) {
  const descriptionId = useId()
  return (
    <Popover>
      <PopoverTrigger asChild>
        <button
          type="button"
          aria-label="About the NTRP rating framework"
          aria-describedby={descriptionId}
          className={cn(
            'inline-flex align-middle text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring rounded-sm',
            className,
          )}
        >
          <Info aria-hidden="true" className="size-3" />
        </button>
      </PopoverTrigger>
      <PopoverContent id={descriptionId} role="tooltip">
        {NTRP_DISCLAIMER}
      </PopoverContent>
    </Popover>
  )
}
