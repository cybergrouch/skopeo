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

interface NtrpLabelProps {
  /** A band or rating to render after the term, e.g. `4.0` → "NTRP 4.0". */
  value?: string | number
  /** Replace the whole trigger text. Defaults to "NTRP", or "NTRP {value}" when [value] is given. */
  children?: ReactNode
  /** Extra classes for the inline trigger. */
  className?: string
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
export function NtrpLabel({ value, children, className }: NtrpLabelProps) {
  const descriptionId = useId()
  const text = children ?? (value == null ? 'NTRP' : `NTRP ${value}`)
  return (
    <Popover>
      <PopoverTrigger asChild>
        <button
          type="button"
          aria-label="NTRP — about this rating framework"
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
        {NTRP_DISCLAIMER}
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
