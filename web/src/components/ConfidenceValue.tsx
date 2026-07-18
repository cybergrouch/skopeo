import { useId } from 'react'
import { Info } from 'lucide-react'
import { formatConfidence } from '@/lib/confidence'
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '@/components/ui/popover'
import { cn } from '@/lib/utils'

/**
 * The single source of truth for how the rating-*confidence* percentage (#343, #459) is projected in
 * the UI: it renders the formatted percent (via {@link formatConfidence}) with a visible, interactive
 * affordance — a dotted underline plus an ⓘ icon — that opens a short explanation on click/tap, focus,
 * and hover (Radix Popover, so it works on mobile and via keyboard). Renders nothing when there's no
 * value, matching the prior inline `· {pct}` behavior at every call site.
 */
export const CONFIDENCE_EXPLANATION =
  'Rating confidence — how much recent, consistent match activity backs this NTRP band rating.'

interface ConfidenceValueProps {
  /** Raw confidence as a 0..1 decimal string (or null/undefined when absent). */
  confidence?: string | null
  /** Extra classes for the inline trigger. */
  className?: string
}

export function ConfidenceValue({ confidence, className }: ConfidenceValueProps) {
  const pct = formatConfidence(confidence)
  const descriptionId = useId()
  if (pct == null) return null

  return (
    <Popover>
      <PopoverTrigger asChild>
        <button
          type="button"
          aria-label={`Rating confidence ${pct}. What is this?`}
          aria-describedby={descriptionId}
          className={cn(
            'inline-flex items-center gap-0.5 underline decoration-dotted underline-offset-2 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring rounded-sm',
            className,
          )}
        >
          {pct}
          <Info aria-hidden="true" className="size-3" />
        </button>
      </PopoverTrigger>
      <PopoverContent id={descriptionId} role="tooltip">
        {CONFIDENCE_EXPLANATION}
      </PopoverContent>
    </Popover>
  )
}
