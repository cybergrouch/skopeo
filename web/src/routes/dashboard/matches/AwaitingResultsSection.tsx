import { useState } from 'react'
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
import {
  getGetApiV1MatchesQueryKey,
  useGetApiV1Matches,
  usePostApiV1MatchesIdResult,
} from '@/api/generated/matches/matches'
import { useGetApiV1Users } from '@/api/generated/users/users'
import { GetApiV1MatchesFilter } from '@/api/generated/model'
import type { MatchResponse, SetScoreRequest } from '@/api/generated/model'

const AWAITING = { filter: GetApiV1MatchesFilter['awaiting-results'] }
const MAX_SETS = 5

interface SetRow {
  t1: string
  t2: string
}

function toSets(rows: SetRow[]): SetScoreRequest[] {
  return rows
    .filter((r) => r.t1.trim() !== '' && r.t2.trim() !== '')
    .map((r) => ({ team1Games: Number(r.t1), team2Games: Number(r.t2) }))
}

function MatchResultRow({
  match,
  nameOf,
}: {
  match: MatchResponse
  nameOf: (userId: string) => string
}) {
  const queryClient = useQueryClient()
  const [rows, setRows] = useState<SetRow[]>([
    { t1: '', t2: '' },
    { t1: '', t2: '' },
  ])
  const [error, setError] = useState<string | null>(null)

  const upload = usePostApiV1MatchesIdResult({
    mutation: {
      onSuccess: () =>
        queryClient.invalidateQueries({ queryKey: getGetApiV1MatchesQueryKey(AWAITING) }),
    },
  })

  function setCell(index: number, key: keyof SetRow, value: string) {
    setRows((prev) => prev.map((r, i) => (i === index ? { ...r, [key]: value } : r)))
  }

  async function submit() {
    setError(null)
    const sets = toSets(rows)
    if (sets.length === 0) {
      setError('Enter at least one set.')
      return
    }
    try {
      await upload.mutateAsync({ id: match.id, data: { sets } })
    } catch {
      setError('Could not record the result. Each set needs a clear winner.')
    }
  }

  const player1 = match.team1.userIds.map(nameOf).join(', ')
  const player2 = match.team2.userIds.map(nameOf).join(', ')

  return (
    <div className="rounded-lg border p-3">
      <div className="mb-2 text-sm">
        <span className="font-medium">
          {player1} vs {player2}
        </span>
        <span className="text-muted-foreground"> · {match.matchDate}</span>
      </div>
      <div className="space-y-2">
        {rows.map((row, index) => (
          <div key={index} className="flex items-center gap-2">
            <span className="w-12 text-xs text-muted-foreground">Set {index + 1}</span>
            <Input
              aria-label={`set ${index + 1} player 1 games`}
              className="w-16"
              inputMode="numeric"
              value={row.t1}
              onChange={(e) => setCell(index, 't1', e.target.value)}
            />
            <span>–</span>
            <Input
              aria-label={`set ${index + 1} player 2 games`}
              className="w-16"
              inputMode="numeric"
              value={row.t2}
              onChange={(e) => setCell(index, 't2', e.target.value)}
            />
            {rows.length > 1 ? (
              <Button
                type="button"
                variant="ghost"
                size="sm"
                onClick={() => setRows((prev) => prev.filter((_, i) => i !== index))}
              >
                Remove
              </Button>
            ) : null}
          </div>
        ))}
      </div>
      <div className="mt-3 flex flex-wrap gap-2">
        {rows.length < MAX_SETS ? (
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => setRows((prev) => [...prev, { t1: '', t2: '' }])}
          >
            Add set
          </Button>
        ) : null}
        <Button size="sm" disabled={upload.isPending} onClick={submit}>
          {upload.isPending ? 'Recording…' : 'Record result'}
        </Button>
      </div>
      {error ? (
        <p className="mt-2 text-sm text-destructive" role="alert">
          {error}
        </p>
      ) : null}
    </div>
  )
}

export function AwaitingResultsSection() {
  const matchesQuery = useGetApiV1Matches(AWAITING)
  const matches = matchesQuery.data ?? []

  const ids = [...new Set(matches.flatMap((m) => [...m.team1.userIds, ...m.team2.userIds]))]
  const usersQuery = useGetApiV1Users(
    { ids: ids.join(',') },
    { query: { enabled: ids.length > 0 } },
  )
  const nameById = new Map((usersQuery.data ?? []).map((u) => [u.id, u.displayName ?? u.id]))
  const nameOf = (userId: string) => nameById.get(userId) ?? userId.slice(0, 8)

  return (
    <Card>
      <CardHeader>
        <CardTitle>Awaiting results</CardTitle>
        <CardDescription>
          Your scheduled fixtures awaiting results — they can be played anytime, so
          record the set scores whenever the match happens; the server derives the
          winner.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {matchesQuery.isLoading ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : matches.length > 0 ? (
          matches.map((match) => (
            <MatchResultRow key={match.id} match={match} nameOf={nameOf} />
          ))
        ) : (
          <p className="text-sm text-muted-foreground">No fixtures awaiting results.</p>
        )}
      </CardContent>
    </Card>
  )
}
