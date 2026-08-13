import type { EventParticipantResponse } from '@/api/generated/model'
import { Button } from '@/components/ui/button'
import { ContentLink } from '@/components/ContentLink'
import { PlaceholderTag } from '@/components/PlaceholderTag'
import { playerLabel } from '@/lib/playerLabel'
import { participantMeta } from './eventFacets'

/**
 * The event's roster, rendered once for every audience (#741). `/events/{code}` is the single event
 * view, so this is the only participant list in the app: an anonymous visitor, a player, and an
 * organizer all read the same component and differ only by what it is allowed to show.
 *
 * - The name always links to the player's public profile when they have one.
 * - The sex/age/NTRP meta line is match-manager-only (#741). It is not conditionally hidden here so
 *   much as simply absent: the public payload never carries those fields, so [participantMeta]
 *   returns an empty string for a non-manager viewer even if this component were misused.
 * - Remove appears only when [onRemove] is supplied — i.e. for a manager on an unlocked event.
 */
export function EventParticipantList({
  participants,
  showCodes = false,
  onRemove,
  removing = false,
  emptyText = 'No participants yet.',
}: {
  participants: EventParticipantResponse[]
  showCodes?: boolean
  onRemove?: (userId: string) => void
  removing?: boolean
  emptyText?: string
}) {
  if (participants.length === 0) {
    return <p className="text-sm text-muted-foreground">{emptyText}</p>
  }
  return (
    <ul className="space-y-1 text-sm">
      {participants.map((p) => {
        const meta = participantMeta(p)
        const label = playerLabel(p.displayName, p.publicCode, p.userId)
        return (
          <li key={p.userId} className="flex items-center justify-between gap-2">
            <span className="min-w-0">
              <span className="block">
                {p.publicCode ? (
                  <ContentLink to={`/players/${p.publicCode}`}>{label}</ContentLink>
                ) : (
                  label
                )}
                <PlaceholderTag show={p.isPlaceholder} deleted={p.isDeleted} />
                {showCodes && p.publicCode ? (
                  <span className="text-muted-foreground"> ({p.publicCode})</span>
                ) : null}
              </span>
              {meta ? <span className="block text-xs text-muted-foreground">{meta}</span> : null}
            </span>
            {onRemove ? (
              <Button
                type="button"
                variant="ghost"
                size="sm"
                disabled={removing}
                onClick={() => onRemove(p.userId)}
              >
                Remove
              </Button>
            ) : null}
          </li>
        )
      })}
    </ul>
  )
}
