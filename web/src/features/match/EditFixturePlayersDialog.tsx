import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { PlayerPicker } from '@/components/PlayerPicker'
import { usePutApiV1MatchesIdPlayers } from '@/api/generated/matches/matches'
import type { MatchResponse } from '@/api/generated/model'

/** A side being edited: the chosen ids, in slot order, with the names to show them by. */
interface Side {
  ids: string[]
  names: string[]
}

/**
 * Change who is playing a fixture (#957).
 *
 * Until this, the only way was to delete the fixture and create a new one — which burns the match
 * number (an identifier that is never recycled, #898), leaves a permanent gap in the event's numbering,
 * and requires dragging the replacement back into position.
 *
 * **Both sides are replaced wholesale**, matching the API: a fixture has two sides, and sending both
 * makes the resulting line-up unambiguous where a patch would leave "unchanged" and "cleared" looking
 * the same.
 *
 * The picker is filtered to the event's participants because the server requires it — offering someone
 * who would be rejected is the #867 shape.
 */
export function EditFixturePlayersDialog({
  match,
  nameOf,
  onClose,
}: {
  match: MatchResponse
  /** Resolves a user id to a display name — MatchSideResponse carries ids only. */
  nameOf: (userId: string) => string
  onClose: () => void
}) {
  const queryClient = useQueryClient()
  const sideFrom = (userIds: string[]): Side => ({
    ids: userIds,
    names: userIds.map(nameOf),
  })
  const [team1, setTeam1] = useState<Side>(() => sideFrom(match.team1.userIds))
  const [team2, setTeam2] = useState<Side>(() => sideFrom(match.team2.userIds))

  const perSide = match.matchFormat === 'SINGLES' ? 1 : 2
  const complete = team1.ids.length === perSide && team2.ids.length === perSide
  const changed =
    team1.ids.join() !== match.team1.userIds.join() ||
    team2.ids.join() !== match.team2.userIds.join()

  const save = usePutApiV1MatchesIdPlayers({
    mutation: {
      onSuccess: () => {
        toast.success('Players updated')
        void queryClient.invalidateQueries()
        onClose()
      },
      onError: (err: unknown) => {
        const message = (err as { response?: { data?: { message?: string } } })?.response?.data
          ?.message
        toast.error(message ?? 'Could not change the players')
      },
    },
  })

  function renderSide(label: string, side: Side, set: (s: Side) => void, other: Side) {
    return (
      <div className="space-y-2">
        <div className="text-xs font-medium uppercase text-muted-foreground">{label}</div>
        {side.ids.map((id, i) => (
          <div key={id} className="flex items-center justify-between gap-2 rounded border px-2 py-1">
            <span className="truncate text-sm">{side.names[i]}</span>
            <Button
              size="sm"
              variant="ghost"
              aria-label={`Remove ${side.names[i]} from ${label}`}
              onClick={() =>
                set({
                  ids: side.ids.filter((_, j) => j !== i),
                  names: side.names.filter((_, j) => j !== i),
                })
              }
            >
              Remove
            </Button>
          </div>
        ))}
        {side.ids.length < perSide ? (
          <PlayerPicker
            label={`Add to ${label}`}
            // Nobody already on either side: the server rejects a duplicate, so offering one would be a
            // control that answers 400.
            excludeIds={[...side.ids, ...other.ids]}
            onSelect={(player) =>
              set({
                ids: [...side.ids, player.id],
                names: [...side.names, player.displayName ?? 'Unknown'],
              })
            }
          />
        ) : null}
      </div>
    )
  }

  return (
    <div className="space-y-4 rounded-lg border p-4">
      <div className="grid gap-4 sm:grid-cols-2">
        {renderSide('Side 1', team1, setTeam1, team2)}
        {renderSide('Side 2', team2, setTeam2, team1)}
      </div>
      <div className="flex items-center gap-2">
        <Button
          disabled={!complete || !changed || save.isPending}
          onClick={() =>
            save.mutate({ id: match.id, data: { team1: team1.ids, team2: team2.ids } })
          }
        >
          Save players
        </Button>
        <Button variant="secondary" onClick={onClose} disabled={save.isPending}>
          Cancel
        </Button>
        {!complete ? (
          <span className="text-xs text-muted-foreground">
            {match.matchFormat === 'SINGLES' ? 'One player' : 'Two players'} per side.
          </span>
        ) : null}
        {/* The picker cannot be filtered to the event's participants — PlayerPicker has no such
            filter and the search API takes no eventId — so a non-participant is reachable here and
            the server rejects it. Its message is surfaced verbatim rather than swallowed. */}
      </div>
    </div>
  )
}
