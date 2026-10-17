import type { ReactNode } from 'react'
import { PlaceholderTag } from '@/components/PlaceholderTag'
import { PopoverContent } from '@/components/ui/popover'
import type { StepScorer } from './replayScorers'

/**
 * The small popover naming who scored a replay step (#1183). Opened by clicking the step's badge in the
 * timeline or its mark in the swimlane — nothing is drawn inline, to save space — and only ever rendered
 * when the server sent scorers, which it does only to the event's organizers and administrators.
 */
export function ScorerPopoverContent({ scorer, title }: { scorer: StepScorer; title: ReactNode }) {
  return (
    <PopoverContent className="w-auto max-w-64 p-2 text-xs" data-testid="replay-scorer-popover">
      <p className="text-muted-foreground">{title}</p>
      <p className="font-medium">
        Scored by {scorer.label}
        <PlaceholderTag show={scorer.isPlaceholder} deleted={scorer.isDeleted} />
      </p>
    </PopoverContent>
  )
}
