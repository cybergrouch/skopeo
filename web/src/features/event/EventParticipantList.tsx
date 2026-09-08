import type { EventParticipantResponse } from '@/api/generated/model'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { ContentLink } from '@/components/ContentLink'
import { PlaceholderTag } from '@/components/PlaceholderTag'
import { SetRatingForm } from '@/components/SetRatingForm'
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
 * - The unrated flag and its inline rating form appear only when [onRated] is supplied (#907). A
 *   self-rated player who has never been assessed used to be invisible here, and only surfaced as
 *   "User <uuid> has no rating" when the host tried to build a fixture — sending them to the Ratings
 *   tab and back through four screens. The work now happens where the problem is discovered.
 */
export function EventParticipantList({
  participants,
  showCodes = false,
  onRemove,
  removing = false,
  emptyText = 'No participants yet.',
  onRated,
}: {
  participants: EventParticipantResponse[]
  showCodes?: boolean
  onRemove?: (userId: string) => void
  removing?: boolean
  emptyText?: string
  // Supplied by an organizer who may rate; called after a rating is saved so the roster refetches.
  onRated?: () => void
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
              {onRated && !p.rating ? (
                <span className="mt-1 block">
                  <Badge variant="outline">Unrated</Badge>
                  {p.proposedRating ? (
                    <span className="ml-2 text-xs text-muted-foreground">
                      Self-rated:{' '}
                      <span className="font-medium text-foreground">{p.proposedRating}</span>
                    </span>
                  ) : null}
                  {/* Prefilled with the self-rating so approving it as-is is one click; a different
                      value overrides it. Same control the Ratings tab uses, moved to the work. */}
                  <span className="mt-1 block">
                    <SetRatingForm
                      userId={p.userId}
                      initialValue={p.proposedRating ?? ''}
                      onSaved={onRated}
                    />
                  </span>
                </span>
              ) : null}
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
