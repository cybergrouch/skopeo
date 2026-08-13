import type { EventPublicResponse } from '@/api/generated/model'
import { CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { EVENT_TYPE_LABELS } from './eventFacets'

/**
 * The event header for a viewer without match-management rights (#741) — including anonymous ones.
 *
 * Deliberately a separate component from the manager header rather than one component threaded with
 * `canManage &&` conditionals: the two audiences diverge most here (rename, club editor, admin
 * actions), and keeping the public variant its own file makes it provable by reading that it carries
 * no manager-only affordance or field.
 */
export function EventHeaderPublic({ event }: { event: EventPublicResponse }) {
  return (
    <CardHeader>
      <div className="flex items-center gap-2">
        <CardTitle>{event.name}</CardTitle>
        {event.isFinalized ? (
          <span
            className="rounded-full border border-emerald-500/50 bg-emerald-500/10 px-2 py-0.5 text-xs font-medium text-emerald-700 dark:text-emerald-400"
            data-testid="finalized-badge"
          >
            Finalized
          </span>
        ) : null}
      </div>
      <CardDescription>
        {event.type ? (EVENT_TYPE_LABELS[event.type] ?? event.type) : null}
        {' · '}
        {event.startDate} – {event.endDate} · Event ID:{' '}
        <code className="select-all font-mono font-medium text-foreground">{event.publicCode}</code>
      </CardDescription>
    </CardHeader>
  )
}
