import { useState, type FormEvent } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { UserSearchSelect } from '@/components/UserSearchSelect'
import {
  getGetApiV1MatchesQueryKey,
  usePostApiV1Matches,
} from '@/api/generated/matches/matches'
import { GetApiV1MatchesFilter } from '@/api/generated/model'
import type { UserSummaryResponse } from '@/api/generated/model'

const SYSTEMS = ['NTRP', 'UTR'] as const
const FORMATS = ['BEST_OF_THREE', 'BEST_OF_FIVE'] as const
const AWAITING = { filter: GetApiV1MatchesFilter['awaiting-results'] }

function PickedPlayer({
  user,
  onClear,
}: {
  user: UserSummaryResponse
  onClear: () => void
}) {
  return (
    <div className="flex items-center justify-between rounded-md border p-2 text-sm">
      <span className="font-medium">{user.displayName ?? user.id}</span>
      <Button type="button" variant="ghost" size="sm" onClick={onClear}>
        Change
      </Button>
    </div>
  )
}

export function CreateFixtureSection() {
  const queryClient = useQueryClient()
  const [player1, setPlayer1] = useState<UserSummaryResponse | null>(null)
  const [player2, setPlayer2] = useState<UserSummaryResponse | null>(null)
  const [system, setSystem] = useState<(typeof SYSTEMS)[number]>('NTRP')
  const [format, setFormat] = useState<(typeof FORMATS)[number]>('BEST_OF_THREE')
  const [date, setDate] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [created, setCreated] = useState(false)

  const create = usePostApiV1Matches({
    mutation: {
      onSuccess: () => {
        setCreated(true)
        setError(null)
        setPlayer1(null)
        setPlayer2(null)
        setDate('')
        queryClient.invalidateQueries({ queryKey: getGetApiV1MatchesQueryKey(AWAITING) })
      },
    },
  })

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    if (!player1 || !player2) return
    setError(null)
    setCreated(false)
    try {
      await create.mutateAsync({
        data: {
          ratingSystem: system,
          matchType: 'SINGLES',
          matchFormat: format,
          matchDate: date,
          team1: [player1.id],
          team2: [player2.id],
        },
      })
    } catch {
      setError('Could not create the fixture. Both players need a rating in the chosen system.')
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Schedule a fixture</CardTitle>
        <CardDescription>
          Pick two rated players. Recording results later does not move ratings —
          that's the admin calculation step.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <form onSubmit={onSubmit} className="space-y-4">
          {player1 ? (
            <PickedPlayer user={player1} onClear={() => setPlayer1(null)} />
          ) : (
            <UserSearchSelect
              label="Player 1"
              excludeIds={player2 ? [player2.id] : []}
              onSelect={setPlayer1}
            />
          )}
          {player2 ? (
            <PickedPlayer user={player2} onClear={() => setPlayer2(null)} />
          ) : (
            <UserSearchSelect
              label="Player 2"
              excludeIds={player1 ? [player1.id] : []}
              onSelect={setPlayer2}
            />
          )}

          <div className="flex flex-wrap gap-3">
            <div className="space-y-1">
              <Label htmlFor="system">System</Label>
              <select
                id="system"
                value={system}
                onChange={(e) => setSystem(e.target.value as (typeof SYSTEMS)[number])}
                className="h-9 rounded-md border border-input bg-transparent px-2 text-sm"
              >
                {SYSTEMS.map((s) => (
                  <option key={s} value={s}>
                    {s}
                  </option>
                ))}
              </select>
            </div>
            <div className="space-y-1">
              <Label htmlFor="format">Format</Label>
              <select
                id="format"
                value={format}
                onChange={(e) => setFormat(e.target.value as (typeof FORMATS)[number])}
                className="h-9 rounded-md border border-input bg-transparent px-2 text-sm"
              >
                {FORMATS.map((f) => (
                  <option key={f} value={f}>
                    {f.replace('BEST_OF_', 'Best of ').toLowerCase()}
                  </option>
                ))}
              </select>
            </div>
            <div className="space-y-1">
              <Label htmlFor="date">Date</Label>
              <Input
                id="date"
                type="date"
                value={date}
                onChange={(e) => setDate(e.target.value)}
                required
              />
            </div>
          </div>

          {error ? (
            <p className="text-sm text-destructive" role="alert">
              {error}
            </p>
          ) : null}
          {created ? (
            <p className="text-sm text-foreground" role="status">
              Fixture scheduled.
            </p>
          ) : null}

          <Button
            type="submit"
            disabled={!player1 || !player2 || !date || create.isPending}
          >
            {create.isPending ? 'Scheduling…' : 'Schedule fixture'}
          </Button>
        </form>
      </CardContent>
    </Card>
  )
}
